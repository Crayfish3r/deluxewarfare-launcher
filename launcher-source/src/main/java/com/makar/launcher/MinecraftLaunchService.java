package com.makar.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class MinecraftLaunchService {
    private static final String DEFAULT_VERSION_TYPE = "Forge";
    private static final String DEFAULT_LAUNCHER_NAME = "DeluxeWarfareLauncher";
    private static final String DEFAULT_LAUNCHER_VERSION = "2.0.0";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Process startMinecraft(MinecraftLaunchOptions options, Consumer<String> logConsumer) {
        ProcessBuilder processBuilder = createProcessBuilder(options);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            CompletableFuture.runAsync(() -> streamProcessLog(process, logConsumer));
            return process;
        } catch (IOException exception) {
            throw new MinecraftLaunchException("Unable to start Minecraft process.", exception);
        }
    }

    public ProcessBuilder createProcessBuilder(MinecraftLaunchOptions options) {
        Path gameDirectory = options.getGameDirectory();
        String forgeVersionName = options.getForgeVersionName();

        JsonNode forgeVersionJson = readVersionJson(gameDirectory, forgeVersionName);
        List<JsonNode> versionChain = loadVersionChain(gameDirectory, forgeVersionJson);

        String mainClass = getLastTextValue(versionChain, "mainClass");
        if (mainClass.isBlank()) {
            throw new MinecraftLaunchException("Minecraft mainClass is missing in version JSON.");
        }

        String classpath = buildClasspath(versionChain, gameDirectory, options);
        Path nativesDirectory = gameDirectory.resolve("natives").resolve(forgeVersionName);
        String assetIndexName = getAssetIndexName(versionChain);
        String loggingArgument = getLoggingArgument(versionChain, gameDirectory);

        Map<String, String> placeholders = createPlaceholders(
                options,
                classpath,
                nativesDirectory,
                assetIndexName
        );

        List<String> command = new ArrayList<>();
        command.add(options.getJavaExecutable());
        List<String> jvmArguments = collectArguments(versionChain, "jvm", placeholders, options);
        applyMemoryArguments(jvmArguments, options.getMemoryGb());
        if (jvmArguments.stream().noneMatch(argument -> argument.startsWith("-Djava.library.path="))) {
            jvmArguments.add("-Djava.library.path=" + nativesDirectory);
        }
        if (!loggingArgument.isBlank()) {
            jvmArguments.add(loggingArgument);
        }
        if (!containsClasspathArgument(jvmArguments)) {
            jvmArguments.add("-cp");
            jvmArguments.add(classpath);
        }

        command.addAll(jvmArguments);
        command.add(mainClass);
        command.addAll(collectArguments(versionChain, "game", placeholders, options));

        if (options.shouldAutoJoinServer()) {
            command.add("--server");
            command.add(options.getServerHost());
            command.add("--port");
            command.add(Integer.toString(options.getServerPort()));
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(gameDirectory.toFile());
        return processBuilder;
    }

    private void applyMemoryArguments(List<String> jvmArguments, int memoryGb) {
        jvmArguments.removeIf(argument -> argument.startsWith("-Xmx") || argument.startsWith("-Xms"));
        jvmArguments.add("-Xms4G");
        jvmArguments.add("-Xmx" + Math.max(4, memoryGb) + "G");
    }

    private List<JsonNode> loadVersionChain(Path gameDirectory, JsonNode forgeVersionJson) {
        List<JsonNode> versionChain = new ArrayList<>();
        String inheritedVersion = forgeVersionJson.path("inheritsFrom").asText("");

        if (!inheritedVersion.isBlank()) {
            versionChain.add(readVersionJson(gameDirectory, inheritedVersion));
        }

        versionChain.add(forgeVersionJson);
        return versionChain;
    }

    private String buildClasspath(List<JsonNode> versionChain, Path gameDirectory, MinecraftLaunchOptions options) {
        LinkedHashSet<String> classpathEntries = new LinkedHashSet<>();
        OperatingSystem operatingSystem = OperatingSystem.current();

        for (JsonNode versionJson : versionChain) {
            for (JsonNode library : versionJson.path("libraries")) {
                if (!isAllowedByRules(library.path("rules"), operatingSystem, options)) {
                    continue;
                }

                String artifactPath = getArtifactPath(library);
                if (!artifactPath.isBlank()) {
                    Path localPath = gameDirectory.resolve("libraries").resolve(artifactPath);
                    if (Files.isRegularFile(localPath)
                            && !isDuplicateMinecraftClientJar(localPath, artifactPath, options.getMinecraftVersion())) {
                        classpathEntries.add(localPath.toString());
                    }
                }
            }
        }

        if (!hasMinecraftClientLibrary(versionChain)) {
            Path clientJar = gameDirectory
                    .resolve("versions")
                    .resolve(options.getMinecraftVersion())
                    .resolve(options.getMinecraftVersion() + ".jar");
            if (Files.isRegularFile(clientJar)
                    && !isDuplicateMinecraftClientJar(clientJar, "", options.getMinecraftVersion())) {
                classpathEntries.add(clientJar.toString());
            }
        }

        if (classpathEntries.isEmpty()) {
            throw new MinecraftLaunchException("Minecraft classpath is empty. Run install/update first.");
        }

        return String.join(System.getProperty("path.separator"), classpathEntries);
    }



    private boolean isDuplicateMinecraftClientJar(Path localPath, String artifactPath, String minecraftVersion) {
        String normalizedArtifactPath = artifactPath.replace('\\', '/');
        String normalizedLocalPath = localPath.toString().replace('\\', '/');

        if (normalizedLocalPath.endsWith("/versions/" + minecraftVersion + "/" + minecraftVersion + ".jar")) {
            return true;
        }

        if (!normalizedArtifactPath.startsWith("net/minecraft/client/")) {
            return false;
        }

        String fileName = localPath.getFileName().toString();
        if (fileName.contains("-srg.")) {
            return false;
        }

        return fileName.equals("client-" + minecraftVersion + ".jar")
                || fileName.startsWith("client-" + minecraftVersion + "-");
    }

    private List<String> collectArguments(
            List<JsonNode> versionChain,
            String section,
            Map<String, String> placeholders,
            MinecraftLaunchOptions options
    ) {
        List<String> arguments = new ArrayList<>();
        for (JsonNode versionJson : versionChain) {
            JsonNode modernArguments = versionJson.path("arguments").path(section);
            if (modernArguments.isArray()) {
                for (JsonNode argument : modernArguments) {
                    addArgument(arguments, argument, placeholders, options);
                }
                continue;
            }

            if ("game".equals(section)) {
                String legacyArguments = versionJson.path("minecraftArguments").asText("");
                if (!legacyArguments.isBlank()) {
                    for (String argument : legacyArguments.split(" ")) {
                        arguments.add(replacePlaceholders(argument, placeholders));
                    }
                }
            }
        }

        return arguments;
    }

    private void addArgument(List<String> arguments, JsonNode argument, Map<String, String> placeholders, MinecraftLaunchOptions options) {
        if (argument.isTextual()) {
            arguments.add(replacePlaceholders(argument.asText(), placeholders));
            return;
        }

        if (!argument.isObject()) {
            return;
        }

        if (!isAllowedByRules(argument.path("rules"), OperatingSystem.current(), options)) {
            return;
        }

        JsonNode value = argument.path("value");
        if (value.isTextual()) {
            arguments.add(replacePlaceholders(value.asText(), placeholders));
            return;
        }

        if (value.isArray()) {
            for (JsonNode item : value) {
                if (item.isTextual()) {
                    arguments.add(replacePlaceholders(item.asText(), placeholders));
                }
            }
        }
    }

    private Map<String, String> createPlaceholders(
            MinecraftLaunchOptions options,
            String classpath,
            Path nativesDirectory,
            String assetIndexName
    ) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("auth_player_name", options.getNickname());
        placeholders.put("version_name", options.getForgeVersionName());
        placeholders.put("game_directory", options.getGameDirectory().toString());
        placeholders.put("assets_root", options.getGameDirectory().resolve("assets").toString());
        placeholders.put("assets_index_name", assetIndexName);
        placeholders.put("auth_uuid", getPlayerUuid(options));
        placeholders.put("auth_access_token", "0");
        placeholders.put("clientid", "0");
        placeholders.put("auth_xuid", "0");
        placeholders.put("user_type", "legacy");
        placeholders.put("version_type", DEFAULT_VERSION_TYPE);
        placeholders.put("natives_directory", nativesDirectory.toString());
        placeholders.put("launcher_name", DEFAULT_LAUNCHER_NAME);
        placeholders.put("launcher_version", DEFAULT_LAUNCHER_VERSION);
        placeholders.put("classpath", classpath);
        placeholders.put("classpath_separator", System.getProperty("path.separator"));
        placeholders.put("library_directory", options.getGameDirectory().resolve("libraries").toString());
        placeholders.put("user_properties", "{}");
        placeholders.put("quickPlayPath", options.shouldAutoJoinServer() ? createQuickPlayPath(options).toString() : "");
        placeholders.put("quickPlaySingleplayer", "");
        placeholders.put("quickPlayMultiplayer", options.shouldAutoJoinServer() ? options.getServerAddress() : "");
        placeholders.put("quickPlayRealms", "");
        placeholders.put("resolution_width", "854");
        placeholders.put("resolution_height", "480");
        return placeholders;
    }

    private String replacePlaceholders(String value, Map<String, String> placeholders) {
        String result = value;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            result = result.replace("${" + placeholder.getKey() + "}", placeholder.getValue());
        }
        return result;
    }

    private String getAssetIndexName(List<JsonNode> versionChain) {
        for (JsonNode versionJson : versionChain) {
            String assetIndex = versionJson.path("assetIndex").path("id").asText("");
            if (!assetIndex.isBlank()) {
                return assetIndex;
            }

            String assets = versionJson.path("assets").asText("");
            if (!assets.isBlank()) {
                return assets;
            }
        }

        throw new MinecraftLaunchException("Asset index name is missing in version JSON.");
    }

    private String getLoggingArgument(List<JsonNode> versionChain, Path gameDirectory) {
        for (JsonNode versionJson : versionChain) {
            JsonNode logging = versionJson.path("logging").path("client");
            String argument = logging.path("argument").asText("");
            String fileId = logging.path("file").path("id").asText("");
            if (!argument.isBlank() && !fileId.isBlank()) {
                Path loggingPath = gameDirectory.resolve("assets").resolve("log_configs").resolve(fileId);
                return argument.replace("${path}", loggingPath.toString());
            }
        }

        return "";
    }

    private String getLastTextValue(List<JsonNode> versionChain, String fieldName) {
        String value = "";
        for (JsonNode versionJson : versionChain) {
            String candidate = versionJson.path(fieldName).asText("");
            if (!candidate.isBlank()) {
                value = candidate;
            }
        }
        return value;
    }

    private String getArtifactPath(JsonNode library) {
        String downloadPath = library.path("downloads").path("artifact").path("path").asText("");
        if (!downloadPath.isBlank()) {
            return downloadPath;
        }

        String name = library.path("name").asText("");
        if (name.isBlank()) {
            return "";
        }

        return MavenArtifactPath.parse(name, "").path();
    }

    private boolean hasMinecraftClientLibrary(List<JsonNode> versionChain) {
        for (JsonNode versionJson : versionChain) {
            for (JsonNode library : versionJson.path("libraries")) {
                String name = library.path("name").asText("");
                if (name.startsWith("net.minecraft:client:")) {
                    return true;
                }

                String artifactPath = getArtifactPath(library);
                if (artifactPath.startsWith("net/minecraft/client/")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsClasspathArgument(List<String> arguments) {
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if ("-cp".equals(argument) || "-classpath".equals(argument) || "--class-path".equals(argument)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowedByRules(JsonNode rules, OperatingSystem operatingSystem, MinecraftLaunchOptions options) {
        if (rules == null || !rules.isArray() || rules.isEmpty()) {
            return true;
        }

        boolean allowed = false;
        for (JsonNode rule : rules) {
            if (!ruleMatches(rule, operatingSystem, options)) {
                continue;
            }
            allowed = "allow".equals(rule.path("action").asText(""));
        }
        return allowed;
    }

    private boolean ruleMatches(JsonNode rule, OperatingSystem operatingSystem, MinecraftLaunchOptions options) {
        JsonNode osRule = rule.path("os");
        if (!osRule.isMissingNode()) {
            String name = osRule.path("name").asText("");
            if (!name.isBlank() && !name.equals(operatingSystem.getLauncherName())) {
                return false;
            }
        }

        JsonNode featureRules = rule.path("features");
        if (!featureRules.isMissingNode() && featureRules.isObject()) {
            for (var featureIterator = featureRules.fields(); featureIterator.hasNext();) {
                Map.Entry<String, JsonNode> featureRule = featureIterator.next();
                boolean expectedValue = featureRule.getValue().asBoolean(false);
                if (isFeatureEnabled(featureRule.getKey(), options) != expectedValue) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean isFeatureEnabled(String featureName, MinecraftLaunchOptions options) {
        boolean quickPlayMultiplayer = options != null && options.shouldAutoJoinServer();

        return switch (featureName) {
            case "has_custom_resolution" -> true;
            case "has_quick_plays_support", "is_quick_play_multiplayer" -> quickPlayMultiplayer;
            case "is_demo_user", "is_quick_play_singleplayer", "is_quick_play_realms" -> false;
            default -> false;
        };
    }

    private Path createQuickPlayPath(MinecraftLaunchOptions options) {
        Path quickPlayDirectory = options.getGameDirectory().resolve("quickplay");
        try {
            Files.createDirectories(quickPlayDirectory);
        } catch (IOException exception) {
            throw new MinecraftLaunchException("Unable to create quick-play directory: " + quickPlayDirectory, exception);
        }

        return quickPlayDirectory.resolve("quickplay-multiplayer.json");
    }

    private JsonNode readVersionJson(Path gameDirectory, String versionName) {
        Path jsonPath = gameDirectory.resolve("versions").resolve(versionName).resolve(versionName + ".json");
        if (!Files.isRegularFile(jsonPath)) {
            throw new MinecraftLaunchException("Version JSON was not found: " + jsonPath);
        }

        try (InputStream inputStream = Files.newInputStream(jsonPath)) {
            return objectMapper.readTree(inputStream);
        } catch (IOException exception) {
            throw new MinecraftLaunchException("Unable to read version JSON: " + jsonPath, exception);
        }
    }

    private String createOfflineUuid(String nickname) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + nickname).getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    private String getPlayerUuid(MinecraftLaunchOptions options) {
        if (!options.getPlayerUuid().isBlank()) {
            return options.getPlayerUuid().replace("-", "");
        }

        return createOfflineUuid(options.getNickname());
    }

    private void streamProcessLog(Process process, Consumer<String> logConsumer) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logConsumer.accept("[Minecraft] " + line);
            }
        } catch (IOException exception) {
            logConsumer.accept("[Minecraft] Unable to read process log: " + exception.getMessage());
        }
    }

    private record MavenArtifactPath(String path) {
        static MavenArtifactPath parse(String coordinate, String classifierOverride) {
            String extension = "jar";
            String normalizedCoordinate = coordinate;
            int extensionSeparator = coordinate.indexOf('@');
            if (extensionSeparator >= 0) {
                extension = coordinate.substring(extensionSeparator + 1);
                normalizedCoordinate = coordinate.substring(0, extensionSeparator);
            }

            String[] parts = normalizedCoordinate.split(":");
            if (parts.length < 3) {
                throw new MinecraftLaunchException("Invalid Maven coordinate: " + coordinate);
            }

            String group = parts[0];
            String artifact = parts[1];
            String version = parts[2];
            String classifier = !classifierOverride.isBlank()
                    ? classifierOverride
                    : parts.length >= 4 ? parts[3] : "";

            String fileName = artifact + "-" + version + (classifier.isBlank() ? "" : "-" + classifier) + "." + extension;
            String path = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + fileName;
            return new MavenArtifactPath(path);
        }
    }

    public static final class MinecraftLaunchException extends RuntimeException {
        public MinecraftLaunchException(String message) {
            super(message);
        }

        public MinecraftLaunchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
