package com.makar.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class DownloadService {
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final int STATUS_NOT_AVAILABLE = -1;
    private static final long BASE_RETRY_DELAY_MILLIS = 500;
    private static final long MAX_RETRY_DELAY_MILLIS = 4_000;
    private static final long MAX_RETRY_AFTER_DELAY_MILLIS = 30_000;

    private final HttpClient httpClient;
    private final FileHashService fileHashService;
    private final MirrorDownloadResolver mirrorDownloadResolver;

    public DownloadService(FileHashService fileHashService) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), fileHashService, new YandexDiskService());
    }

    DownloadService(
            HttpClient httpClient,
            FileHashService fileHashService,
            MirrorDownloadResolver mirrorDownloadResolver
    ) {
        this.httpClient = httpClient;
        this.fileHashService = fileHashService;
        this.mirrorDownloadResolver = mirrorDownloadResolver;
    }

    public void downloadMissingOrOutdatedFiles(
            List<FileHashService.FileCheckResult> fileChecks,
            DownloadProgressListener listener
    ) {
        downloadMissingOrOutdatedFiles(fileChecks, "", listener);
    }

    public void downloadMissingOrOutdatedFiles(
            List<FileHashService.FileCheckResult> fileChecks,
            String yandexDiskPublicUrl,
            DownloadProgressListener listener
    ) {
        List<FileHashService.FileCheckResult> filesToDownload = getFilesToDownload(fileChecks);
        long totalBytes = filesToDownload.stream()
                .map(FileHashService.FileCheckResult::getEntry)
                .mapToLong(ManifestFileEntry::getSize)
                .filter(size -> size > 0)
                .sum();
        long downloadedBytes = 0;

        listener.onProgress(downloadedBytes, totalBytes);

        for (FileHashService.FileCheckResult fileCheck : filesToDownload) {
            ManifestFileEntry entry = fileCheck.getEntry();
            Path finalPath = fileCheck.getLocalPath();
            Path temporaryPath = finalPath.resolveSibling(finalPath.getFileName() + ".download");

            deleteStaleDownload(temporaryPath, entry, listener);
            listener.onFileStarted(entry);
            downloadedBytes += downloadFile(
                    entry,
                    temporaryPath,
                    downloadedBytes,
                    totalBytes,
                    yandexDiskPublicUrl,
                    listener);
            replaceFinalFile(temporaryPath, finalPath);
            listener.onFileFinished(entry);
        }

        listener.onProgress(totalBytes, totalBytes);
    }

    private List<FileHashService.FileCheckResult> getFilesToDownload(List<FileHashService.FileCheckResult> fileChecks) {
        List<FileHashService.FileCheckResult> filesToDownload = new ArrayList<>();
        for (FileHashService.FileCheckResult fileCheck : fileChecks) {
            if (fileCheck.getStatus() == FileHashService.FileStatus.MISSING
                    || fileCheck.getStatus() == FileHashService.FileStatus.OUTDATED) {
                filesToDownload.add(fileCheck);
            }
        }
        return filesToDownload;
    }

    private long downloadFile(
            ManifestFileEntry entry,
            Path temporaryPath,
            long downloadedBeforeFile,
            long totalBytes,
            String yandexDiskPublicUrl,
            DownloadProgressListener listener
    ) {
        DownloadException primaryFailure;
        try {
            listener.onSourceStarted(entry, "GitHub");
            long downloaded = downloadFromUriWithRetry(
                    entry,
                    createUri(entry.getUrl(), entry.getPath()),
                    temporaryPath,
                    downloadedBeforeFile,
                    totalBytes,
                    "GitHub",
                    listener);
            verifyDownloadedFile(entry, temporaryPath);
            return downloaded;
        } catch (DownloadException exception) {
            deleteQuietly(temporaryPath);
            if (Thread.currentThread().isInterrupted()
                    || yandexDiskPublicUrl == null
                    || yandexDiskPublicUrl.isBlank()) {
                throw exception;
            }
            primaryFailure = exception;
            listener.onSourceFailed(entry, "GitHub", exception.getMessage());
        }

        try {
            listener.onSourceStarted(entry, "Yandex Disk");
            URI mirrorUri = mirrorDownloadResolver.resolveDownloadUri(yandexDiskPublicUrl, entry.getPath());
            long downloaded = downloadFromUriWithRetry(
                    entry,
                    mirrorUri,
                    temporaryPath,
                    downloadedBeforeFile,
                    totalBytes,
                    "Yandex Disk",
                    listener);
            verifyDownloadedFile(entry, temporaryPath);
            return downloaded;
        } catch (RuntimeException mirrorFailure) {
            deleteQuietly(temporaryPath);
            DownloadException combined = new DownloadException(
                    "Unable to download "
                            + entry.getPath()
                            + " from GitHub or Yandex Disk. Primary: "
                            + primaryFailure.getMessage()
                            + " Mirror: "
                            + mirrorFailure.getMessage(),
                    mirrorFailure);
            combined.addSuppressed(primaryFailure);
            throw combined;
        }
    }

    private long downloadFromUriWithRetry(
            ManifestFileEntry entry,
            URI sourceUri,
            Path temporaryPath,
            long downloadedBeforeFile,
            long totalBytes,
            String sourceName,
            DownloadProgressListener listener
    ) {
        DownloadException lastException = null;
        String host = getSafeHost(sourceUri);

        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            deleteStaleDownload(temporaryPath, entry, listener);
            listener.onDownloadAttempt(entry, sourceName, attempt, MAX_DOWNLOAD_ATTEMPTS, host);
            long startedAtNanos = System.nanoTime();

            try {
                long downloaded = downloadFromUriOnce(
                        entry,
                        sourceUri,
                        temporaryPath,
                        downloadedBeforeFile,
                        totalBytes,
                        listener);
                listener.onDownloadAttemptFinished(
                        entry,
                        sourceName,
                        attempt,
                        host,
                        200,
                        elapsedMillis(startedAtNanos),
                        "OK");
                return downloaded;
            } catch (DownloadAttemptException exception) {
                lastException = exception;
                listener.onDownloadAttemptFinished(
                        entry,
                        sourceName,
                        attempt,
                        host,
                        exception.getStatusCode(),
                        elapsedMillis(startedAtNanos),
                        exception.getMessage());
                deleteQuietly(temporaryPath);

                if (!exception.isRetryable() || attempt >= MAX_DOWNLOAD_ATTEMPTS) {
                    throw exception;
                }

                long delayMillis = exception.getRetryAfterDelayMillis();
                if (delayMillis < 0) {
                    delayMillis = computeRetryDelayMillis(attempt);
                }
                listener.onDownloadRetryScheduled(
                        entry,
                        sourceName,
                        attempt + 1,
                        MAX_DOWNLOAD_ATTEMPTS,
                        delayMillis,
                        exception.getMessage());
                sleepBeforeRetry(delayMillis, entry);
            } catch (DownloadException exception) {
                lastException = exception;
                listener.onDownloadAttemptFinished(
                        entry,
                        sourceName,
                        attempt,
                        host,
                        STATUS_NOT_AVAILABLE,
                        elapsedMillis(startedAtNanos),
                        exception.getMessage());
                deleteQuietly(temporaryPath);
                throw exception;
            }
        }

        throw lastException == null
                ? new DownloadException("Unable to download file: " + entry.getPath())
                : lastException;
    }

    private long downloadFromUriOnce(
            ManifestFileEntry entry,
            URI sourceUri,
            Path temporaryPath,
            long downloadedBeforeFile,
            long totalBytes,
            DownloadProgressListener listener
    ) {
        HttpRequest request = HttpRequest.newBuilder(sourceUri)
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (ConnectException exception) {
            throw new DownloadAttemptException(
                    "No internet connection or file server is unreachable.",
                    exception,
                    true,
                    STATUS_NOT_AVAILABLE);
        } catch (HttpTimeoutException exception) {
            throw new DownloadAttemptException(
                    "Download timed out for " + entry.getPath() + ".",
                    exception,
                    true,
                    STATUS_NOT_AVAILABLE);
        } catch (IOException exception) {
            throw new DownloadAttemptException(
                    "Network error while downloading " + entry.getPath() + ".",
                    exception,
                    true,
                    STATUS_NOT_AVAILABLE);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DownloadException("Download was interrupted: " + entry.getPath(), exception);
        }

        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            int statusCode = response.statusCode();
            throw new DownloadAttemptException(
                    "File server returned HTTP "
                            + statusCode
                            + " for "
                            + entry.getPath()
                            + ".",
                    isRetryableHttpStatus(statusCode, response),
                    statusCode,
                    getRetryAfterDelayMillis(statusCode, response));
        }

        try {
            Files.createDirectories(temporaryPath.getParent());
        } catch (IOException exception) {
            closeQuietly(response.body());
            throw new DownloadAttemptException(
                    "Unable to create directory for: " + entry.getPath(),
                    exception,
                    false,
                    STATUS_NOT_AVAILABLE);
        }

        long downloadedForFile = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream inputStream = response.body();
                OutputStream outputStream = Files.newOutputStream(temporaryPath)) {
            while (true) {
                int bytesRead;
                try {
                    bytesRead = inputStream.read(buffer);
                } catch (IOException exception) {
                    deleteQuietly(temporaryPath);
                    throw new DownloadAttemptException(
                            "Network error while reading " + entry.getPath() + ".",
                            exception,
                            true,
                            STATUS_NOT_AVAILABLE);
                }
                if (bytesRead == -1) {
                    break;
                }

                try {
                    outputStream.write(buffer, 0, bytesRead);
                } catch (IOException exception) {
                    deleteQuietly(temporaryPath);
                    throw new DownloadAttemptException(
                            "Unable to save downloaded file: " + entry.getPath(),
                            exception,
                            false,
                            STATUS_NOT_AVAILABLE);
                }
                downloadedForFile += bytesRead;
                listener.onProgress(downloadedBeforeFile + downloadedForFile, totalBytes);
            }
        } catch (DownloadAttemptException exception) {
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(temporaryPath);
            throw new DownloadAttemptException(
                    "Unable to save downloaded file: " + entry.getPath(),
                    exception,
                    false,
                    STATUS_NOT_AVAILABLE);
        }

        return downloadedForFile;
    }

    private URI createUri(String url, String manifestPath) {
        if (url == null || url.isBlank()) {
            throw new DownloadException("Download URL is missing for " + manifestPath + ".");
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (uri.getHost() == null
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                throw new DownloadException("Invalid download URL for " + manifestPath + ".");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new DownloadException("Invalid download URL for " + manifestPath + ".", exception);
        }
    }

    private void verifyDownloadedFile(ManifestFileEntry entry, Path temporaryPath) {
        try {
            long expectedSize = entry.getSize();
            long actualSize = Files.size(temporaryPath);
            if (expectedSize > 0 && actualSize != expectedSize) {
                deleteQuietly(temporaryPath);
                throw new DownloadException("Downloaded file size mismatch for "
                        + entry.getPath()
                        + ". Expected "
                        + expectedSize
                        + " but got "
                        + actualSize
                        + ".");
            }
        } catch (IOException exception) {
            deleteQuietly(temporaryPath);
            throw new DownloadException("Unable to inspect downloaded file: " + entry.getPath(), exception);
        }

        String expectedHash = FileHashService.normalizeSha256(entry.getSha256());
        String actualHash = fileHashService.calculateSha256(temporaryPath);

        if (!expectedHash.equals(actualHash)) {
            deleteQuietly(temporaryPath);
            throw new DownloadException("Downloaded file hash mismatch for "
                    + entry.getPath()
                    + ". Expected "
                    + expectedHash
                    + " but got "
                    + actualHash
                    + ".");
        }
    }

    private void replaceFinalFile(Path temporaryPath, Path finalPath) {
        try {
            Files.createDirectories(finalPath.getParent());
            try {
                Files.move(
                        temporaryPath,
                        finalPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                try {
                    Files.move(temporaryPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException fallbackException) {
                    fallbackException.addSuppressed(exception);
                    throw fallbackException;
                }
            }
        } catch (IOException exception) {
            deleteQuietly(temporaryPath);
            throw new DownloadException("Unable to replace local file: " + finalPath, exception);
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

    private void sleepBeforeRetry(long delayMillis, ManifestFileEntry entry) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DownloadException("Download retry was interrupted: " + entry.getPath(), exception);
        }
    }

    private void deleteStaleDownload(
            Path temporaryPath,
            ManifestFileEntry entry,
            DownloadProgressListener listener
    ) {
        try {
            if (Files.deleteIfExists(temporaryPath)) {
                listener.onTemporaryDownloadDeleted(entry, temporaryPath);
            }
        } catch (IOException exception) {
            throw new DownloadException("Unable to remove stale temporary download: " + temporaryPath, exception);
        }
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

    private String getSafeHost(URI uri) {
        String host = uri.getHost();
        return host == null || host.isBlank() ? "unknown" : host;
    }

    private long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    public interface DownloadProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);

        void onFileStarted(ManifestFileEntry entry);

        void onFileFinished(ManifestFileEntry entry);

        default void onSourceStarted(ManifestFileEntry entry, String sourceName) {
        }

        default void onSourceFailed(ManifestFileEntry entry, String sourceName, String reason) {
        }

        default void onDownloadAttempt(
                ManifestFileEntry entry,
                String sourceName,
                int attempt,
                int maxAttempts,
                String host
        ) {
        }

        default void onDownloadAttemptFinished(
                ManifestFileEntry entry,
                String sourceName,
                int attempt,
                String host,
                int httpStatus,
                long durationMillis,
                String result
        ) {
        }

        default void onDownloadRetryScheduled(
                ManifestFileEntry entry,
                String sourceName,
                int nextAttempt,
                int maxAttempts,
                long delayMillis,
                String reason
        ) {
        }

        default void onTemporaryDownloadDeleted(ManifestFileEntry entry, Path temporaryPath) {
        }
    }

    public static class DownloadException extends RuntimeException {
        public DownloadException(String message) {
            super(message);
        }

        public DownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class DownloadAttemptException extends DownloadException {
        private final boolean retryable;
        private final int statusCode;
        private final long retryAfterDelayMillis;

        private DownloadAttemptException(String message, boolean retryable, int statusCode) {
            this(message, retryable, statusCode, -1);
        }

        private DownloadAttemptException(
                String message,
                boolean retryable,
                int statusCode,
                long retryAfterDelayMillis
        ) {
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

        private boolean isRetryable() {
            return retryable;
        }

        private int getStatusCode() {
            return statusCode;
        }

        private long getRetryAfterDelayMillis() {
            return retryAfterDelayMillis;
        }
    }
}
