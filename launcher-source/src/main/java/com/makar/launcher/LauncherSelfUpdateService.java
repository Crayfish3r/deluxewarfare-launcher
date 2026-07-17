package com.makar.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

public final class LauncherSelfUpdateService {
    private static final int BUFFER_SIZE = 8192;
    private static final String FALLBACK_CURRENT_VERSION = "2.0.0";
    private static final String VERSION_RESOURCE = "/launcher-version.properties";
    private static final String DISABLE_SELF_UPDATE_PROPERTY = "launcher.disableSelfUpdateCheck";
    private static final String DISABLE_SELF_UPDATE_ENV = "LAUNCHER_DISABLE_SELF_UPDATE_CHECK";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private String currentVersion;
    private String currentVersionWarning = "";

    public LauncherSelfUpdateService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public String getCurrentVersion() {
        if (currentVersion != null) {
            return currentVersion;
        }

        Package packageInfo = LauncherSelfUpdateService.class.getPackage();
        String implementationVersion = packageInfo == null ? "" : packageInfo.getImplementationVersion();

        if (implementationVersion != null && !implementationVersion.isBlank()) {
            currentVersion = implementationVersion.trim();
            return currentVersion;
        }

        Optional<String> resourceVersion = readGeneratedVersionResource();
        if (resourceVersion.isPresent()) {
            currentVersion = resourceVersion.get();
            return currentVersion;
        }

        currentVersionWarning = "Не удалось определить версию лаунчера из manifest и generated resource. "
                + "Используется fallback " + FALLBACK_CURRENT_VERSION + ".";
        currentVersion = FALLBACK_CURRENT_VERSION;
        return currentVersion;
    }

    public Optional<String> getCurrentVersionWarning() {
        getCurrentVersion();
        return currentVersionWarning.isBlank() ? Optional.empty() : Optional.of(currentVersionWarning);
    }

    public boolean isSelfUpdateCheckDisabledForDev() {
        return isEnabledFlag(System.getProperty(DISABLE_SELF_UPDATE_PROPERTY))
                || isEnabledFlag(System.getenv(DISABLE_SELF_UPDATE_ENV));
    }

    public Optional<LauncherUpdateInfo> checkForUpdate(String updateInfoUrl) {
        if (updateInfoUrl == null || updateInfoUrl.isBlank()) {
            return Optional.empty();
        }

        HttpRequest request = HttpRequest.newBuilder(createHttpUri(
                        updateInfoUrl.trim(),
                        "URL metadata обновления лаунчера некорректен."))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException exception) {
            throw new SelfUpdateException("Launcher update server is unreachable.", exception, true);
        } catch (HttpTimeoutException exception) {
            throw new SelfUpdateException("Launcher update check timed out.", exception, true);
        } catch (IOException exception) {
            throw new SelfUpdateException("Unable to check launcher update.", exception, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SelfUpdateException("Launcher update check was interrupted.", exception);
        }

        if (response.statusCode() == 404) {
            return Optional.empty();
        }

        if (response.statusCode() != 200) {
            int statusCode = response.statusCode();
            throw new SelfUpdateException(
                    "Launcher update server returned HTTP " + statusCode + ".",
                    isTemporaryUpdateStatus(statusCode)
            );
        }

        LauncherUpdateInfo updateInfo;
        try {
            updateInfo = objectMapper.readValue(response.body(), LauncherUpdateInfo.class);
        } catch (IOException exception) {
            throw new SelfUpdateException("Unable to parse launcher update info.", exception);
        }

        validateUpdateInfo(updateInfo);

        if (!isNewerVersion(updateInfo.getVersion(), getCurrentVersion())) {
            return Optional.empty();
        }

        return Optional.of(updateInfo);
    }

