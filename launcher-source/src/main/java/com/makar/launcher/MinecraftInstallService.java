package com.makar.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class MinecraftInstallService {
    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FORGE_MAVEN_BASE_URL = "https://maven.minecraftforge.net/net/minecraftforge/forge";
    private static final String MOJANG_LIBRARIES_BASE_URL = "https://libraries.minecraft.net/";
    private static final String ASSETS_BASE_URL = "https://resources.download.minecraft.net/";
    private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(10);
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final int MINECRAFT_LIBRARY_DOWNLOAD_THREADS = 4;
    private static final int MINECRAFT_ASSET_DOWNLOAD_THREADS = 8;
    private static final long BASE_RETRY_DELAY_MILLIS = 500;
    private static final long MAX_RETRY_DELAY_MILLIS = 4_000;
    private static final long MAX_RETRY_AFTER_DELAY_MILLIS = 30_000;
    private static final int FORGE_INSTALLER_LAST_OUTPUT_LINES = 80;
    private static final long MIN_FORGE_INSTALLER_SIZE_BYTES = 64L * 1024L;
    private static final Pattern SHA1_HEX_PATTERN = Pattern.compile("(?i)[0-9a-f]{40}");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MinecraftInstallService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public void ensureInstalled(MinecraftInstallOptions options, Consumer<String> logConsumer) {
        ensureInstalled(options, logConsumer, (progress, status) -> { });
    }

    public void ensureInstalled(
            MinecraftInstallOptions options,
            Consumer<String> logConsumer,
            InstallProgressListener progressListener
    ) {
        Path gameDirectory = options.getGameDirectory();
        try {
            Files.createDirectories(gameDirectory);
        } catch (IOException exception) {
            throw new MinecraftInstallException("Unable to create game directory: " + gameDirectory, exception);
        }

        InstallMetrics metrics = new InstallMetrics();
        InstallProgressReporter progress = new InstallProgressReporter(progressListener);
        OperatingSystem operatingSystem = OperatingSystem.current();
        logConsumer.accept("Checking Minecraft " + options.getMinecraftVersion() + " files...");
        progress.report(0.02, "Проверка Minecraft manifest...");
        JsonNode vanillaVersion = ensureVanillaVersion(options, logConsumer, progress, metrics);
        ensureClientJar(vanillaVersion, options, logConsumer, progress, metrics);
        ensureLoggingConfig(vanillaVersion, options, logConsumer);
        ensureLibraries(
                vanillaVersion,
                options.getGameDirectory(),
                operatingSystem,
                logConsumer,
                progress,
                metrics,
                "Minecraft libraries",
                0.22,
                0.45
        );
        ensureAssets(vanillaVersion, options.getGameDirectory(), logConsumer, progress, metrics);

        logConsumer.accept("Checking Forge " + options.getForgeVersionName() + "...");
        progress.report(0.78, "Проверка Forge...");
        ensureForgeProfile(options, logConsumer, progress, metrics);
        JsonNode forgeVersion = readVersionJson(options.getGameDirectory(), options.getForgeVersionName());
        ensureLibraries(
                forgeVersion,
                options.getGameDirectory(),
                operatingSystem,
                logConsumer,
                progress,
                metrics,
                "Forge libraries",
                0.90,
                0.96
        );

        Path nativesDirectory = options.getGameDirectory().resolve("natives").resolve(options.getForgeVersionName());
        recreateDirectory(nativesDirectory);
        extractNatives(vanillaVersion, options.getGameDirectory(), nativesDirectory, operatingSystem, logConsumer);
        extractNatives(forgeVersion, options.getGameDirectory(), nativesDirectory, operatingSystem, logConsumer);

        progress.report(1.0, "Minecraft и Forge готовы");
        logConsumer.accept("Minecraft Forge is installed and ready.");
        logConsumer.accept(metrics.formatSummary());
    }

    private JsonNode ensureVanillaVersion(
            MinecraftInstallOptions options,
            Consumer<String> logConsumer,
            InstallProgressReporter progress,
            InstallMetrics metrics
    ) {
        Path versionDirectory = options.getGameDirectory()
                .resolve("versions")
                .resolve(options.getMinecraftVersion());
        Path versionJsonPath = versionDirectory.resolve(options.getMinecraftVersion() + ".json");

        if (Files.isRegularFile(versionJsonPath)) {
            progress.report(0.08, "Проверка version json...");
            return readJson(versionJsonPath, "Minecraft version JSON");
        }

        logConsumer.accept("Downloading Minecraft version manifest...");
        progress.report(0.04, "Проверка Minecraft manifest...");
        long manifestStartedAt = System.nanoTime();
        JsonNode versionManifest = downloadJson(VERSION_MANIFEST_URL, "Minecraft version manifest", logConsumer);
        metrics.versionManifestMillis = elapsedMillis(manifestStartedAt);
        String versionUrl = findVersionUrl(versionManifest, options.getMinecraftVersion());

        try {
            Files.createDirectories(versionDirectory);
        } catch (IOException exception) {
            throw new MinecraftInstallException("Unable to create version directory: " + versionDirectory, exception);
        }

        logConsumer.accept("Проверка version json...");
        progress.report(0.08, "Проверка version json...");
        long versionJsonStartedAt = System.nanoTime();
        downloadFile(versionUrl, versionJsonPath, "", 0, "Minecraft " + options.getMinecraftVersion() + " JSON", logConsumer);
        metrics.versionJsonMillis = elapsedMillis(versionJsonStartedAt);
        return readJson(versionJsonPath, "Minecraft version JSON");
    }

    private String findVersionUrl(JsonNode versionManifest, String minecraftVersion) {
        for (JsonNode version : versionManifest.path("versions")) {
            if (minecraftVersion.equals(version.path("id").asText())) {
                String url = version.path("url").asText("");
                if (!url.isBlank()) {
                    return url;
                }
            }
        }

        throw new MinecraftInstallException("Minecraft version was not found in Mojang manifest: " + minecraftVersion);
    }

    private void ensureClientJar(
            JsonNode versionJson,
            MinecraftInstallOptions options,
            Consumer<String> logConsumer,
            InstallProgressReporter progress,
            InstallMetrics metrics
    ) {
        JsonNode clientDownload = versionJson.path("downloads").path("client");
        String url = clientDownload.path("url").asText("");
        String sha1 = clientDownload.path("sha1").asText("");
        long size = clientDownload.path("size").asLong(0);
        if (url.isBlank()) {
            throw new MinecraftInstallException("Minecraft client download URL is missing in version JSON.");
        }

        Path clientJarPath = options.getGameDirectory()
                .resolve("versions")
                .resolve(options.getMinecraftVersion())
                .resolve(options.getMinecraftVersion() + ".jar");
        progress.report(0.14, "Проверка client jar...");
        long startedAt = System.nanoTime();
        downloadFile(url, clientJarPath, sha1, size, "Minecraft client jar", logConsumer);
        metrics.clientJarMillis = elapsedMillis(startedAt);
    }

    private void ensureLoggingConfig(JsonNode versionJson, MinecraftInstallOptions options, Consumer<String> logConsumer) {
        JsonNode loggingFile = versionJson.path("logging").path("client").path("file");
        String id = loggingFile.path("id").asText("");
        String url = loggingFile.path("url").asText("");
        if (id.isBlank() || url.isBlank()) {
            return;
        }

        Path loggingPath = options.getGameDirectory().resolve("assets").resolve("log_configs").resolve(id);
        downloadFile(
                url,
                loggingPath,
                loggingFile.path("sha1").asText(""),
                loggingFile.path("size").asLong(0),
                "Minecraft logging config",
                logConsumer
        );
    }

    private void ensureAssets(
            JsonNode versionJson,
            Path gameDirectory,
            Consumer<String> logConsumer,
            InstallProgressReporter progress,
            InstallMetrics metrics
    ) {
        JsonNode assetIndex = versionJson.path("assetIndex");
        String indexId = assetIndex.path("id").asText("");
        String indexUrl = assetIndex.path("url").asText("");
        if (indexId.isBlank() || indexUrl.isBlank()) {
            throw new MinecraftInstallException("Asset index is missing in Minecraft version JSON.");
        }

        progress.report(0.48, "Проверка ресурсов Minecraft...");
        Path indexPath = gameDirectory.resolve("assets").resolve("indexes").resolve(indexId + ".json");
        long indexStartedAt = System.nanoTime();
        downloadFile(
                indexUrl,
                indexPath,
                assetIndex.path("sha1").asText(""),
                assetIndex.path("size").asLong(0),
                "Minecraft asset index " + indexId,
                logConsumer
        );
        metrics.assetsIndexMillis += elapsedMillis(indexStartedAt);

        JsonNode indexJson = readJson(indexPath, "Minecraft asset index");
        JsonNode objects = indexJson.path("objects");
        if (!objects.isObject()) {
            throw new MinecraftInstallException("Некорректная metadata ресурсов Minecraft: objects должен быть объектом.");
        }
        long checkStartedAt = System.nanoTime();
        Map<Path, DownloadItem> missingAssetsByPath = new LinkedHashMap<>();
        Path objectsDirectory = gameDirectory.resolve("assets").resolve("objects").normalize();
        int totalObjects = 0;
        int checkedObjects = 0;
        int validObjects = 0;

        Iterator<JsonNode> iterator = objects.elements();
        while (iterator.hasNext()) {
            JsonNode asset = iterator.next();
            String hash = validateAssetHash(asset.path("hash").asText(""));

            totalObjects++;
            String prefix = hash.substring(0, 2);
            Path assetPath = objectsDirectory.resolve(prefix).resolve(hash).normalize();
            if (!assetPath.startsWith(objectsDirectory)) {
                throw new MinecraftInstallException(
                        "Некорректная metadata ресурсов Minecraft: небезопасный путь asset " + hash + ".");
            }
            String assetUrl = ASSETS_BASE_URL + prefix + "/" + hash;
            long size = asset.path("size").asLong(0);
            if (isLocalFileValid(assetPath, hash, size)) {
                validObjects++;
            } else {
                addDownloadItem(
                        missingAssetsByPath,
                        new DownloadItem(assetUrl, assetPath, hash, size, "asset " + hash),
                        "Minecraft asset index"
                );
            }

            checkedObjects++;
            if (checkedObjects % 500 == 0) {
                logConsumer.accept("Assets checked: " + checkedObjects + "/" + totalObjects
                        + " | valid: " + validObjects
                        + " | missing: " + missingAssetsByPath.size());
            }
        }
        List<DownloadItem> missingAssets = new ArrayList<>(missingAssetsByPath.values());
        metrics.assetsTotal += totalObjects;
        metrics.assetsValid += validObjects;
        metrics.assetsCheckMillis += elapsedMillis(checkStartedAt);
        logConsumer.accept("Assets checked: " + checkedObjects + "/" + totalObjects
                + " | valid: " + validObjects
                + " | missing: " + missingAssets.size());

        long downloadStartedAt = System.nanoTime();
        ParallelDownloadResult result = downloadItemsInParallel(
                missingAssets,
                MINECRAFT_ASSET_DOWNLOAD_THREADS,
                "Minecraft assets",
                "Загрузка ресурсов Minecraft",
                logConsumer,
                progress,
                0.55,
                0.75,
                validObjects
        );
        metrics.assetsDownloaded += result.downloaded();
        metrics.assetsFailed += result.failed();
        metrics.assetsDownloadMillis += elapsedMillis(downloadStartedAt);
        progress.report(0.75, "Ресурсы Minecraft готовы");
    }

    private void ensureLibraries(
            JsonNode versionJson,
            Path gameDirectory,
            OperatingSystem operatingSystem,
            Consumer<String> logConsumer,
            InstallProgressReporter progress,
            InstallMetrics metrics,
            String stageName,
            double progressStart,
            double progressEnd
    ) {
        String statusPrefix = stageName.startsWith("Forge")
                ? "Загрузка библиотек Forge"
                : "Загрузка библиотек Minecraft";
        progress.report(progressStart, stageName.startsWith("Forge")
                ? "Проверка библиотек Forge..."
                : "Проверка библиотек Minecraft...");
        long checkStartedAt = System.nanoTime();
        Map<Path, DownloadItem> missingLibrariesByPath = new LinkedHashMap<>();
        int totalLibraries = 0;
        int validLibraries = 0;

        for (JsonNode library : versionJson.path("libraries")) {
            if (!isAllowedByRules(library.path("rules"), operatingSystem)) {
                continue;
            }

            Artifact artifact = getArtifact(library);
            if (artifact != null) {
                totalLibraries++;
                if (collectLibraryArtifact(
                        gameDirectory,
                        artifact,
                        "library " + library.path("name").asText(""),
                        missingLibrariesByPath)) {
                    validLibraries++;
                }
            }

            Artifact nativeArtifact = getNativeArtifact(library, operatingSystem);
            if (nativeArtifact != null) {
                totalLibraries++;
                if (collectLibraryArtifact(
                        gameDirectory,
                        nativeArtifact,
                        "native library " + library.path("name").asText(""),
                        missingLibrariesByPath)) {
                    validLibraries++;
                }
            }
        }

        List<DownloadItem> missingLibraries = new ArrayList<>(missingLibrariesByPath.values());
        metrics.librariesTotal += totalLibraries;
        metrics.librariesValid += validLibraries;
        metrics.librariesCheckMillis += elapsedMillis(checkStartedAt);
        logConsumer.accept(stageName + " checked: " + totalLibraries
                + " total, " + validLibraries
                + " valid, " + missingLibraries.size()
                + " missing/outdated.");

        long downloadStartedAt = System.nanoTime();
        ParallelDownloadResult result = downloadItemsInParallel(
                missingLibraries,
                MINECRAFT_LIBRARY_DOWNLOAD_THREADS,
                stageName,
                statusPrefix,
                logConsumer,
                progress,
                progressStart,
                progressEnd,
                validLibraries
        );
        metrics.librariesDownloaded += result.downloaded();
        metrics.librariesFailed += result.failed();
        metrics.librariesDownloadMillis += elapsedMillis(downloadStartedAt);
    }

    private boolean collectLibraryArtifact(
            Path gameDirectory,
            Artifact artifact,
            String label,
            Map<Path, DownloadItem> missingLibrariesByPath
    ) {
        if (artifact.url() == null || artifact.url().isBlank()) {
            throw new MinecraftInstallException("Некорректная metadata библиотеки " + label + ": пустой URL.");
        }

        Path targetPath = resolveLibraryArtifactPath(gameDirectory, artifact, label);
        if (isLocalFileValid(targetPath, artifact.sha1(), artifact.size())) {
            return true;
        }

        addDownloadItem(
                missingLibrariesByPath,
                new DownloadItem(artifact.url(), targetPath, artifact.sha1(), artifact.size(), label),
                "Minecraft library metadata"
        );
        return false;
    }

    private String validateAssetHash(String rawHash) {
        String hash = normalizeHash(rawHash);
        if (!SHA1_HEX_PATTERN.matcher(hash).matches()) {
            throw new MinecraftInstallException(
                    "Некорректная metadata ресурсов Minecraft: asset hash должен быть SHA-1 из 40 hex символов, получено: "
                            + abbreviateMetadataValue(rawHash));
        }
        return hash;
    }

    private Path resolveLibraryArtifactPath(Path gameDirectory, Artifact artifact, String label) {
        String artifactPath = artifact.path();
        if (artifactPath == null || artifactPath.isBlank()) {
            throw new MinecraftInstallException("Некорректная metadata библиотеки " + label + ": пустой path.");
        }
        if (isUnsafeRelativePath(artifactPath)) {
            throw new MinecraftInstallException("Некорректная metadata библиотеки " + label
                    + ": небезопасный path " + abbreviateMetadataValue(artifactPath) + ".");
        }

        Path librariesDirectory = gameDirectory.resolve("libraries").normalize();
        Path targetPath;
        try {
            targetPath = librariesDirectory.resolve(artifactPath).normalize();
        } catch (InvalidPathException exception) {
            throw new MinecraftInstallException("Некорректная metadata библиотеки " + label
                    + ": недопустимый path " + abbreviateMetadataValue(artifactPath) + ".", exception);
        }

        if (!targetPath.startsWith(librariesDirectory)) {
            throw new MinecraftInstallException("Некорректная metadata библиотеки " + label
                    + ": path выходит за пределы libraries.");
        }
        return targetPath;
    }

    private boolean isUnsafeRelativePath(String path) {
        try {
            if (Path.of(path).isAbsolute()) {
                return true;
            }
        } catch (InvalidPathException exception) {
            return true;
        }

        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.startsWith("~") || normalized.matches("(?i)^[a-z]:.*")) {
            return true;
        }

        for (String segment : normalized.split("/+")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private void addDownloadItem(
            Map<Path, DownloadItem> downloadsByPath,
            DownloadItem item,
            String metadataLabel
    ) {
        Path normalizedPath = item.targetPath().normalize();
        DownloadItem existing = downloadsByPath.get(normalizedPath);
        if (existing == null) {
            downloadsByPath.put(normalizedPath, item.withTargetPath(normalizedPath));
            return;
        }

        if (!sameDownloadMetadata(existing, item)) {
            throw new MinecraftInstallException("Некорректная metadata " + metadataLabel
                    + ": конфликтующие entries для одного файла " + normalizedPath.getFileName() + ".");
        }
    }

    private boolean sameDownloadMetadata(DownloadItem first, DownloadItem second) {
        return Objects.equals(first.url(), second.url())
                && normalizeHash(first.expectedSha1()).equals(normalizeHash(second.expectedSha1()))
                && first.expectedSize() == second.expectedSize();
    }

    private String abbreviateMetadataValue(String value) {
        if (value == null) {
            return "<null>";
        }
        String sanitized = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (sanitized.length() <= 80) {
            return sanitized;
        }
        return sanitized.substring(0, 77) + "...";
    }

    private void ensureForgeProfile(
            MinecraftInstallOptions options,
            Consumer<String> logConsumer,
            InstallProgressReporter progress,
            InstallMetrics metrics
    ) {
        Path forgeVersionJson = options.getGameDirectory()
                .resolve("versions")
                .resolve(options.getForgeVersionName())
                .resolve(options.getForgeVersionName() + ".json");

        if (Files.isRegularFile(forgeVersionJson)) {
            JsonNode existingForgeVersion = readJson(forgeVersionJson, "Forge version JSON");
            if (areForgeGeneratedArtifactsPresent(options, existingForgeVersion)) {
                logConsumer.accept("Forge уже установлен, установка не требуется.");
                return;
            }

            logConsumer.accept("Профиль Forge найден, но сгенерированные библиотеки отсутствуют. Запускаю installer заново.");
        } else {
            logConsumer.accept("Forge не найден, запускаю installer.");
        }

        Path installerPath = options.getGameDirectory()
                .resolve("installers")
                .resolve("forge-" + options.getForgeVersionName() + "-installer.jar");
        String installerUrl = FORGE_MAVEN_BASE_URL
                + "/"
                + options.getMinecraftVersion()
                + "-"
                + options.getForgeVersion()
                + "/forge-"
                + options.getMinecraftVersion()
                + "-"
                + options.getForgeVersion()
                + "-installer.jar";

        progress.report(0.82, "Загрузка Forge installer...");
        long installerDownloadStartedAt = System.nanoTime();
        String installerSha1 = downloadOptionalSha1(installerUrl + ".sha1", "Forge installer", logConsumer);
        validateForgeInstallerCache(installerPath, installerSha1, logConsumer);
        downloadFile(installerUrl, installerPath, installerSha1, 0, "Forge installer", logConsumer);
        metrics.forgeInstallerDownloadMillis += elapsedMillis(installerDownloadStartedAt);
        ensureLauncherProfilesJson(options, logConsumer);
        progress.report(0.86, "Установка Forge. Это самый долгий этап первого запуска и может занять 5–10 минут...");
        long forgeInstallerStartedAt = System.nanoTime();
        runForgeInstaller(options, installerPath, logConsumer);
        metrics.forgeInstallerRunMillis += elapsedMillis(forgeInstallerStartedAt);

        if (!Files.isRegularFile(forgeVersionJson)) {
            logConsumer.accept("Forge installer завершился, но профиль Forge не найден.");
            throw new MinecraftInstallException("Forge installer finished, but profile JSON was not created: " + forgeVersionJson);
        }

        JsonNode installedForgeVersion = readJson(forgeVersionJson, "Forge version JSON");
        if (!areForgeGeneratedArtifactsPresent(options, installedForgeVersion)) {
            logConsumer.accept("Forge installer завершился, но сгенерированные библиотеки Forge не найдены.");
            throw new MinecraftInstallException("Forge installer finished, but generated Forge libraries are still missing.");
        }

        logConsumer.accept("Forge установлен и будет использоваться при следующих запусках.");
    }

    private void validateForgeInstallerCache(Path installerPath, String expectedSha1, Consumer<String> logConsumer) {
        if (!Files.isRegularFile(installerPath)) {
            return;
        }

        if (!normalizeHash(expectedSha1).isBlank()) {
            if (isLocalFileValid(installerPath, expectedSha1, 0)) {
                return;
            }
            logConsumer.accept("Cached Forge installer не прошел SHA-1 validation. Файл будет скачан заново.");
            deleteInvalidForgeInstallerCache(installerPath);
            return;
        }

        long cachedSize;
        try {
            cachedSize = Files.size(installerPath);
        } catch (IOException exception) {
            throw new MinecraftInstallException("Не удалось проверить cached Forge installer: " + installerPath, exception);
        }

        if (cachedSize < MIN_FORGE_INSTALLER_SIZE_BYTES) {
            logConsumer.accept("Cached Forge installer выглядит поврежденным: size="
                    + cachedSize
                    + " bytes. Файл будет скачан заново.");
            deleteInvalidForgeInstallerCache(installerPath);
            return;
        }

        logConsumer.accept("SHA-1 для Forge installer недоступен; cached installer прошел size sanity check: "
                + cachedSize
                + " bytes.");
    }

    private void deleteInvalidForgeInstallerCache(Path installerPath) {
        try {
            Files.deleteIfExists(installerPath);
        } catch (IOException exception) {
            throw new MinecraftInstallException("Не удалось удалить поврежденный cached Forge installer: "
                    + installerPath
                    + ". Проверьте, что файл не занят антивирусом или другим процессом.",
                    exception);
        }
    }

    private boolean areForgeGeneratedArtifactsPresent(MinecraftInstallOptions options, JsonNode forgeVersionJson) {
        Path librariesDirectory = options.getGameDirectory().resolve("libraries");
        String forgeMavenVersion = options.getMinecraftVersion() + "-" + options.getForgeVersion();
        Path forgeClientJar = librariesDirectory
                .resolve("net")
                .resolve("minecraftforge")
                .resolve("forge")
                .resolve(forgeMavenVersion)
                .resolve("forge-" + forgeMavenVersion + "-client.jar");

        return Files.isRegularFile(forgeClientJar)
                && hasGeneratedMinecraftClientArtifact(librariesDirectory, options.getMinecraftVersion(), "-srg.jar")
                && hasGeneratedMinecraftClientArtifact(librariesDirectory, options.getMinecraftVersion(), "-extra.jar");
    }

    private boolean hasGeneratedMinecraftClientArtifact(
            Path librariesDirectory,
            String minecraftVersion,
            String fileSuffix
    ) {
        Path minecraftClientDirectory = librariesDirectory.resolve("net").resolve("minecraft").resolve("client");
        if (!Files.isDirectory(minecraftClientDirectory)) {
            return false;
        }

        try (Stream<Path> paths = Files.walk(minecraftClientDirectory, 2)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .anyMatch(fileName -> fileName.startsWith("client-" + minecraftVersion + "-")
                            && fileName.endsWith(fileSuffix));
        } catch (IOException exception) {
            return false;
        }
    }

    private void ensureLauncherProfilesJson(MinecraftInstallOptions options, Consumer<String> logConsumer) {
        Path launcherProfilesPath = options.getGameDirectory().resolve("launcher_profiles.json");

        if (isUsableLauncherProfilesJson(launcherProfilesPath)) {
            return;
        }

        String minecraftVersion = options.getMinecraftVersion();
        String createdAt = "1970-01-01T00:00:00.000Z";
        String launcherProfilesJson = """
                {
                  "profiles": {
                    "%s": {
                      "name": "%s",
                      "type": "custom",
                      "created": "%s",
                      "lastUsed": "%s",
                      "lastVersionId": "%s"
                    }
                  },
                  "settings": {},
                  "version": 3,
                  "clientToken": "00000000-0000-0000-0000-000000000000",
                  "authenticationDatabase": {},
                  "launcherVersion": {
                    "name": "DeluxeWarfareLauncher",
                    "format": 21
                  }
                }
                """.formatted(minecraftVersion, minecraftVersion, createdAt, createdAt, minecraftVersion);

        try {
            Files.createDirectories(options.getGameDirectory());
            Files.writeString(launcherProfilesPath, launcherProfilesJson, StandardCharsets.UTF_8);
            logConsumer.accept("Created launcher_profiles.json for Forge installer.");
        } catch (IOException exception) {
            throw new MinecraftInstallException("Unable to create launcher_profiles.json: " + launcherProfilesPath, exception);
        }
    }

    private boolean isUsableLauncherProfilesJson(Path launcherProfilesPath) {
        if (!Files.isRegularFile(launcherProfilesPath)) {
            return false;
        }

        try (InputStream inputStream = Files.newInputStream(launcherProfilesPath)) {
            JsonNode root = objectMapper.readTree(inputStream);
            return root.has("profiles") && root.path("profiles").isObject();
        } catch (IOException exception) {
            return false;
        }
    }

    private void runForgeInstaller(
            MinecraftInstallOptions options,
            Path installerPath,
            Consumer<String> logConsumer
    ) {
        List<String> command = List.of(
                options.getJavaExecutable(),
                "-jar",
                installerPath.toString(),
                "--installClient",
                options.getGameDirectory().toString()
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(options.getGameDirectory().toFile());
        processBuilder.redirectErrorStream(true);

        try {
            logConsumer.accept("Forge installer запущен. Первый запуск может занять 5–10 минут.");
            Process process = processBuilder.start();
            long installerStartedAt = System.nanoTime();
            Deque<String> lastOutputLines = new ArrayDeque<>();
            AtomicInteger noisyLines = new AtomicInteger();
            ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "forge-installer-output-reader");
                thread.setDaemon(true);
                return thread;
            });
            ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "forge-installer-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            Future<?> outputReader = outputReaderExecutor.submit(() ->
                    readForgeInstallerOutput(process, lastOutputLines, noisyLines, logConsumer));
            heartbeatExecutor.scheduleAtFixedRate(() -> {
                if (process.isAlive()) {
                    logConsumer.accept("Forge installer всё ещё работает: прошло "
                            + formatForgeInstallerDuration(elapsedMillis(installerStartedAt))
                            + "...");
                }
            }, 60, 60, TimeUnit.SECONDS);

            try {
                boolean completed = process.waitFor(10, TimeUnit.MINUTES);
                if (!completed) {
                    stopForgeInstaller(process);
                    logConsumer.accept("Forge installer не завершился за 10 минут и был остановлен.");
                    outputReader.cancel(true);
                    throw new MinecraftInstallException("Forge installer timed out after 10 minutes. Last output: "
                            + formatLastOutputLines(lastOutputLines));
                }

                waitForForgeOutputReader(outputReader, logConsumer);

                if (noisyLines.get() > 0) {
                    logConsumer.accept("[Forge installer] Suppressed verbose class listing lines: " + noisyLines.get());
                }

                int exitCode = process.exitValue();
                long installerElapsedMillis = elapsedMillis(installerStartedAt);
                if (exitCode != 0) {
                    logConsumer.accept("Forge installer завершился с ошибкой за "
                            + formatForgeInstallerDuration(installerElapsedMillis)
                            + ". Код: "
                            + exitCode
                            + ".");
                    throw new MinecraftInstallException("Forge installer exited with code "
                            + exitCode
                            + ". Last output: "
                            + formatLastOutputLines(lastOutputLines));
                }

                logConsumer.accept("Forge installer завершён за "
                        + formatForgeInstallerDuration(installerElapsedMillis)
                        + ".");
            } finally {
                heartbeatExecutor.shutdownNow();
                outputReaderExecutor.shutdownNow();
            }
        } catch (IOException exception) {
            throw new MinecraftInstallException("Unable to run Forge installer.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MinecraftInstallException("Forge installer was interrupted.", exception);
        }
    }

    private void readForgeInstallerOutput(
            Process process,
            Deque<String> lastOutputLines,
            AtomicInteger noisyLines,
            Consumer<String> logConsumer
    ) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                addLastOutputLine(lastOutputLines, line);
                if (isNoisyForgeInstallerLine(line)) {
                    noisyLines.incrementAndGet();
                    continue;
                }

                if (isImportantForgeInstallerLine(line)) {
                    logConsumer.accept("[Forge installer] " + line);
                }
            }
        } catch (IOException exception) {
            if (process.isAlive()) {
                logConsumer.accept("[Forge installer] Output reader failed: " + rootCauseMessage(exception));
            }
        }
    }

    private void waitForForgeOutputReader(Future<?> outputReader, Consumer<String> logConsumer) {
        try {
            outputReader.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            outputReader.cancel(true);
            logConsumer.accept("[Forge installer] Output reader did not finish in time.");
        } catch (ExecutionException exception) {
            logConsumer.accept("[Forge installer] Output reader failed: " + rootCauseMessage(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MinecraftInstallException("Forge installer output reader was interrupted.", exception);
        }
    }

    private void stopForgeInstaller(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private String formatForgeInstallerDuration(long millis) {
        long seconds = Math.max(0, millis / 1_000);
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return minutes + " мин " + remainingSeconds + " сек";
    }

    private void addLastOutputLine(Deque<String> lines, String line) {
        synchronized (lines) {
            if (lines.size() >= FORGE_INSTALLER_LAST_OUTPUT_LINES) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }
    }

    private String formatLastOutputLines(Deque<String> lines) {
        synchronized (lines) {
            return String.join(" | ", lines);
        }
    }

    private boolean isNoisyForgeInstallerLine(String line) {
        String trimmed = line.trim();
        return trimmed.endsWith(".class")
                || trimmed.endsWith("/")
                || trimmed.startsWith("net/")
                || trimmed.startsWith("com/")
                || trimmed.startsWith("org/")
                || trimmed.startsWith("META-INF/");
    }

    private boolean isImportantForgeInstallerLine(String line) {
        String lowerLine = line.toLowerCase(Locale.ROOT);
        return lowerLine.contains("error")
                || lowerLine.contains("fail")
                || lowerLine.contains("exception")
                || lowerLine.contains("install")
                || lowerLine.contains("complete")
                || lowerLine.contains("success");
    }

    private JsonNode readVersionJson(Path gameDirectory, String versionName) {
        Path jsonPath = gameDirectory.resolve("versions").resolve(versionName).resolve(versionName + ".json");
        if (!Files.isRegularFile(jsonPath)) {
            throw new MinecraftInstallException("Version JSON was not found: " + jsonPath);
        }
        return readJson(jsonPath, "version JSON " + versionName);
    }

    private void extractNatives(
            JsonNode versionJson,
            Path gameDirectory,
            Path nativesDirectory,
            OperatingSystem operatingSystem,
            Consumer<String> logConsumer
    ) {
        int extractedLibraries = 0;
        for (JsonNode library : versionJson.path("libraries")) {
            if (!isAllowedByRules(library.path("rules"), operatingSystem)) {
                continue;
            }

            Artifact nativeArtifact = getNativeArtifact(library, operatingSystem);
            if (nativeArtifact == null) {
                continue;
            }

            Path nativeJarPath = gameDirectory.resolve("libraries").resolve(nativeArtifact.path());
            if (!Files.isRegularFile(nativeJarPath)) {
                continue;
            }

            extractNativeJar(nativeJarPath, nativesDirectory);
            extractedLibraries++;
        }

        if (extractedLibraries > 0) {
            logConsumer.accept("Extracted native libraries: " + extractedLibraries);
        }
    }

    private void extractNativeJar(Path nativeJarPath, Path nativesDirectory) {
        try (InputStream inputStream = Files.newInputStream(nativeJarPath);
                ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory() || shouldSkipNativeEntry(entry.getName())) {
                    continue;
                }

                Path targetPath = nativesDirectory.resolve(entry.getName()).normalize();
                if (!targetPath.startsWith(nativesDirectory.normalize())) {
                    throw new MinecraftInstallException("Native library contains unsafe path: " + entry.getName());
                }

                Files.createDirectories(targetPath.getParent());
                Files.copy(zipInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new MinecraftInstallException("Unable to extract native library: " + nativeJarPath, exception);
        }
    }

    private boolean shouldSkipNativeEntry(String name) {
        String normalized = name.replace('\\', '/');
        return normalized.startsWith("META-INF/")
                || normalized.endsWith(".sha1")
                || normalized.endsWith(".git")
                || normalized.endsWith(".DS_Store");
    }

    private boolean downloadFileIfMissingOrInvalid(
            String url,
            Path targetPath,
            String expectedSha1,
            long expectedSize,
            String label,
            Consumer<String> logConsumer,
            boolean logWhenDownloading
    ) {
        if (isLocalFileValid(targetPath, expectedSha1, expectedSize)) {
            return false;
        }

        if (logWhenDownloading) {
            logConsumer.accept("Downloading " + label + "...");
        }

        Path temporaryPath = targetPath.resolveSibling(targetPath.getFileName() + ".download");
        try {
            Files.createDirectories(targetPath.getParent());
        } catch (IOException exception) {
            throw new MinecraftInstallException("Не удалось создать директорию для загрузки: "
                    + targetPath
                    + ". Проверьте права доступа, антивирус и свободное место на диске.",
                    exception);
        }

        URI sourceUri = createDownloadUri(url, label);
        String host = getSafeHost(sourceUri);
        MinecraftInstallException lastException = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            deleteStaleTemporaryDownload(temporaryPath, label, logConsumer);
            long startedAt = System.nanoTime();
            int httpStatus = -1;

            try {
                HttpRequest request = HttpRequest.newBuilder(sourceUri)
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                httpStatus = response.statusCode();
                if (response.statusCode() != 200) {
                    closeQuietly(response.body());
                    throw new DownloadAttemptException(
                            "Download failed for " + label + ". HTTP " + response.statusCode() + ".",
                            isRetryableHttpStatus(response.statusCode(), response),
                            response.statusCode(),
                            getRetryAfterDelayMillis(response.statusCode(), response)
                    );
                }

                try (InputStream inputStream = response.body()) {
                    OutputStream outputStream;
                    try {
                        outputStream = Files.newOutputStream(temporaryPath);
                    } catch (IOException exception) {
                        throw localFileDownloadException(
                                "Не удалось открыть временный файл загрузки " + temporaryPath.getFileName()
                                        + ". Проверьте права доступа, антивирус и свободное место на диске.",
                                exception
                        );
                    }

                    byte[] buffer = new byte[BUFFER_SIZE];
                    try (OutputStream closeableOutputStream = outputStream) {
                        while (true) {
                            int bytesRead;
                            try {
                                bytesRead = inputStream.read(buffer);
                            } catch (IOException exception) {
                                deleteQuietly(temporaryPath);
                                throw new DownloadAttemptException(
                                        "Network error while reading " + label + ".",
                                        exception,
                                        true,
                                        -1
                                );
                            }

                            if (bytesRead == -1) {
                                break;
                            }

                            try {
                                closeableOutputStream.write(buffer, 0, bytesRead);
                            } catch (IOException exception) {
                                deleteQuietly(temporaryPath);
                                throw localFileDownloadException(
                                        "Не удалось записать временный файл загрузки " + temporaryPath.getFileName()
                                                + ". Файл может быть занят, заблокирован антивирусом, "
                                                + "нет прав доступа или закончилось место на диске.",
                                        exception
                                );
                            }
                        }
                    } catch (IOException exception) {
                        deleteQuietly(temporaryPath);
                        throw localFileDownloadException(
                                "Не удалось завершить запись временного файла загрузки "
                                        + temporaryPath.getFileName()
                                        + ". Проверьте права доступа, антивирус и свободное место на диске.",
                                exception
                        );
                    }
                }

                if (!isLocalFileValid(temporaryPath, expectedSha1, expectedSize)) {
                    deleteQuietly(temporaryPath);
                    throw new MinecraftInstallException("Downloaded file validation failed: " + label);
                }

                replaceDownloadedFile(temporaryPath, targetPath);
                logConsumer.accept("DOWNLOAD OK | " + label
                        + " | host=" + host
                        + " | status=" + httpStatus
                        + " | durationMs=" + elapsedMillis(startedAt));
                return true;
            } catch (ConnectException exception) {
                lastException = new DownloadAttemptException(
                        "Unable to connect while downloading " + label + ".",
                        exception,
                        true,
                        -1
                );
            } catch (HttpTimeoutException exception) {
                lastException = new DownloadAttemptException(
                        "Download timed out for " + label + ".",
                        exception,
                        true,
                        -1
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                deleteQuietly(temporaryPath);
                throw new MinecraftInstallException("Download was interrupted: " + label, exception);
            } catch (IOException exception) {
                lastException = new DownloadAttemptException(
                        "Network error while downloading " + label + ".",
                        exception,
                        true,
                        -1
                );
            } catch (DownloadAttemptException exception) {
                lastException = exception;
            } catch (MinecraftInstallException exception) {
                deleteQuietly(temporaryPath);
                throw exception;
            }

            deleteQuietly(temporaryPath);
            logConsumer.accept("DOWNLOAD FAILED | " + label
                    + " | attempt=" + attempt + "/" + MAX_DOWNLOAD_ATTEMPTS
                    + " | host=" + host
                    + " | status=" + (lastException instanceof DownloadAttemptException attemptException
                            ? formatStatus(attemptException.statusCode())
                            : "n/a")
                    + " | durationMs=" + elapsedMillis(startedAt)
                    + " | " + rootCauseMessage(lastException));

            if (!(lastException instanceof DownloadAttemptException attemptException) || !attemptException.retryable()) {
                throw lastException;
            }

            if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                long retryDelayMillis = attemptException.retryAfterDelayMillis();
                if (retryDelayMillis < 0) {
                    retryDelayMillis = computeRetryDelayMillis(attempt);
                }
                logConsumer.accept(
                        "Retrying download "
                                + (attempt + 1)
                                + "/"
                                + MAX_DOWNLOAD_ATTEMPTS
                                + " for "
                                + label
                                + " after "
                                + retryDelayMillis
                                + " ms | "
                                + rootCauseMessage(lastException)
                );
                sleepBeforeRetry(retryDelayMillis);
            }
        }

        throw new MinecraftInstallException(
                "Download failed after "
                        + MAX_DOWNLOAD_ATTEMPTS
                        + " attempts: "
                        + label
                        + " | "
                        + rootCauseMessage(lastException),
                lastException
        );
    }

    private DownloadAttemptException localFileDownloadException(String message, IOException cause) {
        return new DownloadAttemptException(message, cause, false, -1);
    }

    private void deleteStaleTemporaryDownload(Path temporaryPath, String label, Consumer<String> logConsumer) {
        try {
            if (Files.deleteIfExists(temporaryPath)) {
                logConsumer.accept("Removed stale temporary download for " + label + ": "
                        + temporaryPath.getFileName());
            }
        } catch (IOException exception) {
            throw new MinecraftInstallException(
                    "Не удалось удалить старый временный файл загрузки для "
                            + label
                            + ": "
                            + temporaryPath
                            + ". Файл может быть занят другим процессом или антивирусом.",
                    exception
            );
        }
    }

    private ParallelDownloadResult downloadItemsInParallel(
            List<DownloadItem> items,
            int threadCount,
            String stageName,
            String statusPrefix,
            Consumer<String> logConsumer,
            InstallProgressReporter progress,
            double progressStart,
            double progressEnd,
            int skippedCount
    ) {
        if (items.isEmpty()) {
            progress.report(progressEnd, statusPrefix + ": 0/0");
            return new ParallelDownloadResult(0, 0);
        }

        long batchStartedAt = System.nanoTime();
        int threads = Math.max(1, Math.min(threadCount, items.size()));
        ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "minecraft-install-download");
            thread.setDaemon(true);
            return thread;
        });
        CompletionService<Boolean> completionService = new ExecutorCompletionService<>(executor);

        for (DownloadItem item : items) {
            completionService.submit(downloadTask(item, logConsumer));
        }

        int completed = 0;
        int downloaded = 0;
        int failed = 0;

        try {
            while (completed < items.size()) {
                Future<Boolean> future = completionService.take();
                try {
                    if (Boolean.TRUE.equals(future.get())) {
                        downloaded++;
                    }
                    completed++;
                    double progressValue = progressStart
                            + (progressEnd - progressStart) * ((double) completed / (double) items.size());
                    progress.reportThrottled(
                            progressValue,
                            statusPrefix + ": " + completed + "/" + items.size()
                    );
                    if (completed == items.size() || completed % Math.max(1, items.size() / 10) == 0) {
                        logConsumer.accept(statusPrefix + ": " + completed + "/" + items.size()
                                + " | downloaded: " + downloaded);
                    }
                } catch (ExecutionException exception) {
                    failed++;
                    String partialCounters = formatParallelBatchCounters(
                            skippedCount,
                            items.size(),
                            completed,
                            downloaded,
                            failed,
                            elapsedMillis(batchStartedAt)
                    );
                    logConsumer.accept(stageName + " failed. " + partialCounters);
                    executor.shutdownNow();
                    throw new MinecraftInstallException(stageName
                            + " failed: "
                            + rootCauseMessage(exception)
                            + ". "
                            + partialCounters
                            + ". Проверьте сеть, доступность сервера и антивирус, затем повторите запуск.",
                            exception);
                }
            }
        } catch (InterruptedException exception) {
            logConsumer.accept(stageName + " interrupted. " + formatParallelBatchCounters(
                    skippedCount,
                    items.size(),
                    completed,
                    downloaded,
                    failed,
                    elapsedMillis(batchStartedAt)
            ));
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new MinecraftInstallException(stageName + " download was interrupted.", exception);
        } finally {
            executor.shutdownNow();
        }

        progress.report(progressEnd, statusPrefix + ": " + completed + "/" + items.size());
        return new ParallelDownloadResult(downloaded, failed);
    }

    private String formatParallelBatchCounters(
            int skippedCount,
            int downloadTotal,
            int completed,
            int downloaded,
            int failed,
            long elapsedMillis
    ) {
        return "partial counters: total="
                + (skippedCount + downloadTotal)
                + ", validSkipped="
                + skippedCount
                + ", downloadTotal="
                + downloadTotal
                + ", completed="
                + completed
                + ", downloaded="
                + downloaded
                + ", failed="
                + failed
                + ", elapsedMs="
                + elapsedMillis;
    }

    private Callable<Boolean> downloadTask(DownloadItem item, Consumer<String> logConsumer) {
        return () -> downloadFileIfMissingOrInvalid(
                item.url(),
                item.targetPath(),
                item.expectedSha1(),
                item.expectedSize(),
                item.label(),
                logConsumer,
                false
        );
    }

    private void replaceDownloadedFile(Path temporaryPath, Path targetPath) {
        try {
            try {
                Files.move(
                        temporaryPath,
                        targetPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                try {
                    Files.move(temporaryPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException fallbackException) {
                    fallbackException.addSuppressed(exception);
                    throw fallbackException;
                }
            }
        } catch (IOException exception) {
            deleteQuietly(temporaryPath);
            throw new MinecraftInstallException("Unable to replace downloaded file: " + targetPath, exception);
        }
    }

    private URI createDownloadUri(String url, String label) {
        if (url == null || url.isBlank()) {
            throw new MinecraftInstallException("Download URL is missing for " + label + ".");
        }

        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (uri.getHost() == null
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                throw new MinecraftInstallException("Invalid download URL for " + label + ".");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new MinecraftInstallException("Invalid download URL for " + label + ".", exception);
        }
    }

    private boolean isRetryableHttpStatus(int statusCode, HttpResponse<?> response) {
        return statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504
                || (statusCode == 403 && isRateLimited403(response));
    }

    private boolean isRateLimited403(HttpResponse<?> response) {
        String remaining = response.headers()
                .firstValue("x-ratelimit-remaining")
                .orElse("")
                .trim();
        return response.headers().firstValue("retry-after").isPresent() || "0".equals(remaining);
    }

    private long getRetryAfterDelayMillis(int statusCode, HttpResponse<?> response) {
        if (statusCode != 429 && !(statusCode == 403 && isRateLimited403(response))) {
            return -1;
        }

        return response.headers()
                .firstValue("retry-after")
                .map(this::parseRetryAfterMillis)
                .orElse(-1L);
    }

    private long parseRetryAfterMillis(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            return -1;
        }

        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds < 0) {
                return -1;
            }
            if (seconds >= MAX_RETRY_AFTER_DELAY_MILLIS / 1_000L) {
                return MAX_RETRY_AFTER_DELAY_MILLIS;
            }
            return Math.min(MAX_RETRY_AFTER_DELAY_MILLIS, seconds * 1_000L);
        } catch (NumberFormatException ignored) {
        }

        try {
            long delayMillis = Duration.between(
                    ZonedDateTime.now(),
                    ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
            ).toMillis();
            return Math.min(MAX_RETRY_AFTER_DELAY_MILLIS, Math.max(0L, delayMillis));
        } catch (DateTimeParseException ignored) {
            return -1;
        }
    }

    private long computeRetryDelayMillis(int completedAttempt) {
        long exponentialDelay = BASE_RETRY_DELAY_MILLIS << Math.max(0, completedAttempt - 1);
        long cappedDelay = Math.min(MAX_RETRY_DELAY_MILLIS, exponentialDelay);
        long jitter = ThreadLocalRandom.current().nextLong(100, 351);
        return cappedDelay + jitter;
    }

    private String getSafeHost(URI uri) {
        String host = uri.getHost();
        return host == null || host.isBlank() ? "unknown" : host;
    }

    private String formatStatus(int statusCode) {
        return statusCode > 0 ? Integer.toString(statusCode) : "n/a";
    }

    private void downloadFile(
            String url,
            Path targetPath,
            String expectedSha1,
            long expectedSize,
            String label,
            Consumer<String> logConsumer
    ) {
        downloadFileIfMissingOrInvalid(url, targetPath, expectedSha1, expectedSize, label, logConsumer, true);
    }

    private JsonNode downloadJson(String url, String label, Consumer<String> logConsumer) {
        URI sourceUri = createDownloadUri(url, label);
        String host = getSafeHost(sourceUri);
        MinecraftInstallException lastException = null;

        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            long startedAt = System.nanoTime();
            int httpStatus = -1;
            HttpResponse<String> response;

            try {
                HttpRequest request = HttpRequest.newBuilder(sourceUri)
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build();

                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                httpStatus = response.statusCode();
                if (response.statusCode() != 200) {
                    throw new DownloadAttemptException(
                            "Download failed for " + label + ". HTTP " + response.statusCode() + ".",
                            isRetryableHttpStatus(response.statusCode(), response),
                            response.statusCode(),
                            getRetryAfterDelayMillis(response.statusCode(), response)
                    );
                }
            } catch (ConnectException exception) {
                lastException = new DownloadAttemptException(
                        "Unable to connect while downloading " + label + ".",
                        exception,
                        true,
                        -1
                );
                logMetadataDownloadFailure(label, host, attempt, httpStatus, startedAt, lastException, logConsumer);
                retryMetadataDownloadIfNeeded(label, attempt, lastException, logConsumer);
                continue;
            } catch (HttpTimeoutException exception) {
                lastException = new DownloadAttemptException(
                        "Download timed out for " + label + ".",
                        exception,
                        true,
                        -1
                );
                logMetadataDownloadFailure(label, host, attempt, httpStatus, startedAt, lastException, logConsumer);
                retryMetadataDownloadIfNeeded(label, attempt, lastException, logConsumer);
                continue;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new MinecraftInstallException("Download was interrupted: " + label, exception);
            } catch (IOException exception) {
                lastException = new DownloadAttemptException(
                        "Network error while downloading " + label + ".",
                        exception,
                        true,
                        -1
                );
                logMetadataDownloadFailure(label, host, attempt, httpStatus, startedAt, lastException, logConsumer);
                retryMetadataDownloadIfNeeded(label, attempt, lastException, logConsumer);
                continue;
            } catch (DownloadAttemptException exception) {
                lastException = exception;
                logMetadataDownloadFailure(label, host, attempt, httpStatus, startedAt, lastException, logConsumer);
                retryMetadataDownloadIfNeeded(label, attempt, lastException, logConsumer);
                continue;
            }

            try {
                JsonNode json = objectMapper.readTree(response.body());
                logConsumer.accept("METADATA DOWNLOAD OK | " + label
                        + " | host=" + host
                        + " | status=" + httpStatus
                        + " | durationMs=" + elapsedMillis(startedAt));
                return json;
            } catch (IOException exception) {
                throw new MinecraftInstallException(label + " JSON is invalid.", exception);
            }
        }

        throw new MinecraftInstallException(
                "Download failed after "
                        + MAX_DOWNLOAD_ATTEMPTS
                        + " attempts: "
                        + label
                        + " | "
                        + rootCauseMessage(lastException),
                lastException
        );
    }

    private void logMetadataDownloadFailure(
            String label,
            String host,
            int attempt,
            int httpStatus,
            long startedAt,
            MinecraftInstallException exception,
            Consumer<String> logConsumer
    ) {
        if (logConsumer == null) {
            return;
        }
        logConsumer.accept("METADATA DOWNLOAD FAILED | " + label
                + " | attempt=" + attempt + "/" + MAX_DOWNLOAD_ATTEMPTS
                + " | host=" + host
                + " | status=" + formatStatus(httpStatus)
                + " | durationMs=" + elapsedMillis(startedAt)
                + " | " + rootCauseMessage(exception));
    }

    private void retryMetadataDownloadIfNeeded(
            String label,
            int attempt,
            MinecraftInstallException exception,
            Consumer<String> logConsumer
    ) {
        if (!(exception instanceof DownloadAttemptException attemptException) || !attemptException.retryable()) {
            throw exception;
        }

        if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
            long retryDelayMillis = attemptException.retryAfterDelayMillis();
            if (retryDelayMillis < 0) {
                retryDelayMillis = computeRetryDelayMillis(attempt);
            }
            logConsumer.accept("Повтор загрузки metadata "
                    + (attempt + 1)
                    + "/"
                    + MAX_DOWNLOAD_ATTEMPTS
                    + " для "
                    + label
                    + " через "
                    + retryDelayMillis
                    + " мс | "
                    + rootCauseMessage(exception));
            sleepBeforeRetry(retryDelayMillis);
        }
    }

    private String downloadOptionalSha1(String url, String label, Consumer<String> logConsumer) {
        HttpRequest request = HttpRequest.newBuilder(createDownloadUri(url, label + " SHA-1"))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logConsumer.accept("Не удалось получить SHA-1 для " + label
                        + ": HTTP " + response.statusCode()
                        + ". Cached installer будет проверен по sanity check размера.");
                return "";
            }

            String firstToken = response.body().trim().split("\\s+")[0];
            if (firstToken.matches("(?i)[0-9a-f]{40}")) {
                return firstToken;
            }

            logConsumer.accept("SHA-1 для " + label + " имеет неожиданный формат. "
                    + "Cached installer будет проверен по sanity check размера.");
            return "";
        } catch (IOException exception) {
            logConsumer.accept("Не удалось получить SHA-1 для " + label
                    + ": " + rootCauseMessage(exception)
                    + ". Cached installer будет проверен по sanity check размера.");
            return "";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MinecraftInstallException(label + " SHA-1 download was interrupted.", exception);
        } catch (RuntimeException exception) {
            logConsumer.accept("Не удалось получить SHA-1 для " + label
                    + ": " + rootCauseMessage(exception)
                    + ". Cached installer будет проверен по sanity check размера.");
            return "";
        }
    }

    private JsonNode readJson(Path path, String label) {
        try (InputStream inputStream = Files.newInputStream(path)) {
            return objectMapper.readTree(inputStream);
        } catch (IOException exception) {
            throw new MinecraftInstallException("Unable to read " + label + ": " + path, exception);
        }
    }

    private boolean isLocalFileValid(Path path, String expectedSha1, long expectedSize) {
        if (!Files.isRegularFile(path)) {
            return false;
        }

        try {
            if (expectedSize > 0 && Files.size(path) != expectedSize) {
                return false;
            }
        } catch (IOException exception) {
            return false;
        }

        String normalizedSha1 = normalizeHash(expectedSha1);
        return normalizedSha1.isBlank() || normalizedSha1.equals(calculateSha1(path));
    }

    private String calculateSha1(Path path) {
        MessageDigest digest = createSha1Digest();
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream inputStream = Files.newInputStream(path);
                DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
            while (digestInputStream.read(buffer) != -1) {
                // DigestInputStream updates the digest while reading.
            }
        } catch (IOException exception) {
            throw new MinecraftInstallException("Unable to read file for SHA-1: " + path, exception);
        }

        return toHex(digest.digest());
    }

    private MessageDigest createSha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new MinecraftInstallException("SHA-1 is not supported by this Java runtime.", exception);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private String normalizeHash(String hash) {
        return hash == null ? "" : hash.trim().toLowerCase(Locale.ROOT);
    }

    private Artifact getArtifact(JsonNode library) {
        JsonNode artifact = library.path("downloads").path("artifact");
        if (!artifact.isMissingNode() && !artifact.path("url").asText("").isBlank()) {
            return new Artifact(
                    artifact.path("path").asText(""),
                    artifact.path("url").asText(""),
                    artifact.path("sha1").asText(""),
                    artifact.path("size").asLong(0)
            );
        }

        return createArtifactFromMavenName(library, "");
    }

    private Artifact getNativeArtifact(JsonNode library, OperatingSystem operatingSystem) {
        JsonNode natives = library.path("natives");
        if (natives.isMissingNode()) {
            return null;
        }

        String classifier = natives.path(operatingSystem.getLauncherName()).asText("");
        if (classifier.isBlank()) {
            return null;
        }

        classifier = classifier.replace("${arch}", OperatingSystem.currentArchitectureBits());

        JsonNode nativeDownload = library.path("downloads").path("classifiers").path(classifier);
        if (!nativeDownload.isMissingNode() && !nativeDownload.path("url").asText("").isBlank()) {
            return new Artifact(
                    nativeDownload.path("path").asText(""),
                    nativeDownload.path("url").asText(""),
                    nativeDownload.path("sha1").asText(""),
                    nativeDownload.path("size").asLong(0)
            );
        }

        return createArtifactFromMavenName(library, classifier);
    }

    private Artifact createArtifactFromMavenName(JsonNode library, String classifierOverride) {
        String name = library.path("name").asText("");
        if (name.isBlank()) {
            return null;
        }

        MavenArtifact mavenArtifact = MavenArtifact.parse(name, classifierOverride);
        String baseUrl = library.path("url").asText(MOJANG_LIBRARIES_BASE_URL);
        if (baseUrl.isBlank()) {
            baseUrl = MOJANG_LIBRARIES_BASE_URL;
        }
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }

        return new Artifact(mavenArtifact.path(), baseUrl + mavenArtifact.path(), "", 0);
    }

    private boolean isAllowedByRules(JsonNode rules, OperatingSystem operatingSystem) {
        if (rules == null || !rules.isArray() || rules.isEmpty()) {
            return true;
        }

        boolean allowed = false;
        for (JsonNode rule : rules) {
            if (!ruleMatches(rule, operatingSystem)) {
                continue;
            }
            allowed = "allow".equals(rule.path("action").asText(""));
        }
        return allowed;
    }

    private boolean ruleMatches(JsonNode rule, OperatingSystem operatingSystem) {
        JsonNode osRule = rule.path("os");
        if (osRule.isMissingNode()) {
            return true;
        }

        String name = osRule.path("name").asText("");
        return name.isBlank() || name.equals(operatingSystem.getLauncherName());
    }

    private void recreateDirectory(Path directory) {
        try {
            if (Files.exists(directory)) {
                Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path visitedDirectory, IOException exception)
                            throws IOException {
                        if (exception != null) {
                            throw exception;
                        }
                        if (!visitedDirectory.equals(directory)) {
                            Files.deleteIfExists(visitedDirectory);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new MinecraftInstallException("Unable to recreate directory: " + directory, exception);
        }
    }

    private void sleepBeforeRetry(long delayMillis) {
        try {
            TimeUnit.MILLISECONDS.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MinecraftInstallException("Download retry was interrupted.", exception);
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private String rootCauseMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }

        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }

        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return current.getClass().getSimpleName() + ": " + message;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(InputStream inputStream) {
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
    }

    public interface InstallProgressListener {
        void onProgress(double progress, String status);
    }

    private static final class InstallProgressReporter {
        private static final long MIN_INTERVAL_NANOS = 200_000_000L;
        private static final double MIN_PROGRESS_DELTA = 0.01;

        private final InstallProgressListener listener;
        private long lastReportNanos;
        private double lastProgress = -1.0;

        private InstallProgressReporter(InstallProgressListener listener) {
            this.listener = listener == null ? (progress, status) -> { } : listener;
        }

        private void report(double progress, String status) {
            double normalizedProgress = Math.min(1.0, Math.max(0.0, progress));
            lastReportNanos = System.nanoTime();
            lastProgress = normalizedProgress;
            listener.onProgress(normalizedProgress, status);
        }

        private void reportThrottled(double progress, String status) {
            double normalizedProgress = Math.min(1.0, Math.max(0.0, progress));
            long now = System.nanoTime();
            if (lastProgress < 0.0
                    || normalizedProgress >= 1.0
                    || now - lastReportNanos >= MIN_INTERVAL_NANOS
                    || Math.abs(normalizedProgress - lastProgress) >= MIN_PROGRESS_DELTA) {
                lastReportNanos = now;
                lastProgress = normalizedProgress;
                listener.onProgress(normalizedProgress, status);
            }
        }
    }

    private static final class InstallMetrics {
        private final long totalStartedAtNanos = System.nanoTime();
        private long versionManifestMillis;
        private long versionJsonMillis;
        private long clientJarMillis;
        private long librariesCheckMillis;
        private long librariesDownloadMillis;
        private long assetsIndexMillis;
        private long assetsCheckMillis;
        private long assetsDownloadMillis;
        private long forgeInstallerDownloadMillis;
        private long forgeInstallerRunMillis;
        private int librariesTotal;
        private int librariesValid;
        private int librariesDownloaded;
        private int librariesFailed;
        private int assetsTotal;
        private int assetsValid;
        private int assetsDownloaded;
        private int assetsFailed;

        private String formatSummary() {
            long totalMillis = Duration.ofNanos(System.nanoTime() - totalStartedAtNanos).toMillis();
            return "Minecraft/Forge подготовлены: libraries "
                    + librariesTotal
                    + " total, "
                    + librariesValid
                    + " valid, "
                    + librariesDownloaded
                    + " downloaded, "
                    + librariesFailed
                    + " failed; assets "
                    + assetsTotal
                    + " total, "
                    + assetsValid
                    + " valid, "
                    + assetsDownloaded
                    + " downloaded, "
                    + assetsFailed
                    + " failed; время "
                    + formatDuration(totalMillis)
                    + ". Stages: versionManifest="
                    + versionManifestMillis
                    + "ms, versionJson="
                    + versionJsonMillis
                    + "ms, clientJar="
                    + clientJarMillis
                    + "ms, librariesCheck="
                    + librariesCheckMillis
                    + "ms, librariesDownload="
                    + librariesDownloadMillis
                    + "ms, assetsIndex="
                    + assetsIndexMillis
                    + "ms, assetsCheck="
                    + assetsCheckMillis
                    + "ms, assetsDownload="
                    + assetsDownloadMillis
                    + "ms, forgeInstallerDownload="
                    + forgeInstallerDownloadMillis
                    + "ms, forgeInstallerRun="
                    + forgeInstallerRunMillis
                    + "ms.";
        }

        private static String formatDuration(long millis) {
            long seconds = Math.max(0, millis / 1_000);
            long minutes = seconds / 60;
            long remainingSeconds = seconds % 60;
            if (minutes > 0) {
                return minutes + "м " + remainingSeconds + "с";
            }
            return remainingSeconds + "с";
        }
    }

    private static final class DownloadAttemptException extends MinecraftInstallException {
        private final boolean retryable;
        private final int statusCode;
        private final long retryAfterDelayMillis;

        private DownloadAttemptException(String message, boolean retryable, int statusCode, long retryAfterDelayMillis) {
            super(message);
            this.retryable = retryable;
            this.statusCode = statusCode;
            this.retryAfterDelayMillis = retryAfterDelayMillis;
        }

        private DownloadAttemptException(String message, Throwable cause, boolean retryable, int statusCode) {
            super(message, cause);
            this.retryable = retryable;
            this.statusCode = statusCode;
            this.retryAfterDelayMillis = -1;
        }

        private boolean retryable() {
            return retryable;
        }

        private int statusCode() {
            return statusCode;
        }

        private long retryAfterDelayMillis() {
            return retryAfterDelayMillis;
        }
    }

    private record DownloadItem(
            String url,
            Path targetPath,
            String expectedSha1,
            long expectedSize,
            String label
    ) {
        private DownloadItem withTargetPath(Path newTargetPath) {
            return new DownloadItem(url, newTargetPath, expectedSha1, expectedSize, label);
        }
    }

    private record ParallelDownloadResult(int downloaded, int failed) {
    }

    private record Artifact(String path, String url, String sha1, long size) {
    }

    private record MavenArtifact(String path) {
        static MavenArtifact parse(String coordinate, String classifierOverride) {
            String extension = "jar";
            String normalizedCoordinate = coordinate;
            int extensionSeparator = coordinate.indexOf('@');
            if (extensionSeparator >= 0) {
                extension = coordinate.substring(extensionSeparator + 1);
                normalizedCoordinate = coordinate.substring(0, extensionSeparator);
            }

            String[] parts = normalizedCoordinate.split(":");
            if (parts.length < 3) {
                throw new MinecraftInstallException("Invalid Maven coordinate: " + coordinate);
            }

            String group = parts[0];
            String artifact = parts[1];
            String version = parts[2];
            String classifier = !classifierOverride.isBlank()
                    ? classifierOverride
                    : parts.length >= 4 ? parts[3] : "";

            String fileName = artifact + "-" + version + (classifier.isBlank() ? "" : "-" + classifier) + "." + extension;
            String path = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + fileName;
            return new MavenArtifact(path);
        }
    }

    public static class MinecraftInstallException extends RuntimeException {
        public MinecraftInstallException(String message) {
            super(message);
        }

        public MinecraftInstallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
