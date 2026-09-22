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
    private static final String UNKNOWN_CURRENT_VERSION = "0.0.0";
    private static final String VERSION_RESOURCE = "/launcher-version.properties";
    private static final String YANDEX_METADATA_PATH = "launcher/latest.json";
    private static final String DISABLE_SELF_UPDATE_PROPERTY = "launcher.disableSelfUpdateCheck";
    private static final String DISABLE_SELF_UPDATE_ENV = "LAUNCHER_DISABLE_SELF_UPDATE_CHECK";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final YandexDiskService yandexDiskService;
    private final Path updatesDirectoryOverride;
    private final boolean allowHttpForTests;
    private String currentVersion;
    private String currentVersionWarning = "";

    public LauncherSelfUpdateService() {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                new ObjectMapper(),
                new YandexDiskService(),
                null,
                null,
                false);
    }

    LauncherSelfUpdateService(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            YandexDiskService yandexDiskService,
            String currentVersion,
            Path updatesDirectory,
            boolean allowHttpForTests
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.yandexDiskService = yandexDiskService;
        this.currentVersion = currentVersion == null || currentVersion.isBlank()
                ? null
                : currentVersion.trim();
        this.updatesDirectoryOverride = updatesDirectory;
        this.allowHttpForTests = allowHttpForTests;
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

        currentVersionWarning = "Не удалось определить версию лаунчера из manifest или generated resource. "
                + "Используется безопасная неизвестная версия " + UNKNOWN_CURRENT_VERSION + ".";
        currentVersion = UNKNOWN_CURRENT_VERSION;
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

    public Optional<LauncherUpdateCandidate> checkForUpdate(
            String primaryMetadataUrl,
            String yandexDiskPublicUrl
    ) {
        return checkForUpdate(primaryMetadataUrl, yandexDiskPublicUrl, ignored -> { });
    }

    public Optional<LauncherUpdateCandidate> checkForUpdate(
            String primaryMetadataUrl,
            String yandexDiskPublicUrl,
            Consumer<String> statusConsumer
    ) {
        if (primaryMetadataUrl == null || primaryMetadataUrl.isBlank()) {
            return Optional.empty();
        }

        Consumer<String> status = statusConsumer == null ? ignored -> { } : statusConsumer;
        try {
            Optional<LauncherUpdateInfo> primaryMetadata = loadMetadata(
                    createUpdateUri(primaryMetadataUrl.trim(), "URL metadata обновления лаунчера некорректен."),
                    LauncherUpdateSource.PRIMARY_HTTP,
                    true);
            return toCandidateIfNewer(primaryMetadata, LauncherUpdateSource.PRIMARY_HTTP);
        } catch (SelfUpdateException primaryFailure) {
            if (!primaryFailure.isTemporaryFailure()) {
                throw primaryFailure;
            }

            status.accept("Primary launcher update metadata is temporarily unavailable.");
            status.accept("Trying Yandex Disk launcher update metadata fallback.");
            try {
                LauncherUpdateInfo fallbackMetadata = loadYandexMetadata(yandexDiskPublicUrl);
                status.accept("Launcher update metadata loaded from Yandex Disk.");
                return toCandidateIfNewer(
                        Optional.of(fallbackMetadata),
                        LauncherUpdateSource.YANDEX_DISK);
            } catch (SelfUpdateException fallbackFailure) {
                if (fallbackFailure.isTemporaryFailure()) {
                    SelfUpdateException combinedFailure = new SelfUpdateException(
                            "Primary and Yandex Disk launcher update metadata are temporarily unavailable.",
                            fallbackFailure,
                            true);
                    combinedFailure.addSuppressed(primaryFailure);
                    throw combinedFailure;
                }
                throw fallbackFailure;
            }
        }
    }

    public Path downloadInstaller(
            LauncherUpdateCandidate candidate,
            String yandexDiskPublicUrl,
            Consumer<String> statusConsumer
    ) {
        if (candidate == null) {
            throw new SelfUpdateException("Launcher update candidate is empty.");
        }

        LauncherUpdateInfo updateInfo = candidate.updateInfo();
        validateUpdateInfo(updateInfo, candidate.source());
        Consumer<String> status = statusConsumer == null ? ignored -> { } : statusConsumer;
        InstallerPaths installerPaths = createInstallerPaths(updateInfo);

        if (candidate.source() == LauncherUpdateSource.YANDEX_DISK) {
            URI installerUri = resolveYandexDownloadUri(
                    yandexDiskPublicUrl,
                    updateInfo.getUrl(),
                    "Unable to resolve Yandex Disk launcher installer.");
            return downloadInstallerFromUri(updateInfo, installerUri, installerPaths, status);
        }

        try {
            URI installerUri = createUpdateUri(
                    updateInfo.getUrl(),
                    "URL установщика лаунчера в metadata некорректен.");
            return downloadInstallerFromUri(updateInfo, installerUri, installerPaths, status);
        } catch (SelfUpdateException primaryFailure) {
            if (!primaryFailure.isTemporaryFailure()) {
                throw primaryFailure;
            }

            deleteTemporaryBeforeFallback(installerPaths.temporaryPath(), status);
            status.accept("Primary launcher installer is temporarily unavailable.");
            status.accept("Trying Yandex Disk launcher installer fallback.");

            LauncherUpdateInfo fallbackMetadata;
            try {
                fallbackMetadata = loadYandexMetadata(yandexDiskPublicUrl);
                status.accept("Launcher update metadata loaded from Yandex Disk.");
            } catch (SelfUpdateException fallbackFailure) {
                throw combineTemporaryFailures(primaryFailure, fallbackFailure);
            }

            validateMatchingMetadata(updateInfo, fallbackMetadata);
            URI fallbackInstallerUri = resolveYandexDownloadUri(
                    yandexDiskPublicUrl,
                    fallbackMetadata.getUrl(),
                    "Unable to resolve Yandex Disk launcher installer.");
            try {
                return downloadInstallerFromUri(
                        fallbackMetadata,
                        fallbackInstallerUri,
                        installerPaths,
                        status);
            } catch (SelfUpdateException fallbackFailure) {
                throw combineTemporaryFailures(primaryFailure, fallbackFailure);
            }
        }
    }

    public void startInstaller(Path installerPath) {
        try {
            new ProcessBuilder(installerPath.toAbsolutePath().toString()).start();
        } catch (IOException exception) {
            throw new SelfUpdateException("Unable to start launcher installer.", exception);
        }
    }

    private Optional<LauncherUpdateCandidate> toCandidateIfNewer(
            Optional<LauncherUpdateInfo> updateInfo,
            LauncherUpdateSource source
    ) {
        if (updateInfo.isEmpty()
                || !isNewerVersion(updateInfo.get().getVersion(), getCurrentVersion())) {
            return Optional.empty();
        }
        return Optional.of(new LauncherUpdateCandidate(updateInfo.get(), source));
    }

    private Optional<LauncherUpdateInfo> loadMetadata(
            URI metadataUri,
            LauncherUpdateSource source,
            boolean missingMeansNoUpdate
    ) {
        HttpRequest request = HttpRequest.newBuilder(metadataUri)
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

        if (response.statusCode() == 404 && missingMeansNoUpdate) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            int statusCode = response.statusCode();
            throw new SelfUpdateException(
                    "Launcher update server returned HTTP " + statusCode + ".",
                    isTemporaryUpdateStatus(statusCode));
        }

        LauncherUpdateInfo updateInfo;
        try {
            updateInfo = objectMapper.readValue(response.body(), LauncherUpdateInfo.class);
        } catch (IOException exception) {
            throw new SelfUpdateException("Unable to parse launcher update info.", exception);
        }

        validateUpdateInfo(updateInfo, source);
        return Optional.of(updateInfo);
    }

    private LauncherUpdateInfo loadYandexMetadata(String yandexDiskPublicUrl) {
        URI metadataUri = resolveYandexDownloadUri(
                yandexDiskPublicUrl,
                YANDEX_METADATA_PATH,
                "Unable to resolve Yandex Disk launcher update metadata.");
        return loadMetadata(metadataUri, LauncherUpdateSource.YANDEX_DISK, false)
                .orElseThrow(() -> new SelfUpdateException("Yandex Disk launcher update metadata is missing."));
    }

    private URI resolveYandexDownloadUri(String publicUrl, String filePath, String failureMessage) {
        try {
            URI uri = yandexDiskService.resolveDownloadUri(publicUrl, filePath);
            return validateResolvedYandexUri(uri, failureMessage);
        } catch (YandexDiskService.YandexDiskException exception) {
            throw new SelfUpdateException(
                    failureMessage + " " + exception.getMessage(),
                    exception,
                    exception.isTemporaryFailure());
        }
    }

    private URI validateResolvedYandexUri(URI uri, String failureMessage) {
        if (uri == null) {
            throw new SelfUpdateException(failureMessage);
        }
        return createUpdateUri(uri.toString(), failureMessage);
    }

    private Path downloadInstallerFromUri(
            LauncherUpdateInfo updateInfo,
            URI installerUri,
            InstallerPaths installerPaths,
            Consumer<String> statusConsumer
    ) {
        try {
            Files.createDirectories(installerPaths.updatesDirectory());
            deleteStaleInstallerDownload(installerPaths.temporaryPath(), statusConsumer);
        } catch (IOException exception) {
            throw new SelfUpdateException("Не удалось подготовить директорию обновления лаунчера: "
                    + exception.getMessage(), exception);
        }

        HttpRequest request = HttpRequest.newBuilder(installerUri)
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (ConnectException exception) {
            throw new SelfUpdateException("Launcher installer server is unreachable.", exception, true);
        } catch (HttpTimeoutException exception) {
            throw new SelfUpdateException("Launcher installer download timed out.", exception, true);
        } catch (IOException exception) {
            throw new SelfUpdateException("Unable to download launcher installer.", exception, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SelfUpdateException("Launcher installer download was interrupted.", exception);
        }

        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw new SelfUpdateException(
                    "Launcher installer server returned HTTP " + response.statusCode() + ".",
                    isTemporaryUpdateStatus(response.statusCode()));
        }

        try {
            writeInstallerResponse(response.body(), installerPaths.temporaryPath());
            verifyInstaller(updateInfo, installerPaths.temporaryPath());
            replaceInstaller(installerPaths.temporaryPath(), installerPaths.installerPath());
            return installerPaths.installerPath();
        } catch (SelfUpdateException exception) {
            deleteQuietly(installerPaths.temporaryPath());
            throw exception;
        } finally {
            closeQuietly(response.body());
        }
    }

    private void writeInstallerResponse(InputStream inputStream, Path temporaryPath) {
        try (OutputStream outputStream = Files.newOutputStream(temporaryPath)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                int bytesRead;
                try {
                    bytesRead = inputStream.read(buffer);
                } catch (IOException exception) {
                    throw new SelfUpdateException(
                            "Network error while reading launcher installer.",
                            exception,
                            true);
                }
                if (bytesRead == -1) {
                    break;
                }
                try {
                    outputStream.write(buffer, 0, bytesRead);
                } catch (IOException exception) {
                    throw new SelfUpdateException("Unable to save launcher installer.", exception);
                }
            }
        } catch (SelfUpdateException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new SelfUpdateException("Unable to save launcher installer.", exception);
        }
    }

    private InstallerPaths createInstallerPaths(LauncherUpdateInfo updateInfo) {
        Path updatesDirectory = updatesDirectoryOverride == null
                ? LauncherPaths.createConfigDirectory().resolve("updates")
                : updatesDirectoryOverride;
        Path installerPath = updatesDirectory.resolve("DeluxeWarfareLauncher-Setup-"
                + sanitizeFileName(updateInfo.getVersion())
                + ".exe");
        Path temporaryPath = installerPath.resolveSibling(installerPath.getFileName() + ".download");
        return new InstallerPaths(updatesDirectory, installerPath, temporaryPath);
    }

    private void validateUpdateInfo(LauncherUpdateInfo updateInfo, LauncherUpdateSource source) {
        if (updateInfo == null) {
            throw new SelfUpdateException("Launcher update info is empty.");
        }
        if (!isValidVersion(updateInfo.getVersion())) {
            throw new SelfUpdateException("Launcher update version is invalid.");
        }
        if (updateInfo.getUrl().isBlank()) {
            throw new SelfUpdateException("Launcher update URL is empty.");
        }
        if (!isValidSha256(updateInfo.getSha256())) {
            throw new SelfUpdateException("Launcher update sha256 is invalid.");
        }
        if (updateInfo.getSize() <= 0) {
            throw new SelfUpdateException("Launcher update size is invalid.");
        }

        if (source == LauncherUpdateSource.PRIMARY_HTTP) {
            createUpdateUri(updateInfo.getUrl(), "Launcher update URL is invalid.");
        } else {
            validateYandexPath(updateInfo.getUrl());
        }
    }

    private void validateYandexPath(String value) {
        String path = value == null ? "" : value.trim();
        if (path.isBlank() || path.startsWith("/") || path.startsWith("\\") || path.contains("\\")) {
            throw new SelfUpdateException("Yandex Disk launcher update path is invalid.");
        }
        try {
            URI uri = URI.create(path);
            if (uri.isAbsolute() || uri.getHost() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new SelfUpdateException("Yandex Disk launcher update path is invalid.");
            }
            path = uri.getPath();
        } catch (IllegalArgumentException exception) {
            throw new SelfUpdateException("Yandex Disk launcher update path is invalid.", exception);
        }
        if (path == null || path.isBlank()) {
            throw new SelfUpdateException("Yandex Disk launcher update path is invalid.");
        }
        for (String segment : path.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new SelfUpdateException("Yandex Disk launcher update path is invalid.");
            }
        }
    }

    private void validateMatchingMetadata(LauncherUpdateInfo primary, LauncherUpdateInfo fallback) {
        validateUpdateInfo(primary, LauncherUpdateSource.PRIMARY_HTTP);
        validateUpdateInfo(fallback, LauncherUpdateSource.YANDEX_DISK);
        if (!primary.getVersion().equals(fallback.getVersion())
                || !primary.getSha256().equalsIgnoreCase(fallback.getSha256())
                || primary.getSize() != fallback.getSize()
                || primary.isMandatory() != fallback.isMandatory()
                || !primary.getNotes().equals(fallback.getNotes())) {
            throw new SelfUpdateException("Launcher update mirror metadata mismatch.");
        }
    }

    private SelfUpdateException combineTemporaryFailures(
            SelfUpdateException primaryFailure,
            SelfUpdateException fallbackFailure
    ) {
        if (!fallbackFailure.isTemporaryFailure()) {
            return fallbackFailure;
        }
        SelfUpdateException combinedFailure = new SelfUpdateException(
                "Primary and Yandex Disk launcher installer sources are temporarily unavailable.",
                fallbackFailure,
                true);
        combinedFailure.addSuppressed(primaryFailure);
        return combinedFailure;
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

    private void verifyInstaller(LauncherUpdateInfo updateInfo, Path installerPath) {
        try {
            long actualSize = Files.size(installerPath);
            if (actualSize != updateInfo.getSize()) {
                deleteQuietly(installerPath);
                throw new SelfUpdateException("Launcher installer size mismatch. Expected "
                        + updateInfo.getSize() + " but got " + actualSize + ".");
            }
        } catch (IOException exception) {
            deleteQuietly(installerPath);
            throw new SelfUpdateException("Unable to read launcher installer size.", exception);
        }

        String expectedSha256 = updateInfo.getSha256().toLowerCase();
        String actualSha256 = calculateSha256(installerPath);
        if (!expectedSha256.equals(actualSha256)) {
            deleteQuietly(installerPath);
            throw new SelfUpdateException("Launcher installer sha256 mismatch. Expected "
                    + expectedSha256 + " but got " + actualSha256 + ".");
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

    private boolean isNewerVersion(String candidateVersion, String installedVersion) {
        int[] candidate = parseVersion(candidateVersion);
        int[] current = parseVersion(installedVersion);
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
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        String[] rawParts = normalized.split("\\.");
        int[] parts = new int[rawParts.length];
        for (int index = 0; index < rawParts.length; index++) {
            parts[index] = Integer.parseInt(rawParts[index]);
        }
        return parts;
    }

    private boolean isTemporaryUpdateStatus(int statusCode) {
        return statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    private URI createUpdateUri(String value, String errorMessage) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            boolean validScheme = "https".equalsIgnoreCase(scheme)
                    || (allowHttpForTests && "http".equalsIgnoreCase(scheme));
            if (uri.getHost() == null || !validScheme) {
                throw new SelfUpdateException(errorMessage);
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new SelfUpdateException(errorMessage, exception);
        }
    }

    private boolean isValidVersion(String value) {
        if (value == null || !value.matches("(?i)v?[0-9]+(?:\\.[0-9]+)+")) {
            return false;
        }
        String normalized = value.startsWith("v") || value.startsWith("V")
                ? value.substring(1)
                : value;
        try {
            for (String part : normalized.split("\\.")) {
                Integer.parseInt(part);
            }
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean isValidSha256(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{64}");
    }

    private void deleteTemporaryBeforeFallback(Path temporaryPath, Consumer<String> statusConsumer) {
        try {
            deleteStaleInstallerDownload(temporaryPath, statusConsumer);
        } catch (IOException exception) {
            throw new SelfUpdateException("Unable to remove incomplete primary launcher installer.", exception);
        }
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
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
    }

    private record InstallerPaths(Path updatesDirectory, Path installerPath, Path temporaryPath) {
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