    private Optional<String> readGeneratedVersionResource() {
        try (InputStream inputStream = LauncherSelfUpdateService.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (inputStream == null) {
                return Optional.empty();
            }

            Properties properties = new Properties();
            properties.load(inputStream);
            String version = properties.getProperty("version", "").trim();
            return version.isBlank() ? Optional.empty() : Optional.of(version);
        } catch (IOException exception) {
            currentVersionWarning = "Не удалось прочитать generated resource версии лаунчера: "
                    + exception.getMessage();
            return Optional.empty();
        }
    }

    private boolean isEnabledFlag(String value) {
        if (value == null) {
            return false;
        }

        String normalized = value.trim().toLowerCase();
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized);
    }

    public Path downloadInstaller(LauncherUpdateInfo updateInfo) {
        return downloadInstaller(updateInfo, ignored -> { });
    }

    public Path downloadInstaller(LauncherUpdateInfo updateInfo, Consumer<String> statusConsumer) {
        validateUpdateInfo(updateInfo);
        Consumer<String> status = statusConsumer == null ? ignored -> { } : statusConsumer;

        Path updatesDirectory = LauncherPaths.createConfigDirectory().resolve("updates");
        Path installerPath = updatesDirectory.resolve("DeluxeWarfareLauncher-Setup-"
                + sanitizeFileName(updateInfo.getVersion())
                + ".exe");
        Path temporaryPath = installerPath.resolveSibling(installerPath.getFileName() + ".download");

        HttpRequest request = HttpRequest.newBuilder(createHttpUri(
                        updateInfo.getUrl(),
                        "URL установщика лаунчера в metadata некорректен."))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (ConnectException exception) {
            throw new SelfUpdateException("Launcher installer server is unreachable.", exception);
        } catch (IOException exception) {
            throw new SelfUpdateException("Unable to download launcher installer.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SelfUpdateException("Launcher installer download was interrupted.", exception);
        }

        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw new SelfUpdateException("Launcher installer server returned HTTP " + response.statusCode() + ".");
        }

        try {
            Files.createDirectories(updatesDirectory);
            deleteStaleInstallerDownload(temporaryPath, status);
        } catch (IOException exception) {
            closeQuietly(response.body());
            throw new SelfUpdateException("Не удалось подготовить директорию обновления лаунчера: "
                    + exception.getMessage(), exception);
        }

        try (InputStream inputStream = response.body();
                OutputStream outputStream = Files.newOutputStream(temporaryPath)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        } catch (IOException exception) {
            deleteQuietly(temporaryPath);
            throw new SelfUpdateException("Unable to save launcher installer.", exception);
        }

        verifyInstaller(updateInfo, temporaryPath);
        replaceInstaller(temporaryPath, installerPath);

        return installerPath;
    }

    public void startInstaller(Path installerPath) {
        try {
            new ProcessBuilder(installerPath.toAbsolutePath().toString()).start();
        } catch (IOException exception) {
            throw new SelfUpdateException("Unable to start launcher installer.", exception);
        }
    }

    private void validateUpdateInfo(LauncherUpdateInfo updateInfo) {
        if (updateInfo == null) {
            throw new SelfUpdateException("Launcher update info is empty.");
        }

        if (updateInfo.getVersion().isBlank()) {
            throw new SelfUpdateException("Launcher update version is empty.");
        }

        if (updateInfo.getUrl().isBlank()) {
            throw new SelfUpdateException("Launcher update URL is empty.");
        }

        if (updateInfo.getSha256().isBlank()) {
            throw new SelfUpdateException("Launcher update sha256 is empty.");
        }

        if (!isValidSha256(updateInfo.getSha256())) {
            throw new SelfUpdateException("Launcher update sha256 is invalid.");
        }

        createHttpUri(updateInfo.getUrl(), "Launcher update URL is invalid.");
    }

    private void verifyInstaller(LauncherUpdateInfo updateInfo, Path installerPath) {
        if (updateInfo.getSize() > 0) {
            try {
                long actualSize = Files.size(installerPath);
                if (actualSize != updateInfo.getSize()) {
                    deleteQuietly(installerPath);
                    throw new SelfUpdateException("Launcher installer size mismatch. Expected "
                            + updateInfo.getSize()
                            + " but got "
                            + actualSize
                            + ".");
                }
            } catch (IOException exception) {
                deleteQuietly(installerPath);
                throw new SelfUpdateException("Unable to read launcher installer size.", exception);
            }
        }

        String expectedSha256 = updateInfo.getSha256().toLowerCase();
        String actualSha256 = calculateSha256(installerPath);

        if (!expectedSha256.equals(actualSha256)) {
            deleteQuietly(installerPath);
            throw new SelfUpdateException("Launcher installer sha256 mismatch. Expected "
                    + expectedSha256
                    + " but got "
                    + actualSha256
                    + ".");
        }
    }

    private void replaceInstaller(Path temporaryPath, Path installerPath) {
        try {
            Files.move(temporaryPath, installerPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            deleteQuietly(temporaryPath);
            throw new SelfUpdateException("Unable to replace launcher installer.", exception);
        }
    }

    private String calculateSha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream inputStream = Files.newInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new SelfUpdateException("SHA-256 is not available.", exception);
        } catch (IOException exception) {
            throw new SelfUpdateException("Unable to calculate launcher installer sha256.", exception);
        }
    }

    private boolean isNewerVersion(String candidateVersion, String currentVersion) {
        int[] candidate = parseVersion(candidateVersion);
        int[] current = parseVersion(currentVersion);

        int maxLength = Math.max(candidate.length, current.length);
        for (int index = 0; index < maxLength; index++) {
            int candidatePart = index < candidate.length ? candidate[index] : 0;
            int currentPart = index < current.length ? current[index] : 0;

            if (candidatePart > currentPart) {
                return true;
            }

            if (candidatePart < currentPart) {
                return false;
            }
        }

        return false;
    }

    private int[] parseVersion(String version) {
        String normalized = version == null ? "" : version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }

        String[] rawParts = normalized.split("\\.");
        int[] parts = new int[rawParts.length];

        for (int index = 0; index < rawParts.length; index++) {
            parts[index] = parseVersionPart(rawParts[index]);
        }

        return parts;
    }

    private int parseVersionPart(String value) {
        String digits = value.replaceAll("[^0-9].*$", "");
        if (digits.isBlank()) {
            return 0;
        }

        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private boolean isTemporaryUpdateStatus(int statusCode) {
        return statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    private URI createHttpUri(String value, String errorMessage) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (uri.getHost() == null
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                throw new SelfUpdateException(errorMessage);
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new SelfUpdateException(errorMessage, exception);
        }
    }

    private boolean isValidSha256(String value) {
        return value.matches("(?i)[0-9a-f]{64}");
    }

    private void deleteStaleInstallerDownload(Path temporaryPath, Consumer<String> statusConsumer) throws IOException {
        try {
            if (Files.deleteIfExists(temporaryPath)) {
                statusConsumer.accept("Удален старый временный файл установщика лаунчера: "
                        + temporaryPath.getFileName());
            }
        } catch (IOException exception) {
            throw new IOException("не удалось удалить временный файл "
                    + temporaryPath
                    + ". Возможно, файл занят другим процессом или антивирусом.", exception);
        }
    }

    private String sanitizeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
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

    public static final class SelfUpdateException extends RuntimeException {
        private final boolean temporaryFailure;

        public SelfUpdateException(String message) {
            super(message);
            this.temporaryFailure = false;
        }

        public SelfUpdateException(String message, Throwable cause) {
            super(message, cause);
            this.temporaryFailure = false;
        }

        public SelfUpdateException(String message, boolean temporaryFailure) {
            super(message);
            this.temporaryFailure = temporaryFailure;
        }

        public SelfUpdateException(String message, Throwable cause, boolean temporaryFailure) {
            super(message, cause);
            this.temporaryFailure = temporaryFailure;
        }

        public boolean isTemporaryFailure() {
            return temporaryFailure;
        }
    }
}
