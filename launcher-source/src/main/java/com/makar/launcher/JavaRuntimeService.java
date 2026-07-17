package com.makar.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaRuntimeService {
    private static final int REQUIRED_MAJOR_VERSION = 17;
    private static final Duration VERSION_COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern VERSION_PATTERN = Pattern.compile("version\\s+\"([^\"]+)\"");

    private final String javaExecutableOverride;

    public JavaRuntimeService() {
        this(null);
    }

    public JavaRuntimeService(String javaExecutableOverride) {
        this.javaExecutableOverride = javaExecutableOverride;
    }

    public JavaRuntimeInfo checkJavaRuntime() {
        List<String> candidates = findJavaExecutableCandidates();

        if (candidates.isEmpty()) {
            throw new JavaRuntimeException("Java executable was not found. Install Java 17 or newer.");
        }

        JavaRuntimeException lastFailure = null;
        for (String executable : candidates) {
            try {
                JavaVersionCommandResult commandResult = runJavaVersion(executable);
                int majorVersion = parseMajorVersion(commandResult.output());

                if (majorVersion >= REQUIRED_MAJOR_VERSION) {
                    return new JavaRuntimeInfo(executable, commandResult.versionText(), majorVersion);
                }

                lastFailure = new JavaRuntimeException("Java "
                        + REQUIRED_MAJOR_VERSION
                        + " or newer is required. Found: "
                        + commandResult.versionText()
                        + ".");
            } catch (JavaRuntimeException exception) {
                lastFailure = exception;
            }
        }

        if (lastFailure != null) {
            throw lastFailure;
        }

        throw new JavaRuntimeException("Java executable was not found. Install Java 17 or newer.");
    }

    public JavaRuntimeInfo checkJavaRuntime(String executableOverride) {
        return new JavaRuntimeService(executableOverride).checkJavaRuntime();
    }

    private List<String> findJavaExecutableCandidates() {
        Set<String> candidates = new LinkedHashSet<>();

        if (javaExecutableOverride != null && !javaExecutableOverride.isBlank()) {
            String expandedOverride = expandEnvironmentVariables(javaExecutableOverride.trim());
            candidates.add(expandedOverride);
        }

        findBundledLauncherRuntime().ifPresent(candidates::add);

        String currentJavaHome = System.getProperty("java.home");
        if (currentJavaHome != null && !currentJavaHome.isBlank()) {
            Path currentJavaExecutable = Path.of(currentJavaHome, "bin", getJavaExecutableName());
            if (Files.isRegularFile(currentJavaExecutable)) {
                candidates.add(currentJavaExecutable.toString());
            }
        }

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            Path javaFromJavaHome = Path.of(javaHome, "bin", getJavaExecutableName());
            if (Files.isRegularFile(javaFromJavaHome)) {
                candidates.add(javaFromJavaHome.toString());
            }
        }

        candidates.add(getJavaExecutableName());

        return new ArrayList<>(candidates);
    }

    private Optional<String> findBundledLauncherRuntime() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path appExecutable = Path.of(appPath);
            Optional<String> runtimeFromAppPath = findRuntimeNearAppDirectory(appExecutable.getParent());
            if (runtimeFromAppPath.isPresent()) {
                return runtimeFromAppPath;
            }
        }

        String userDirectory = System.getProperty("user.dir");
        if (userDirectory != null && !userDirectory.isBlank()) {
            Optional<String> runtimeFromUserDirectory = findRuntimeNearAppDirectory(Path.of(userDirectory));
            if (runtimeFromUserDirectory.isPresent()) {
                return runtimeFromUserDirectory;
            }
        }

        String currentJavaHome = System.getProperty("java.home");
        if (currentJavaHome != null && !currentJavaHome.isBlank()) {
            Path currentJavaHomePath = Path.of(currentJavaHome);
            Path currentRuntimeJava = currentJavaHomePath.resolve("bin").resolve(getJavaExecutableName());
            if (Files.isRegularFile(currentRuntimeJava)) {
                return Optional.of(currentRuntimeJava.toString());
            }

            Optional<String> runtimeFromJavaHome = findRuntimeNearAppDirectory(currentJavaHomePath.getParent());
            if (runtimeFromJavaHome.isPresent()) {
                return runtimeFromJavaHome;
            }
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            Path installedRuntimeJava = Path.of(
                    localAppData,
                    "DeluxeWarfareLauncher",
                    "runtime",
                    "bin",
                    getJavaExecutableName());

            if (Files.isRegularFile(installedRuntimeJava)) {
                return Optional.of(installedRuntimeJava.toString());
            }
        }

        return Optional.empty();
    }

    private Optional<String> findRuntimeNearAppDirectory(Path appDirectory) {
        if (appDirectory == null) {
            return Optional.empty();
        }

        Path runtimeJava = appDirectory.resolve("runtime").resolve("bin").resolve(getJavaExecutableName());
        if (Files.isRegularFile(runtimeJava)) {
            return Optional.of(runtimeJava.toString());
        }

        Path parent = appDirectory.getParent();
        if (parent == null) {
            return Optional.empty();
        }

        Path parentRuntimeJava = parent.resolve("runtime").resolve("bin").resolve(getJavaExecutableName());
        if (Files.isRegularFile(parentRuntimeJava)) {
            return Optional.of(parentRuntimeJava.toString());
        }

        return Optional.empty();
    }

    private String expandEnvironmentVariables(String value) {
        String expanded = value;

        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            expanded = expanded.replace("%" + entry.getKey() + "%", entry.getValue());
        }

        return expanded;
    }

    private String getJavaExecutableName() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("win") ? "java.exe" : "java";
    }

    private JavaVersionCommandResult runJavaVersion(String executable) {
        ProcessBuilder processBuilder = new ProcessBuilder(executable, "-version");
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            boolean completed = process.waitFor(VERSION_COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new JavaRuntimeException("Timed out while checking Java version.");
            }

            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (process.exitValue() != 0) {
                throw new JavaRuntimeException("Unable to run java -version: " + output);
            }

            return new JavaVersionCommandResult(output, extractVersionText(output));
        } catch (IOException exception) {
            throw new JavaRuntimeException("Java executable was not found or could not be started: "
                    + executable
                    + ".", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JavaRuntimeException("Java version check was interrupted.", exception);
        }
    }

    private String extractVersionText(String output) {
        Matcher matcher = VERSION_PATTERN.matcher(output);
        if (matcher.find()) {
            return matcher.group(1);
        }

        throw new JavaRuntimeException("Unable to parse java -version output: " + output);
    }

    private int parseMajorVersion(String output) {
        String versionText = extractVersionText(output);
        String[] parts = versionText.split("\\.");

        try {
            if (parts.length > 1 && "1".equals(parts[0])) {
                return Integer.parseInt(parts[1]);
            }

            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException exception) {
            throw new JavaRuntimeException("Unable to parse Java major version: " + versionText, exception);
        }
    }

    private record JavaVersionCommandResult(String output, String versionText) {
    }

    public static final class JavaRuntimeInfo {
        private final String executable;
        private final String versionText;
        private final int majorVersion;

        public JavaRuntimeInfo(String executable, String versionText, int majorVersion) {
            this.executable = executable;
            this.versionText = versionText;
            this.majorVersion = majorVersion;
        }

        public String getExecutable() {
            return executable;
        }

        public String getVersionText() {
            return versionText;
        }

        public int getMajorVersion() {
            return majorVersion;
        }
    }

    public static final class JavaRuntimeException extends RuntimeException {
        public JavaRuntimeException(String message) {
            super(message);
        }

        public JavaRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
