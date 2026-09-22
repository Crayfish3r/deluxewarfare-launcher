package com.makar.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class YandexDiskService implements MirrorDownloadResolver {
    private static final String PUBLIC_DOWNLOAD_API =
            "https://cloud-api.yandex.net/v1/disk/public/resources/download";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String publicDownloadApi;
    private final boolean allowHttpDownloadsForTests;

    public YandexDiskService() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), new ObjectMapper(), PUBLIC_DOWNLOAD_API, false);
    }

    YandexDiskService(HttpClient httpClient, ObjectMapper objectMapper, String publicDownloadApi) {
        this(httpClient, objectMapper, publicDownloadApi, false);
    }

    YandexDiskService(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String publicDownloadApi,
            boolean allowHttpDownloadsForTests
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.publicDownloadApi = publicDownloadApi;
        this.allowHttpDownloadsForTests = allowHttpDownloadsForTests;
    }

    @Override
    public URI resolveDownloadUri(String publicUrl, String filePath) {
        if (publicUrl == null || publicUrl.isBlank()) {
            throw new YandexDiskException("Yandex Disk public URL is not configured.");
        }

        String normalizedPath = normalizePath(filePath);
        String requestUrl = publicDownloadApi
                + "?public_key=" + encode(publicUrl.trim())
                + "&path=" + encode(normalizedPath);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(requestUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
        } catch (IllegalArgumentException exception) {
            throw new YandexDiskException("Unable to create Yandex Disk API request.", exception);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException exception) {
            throw new YandexDiskException("Yandex Disk API is unreachable.", exception, true);
        } catch (HttpTimeoutException exception) {
            throw new YandexDiskException("Yandex Disk API request timed out.", exception, true);
        } catch (IOException exception) {
            throw new YandexDiskException("Unable to request a Yandex Disk download link.", exception, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new YandexDiskException("Yandex Disk API request was interrupted.", exception);
        }

        if (response.statusCode() != 200) {
            throw new YandexDiskException("Yandex Disk API returned HTTP "
                    + response.statusCode()
                    + " for "
                    + normalizedPath
                    + ".",
                    isTemporaryStatus(response.statusCode()));
        }

        try {
            JsonNode root = objectMapper.readTree(response.body());
            String href = root.path("href").asText("").trim();
            if (href.isEmpty()) {
                throw new YandexDiskException("Yandex Disk API response does not contain a download URL.");
            }

            URI downloadUri = URI.create(href);
            boolean validScheme = "https".equalsIgnoreCase(downloadUri.getScheme())
                    || (allowHttpDownloadsForTests && "http".equalsIgnoreCase(downloadUri.getScheme()));
            if (downloadUri.getHost() == null || !validScheme) {
                throw new YandexDiskException("Yandex Disk API returned a non-HTTPS download URL.");
            }
            return downloadUri;
        } catch (YandexDiskException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new YandexDiskException("Yandex Disk API response is invalid.", exception);
        }
    }

    private String normalizePath(String filePath) {
        String normalized = filePath == null ? "" : filePath.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new YandexDiskException("Invalid Yandex Disk file path: " + filePath);
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new YandexDiskException("Invalid Yandex Disk file path: " + filePath);
            }
        }
        return "/" + normalized;
    }

    private boolean isTemporaryStatus(int statusCode) {
        return statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static final class YandexDiskException extends RuntimeException {
        private final boolean temporaryFailure;

        public YandexDiskException(String message) {
            super(message);
            this.temporaryFailure = false;
        }

        public YandexDiskException(String message, Throwable cause) {
            super(message, cause);
            this.temporaryFailure = false;
        }

        public YandexDiskException(String message, boolean temporaryFailure) {
            super(message);
            this.temporaryFailure = temporaryFailure;
        }

        public YandexDiskException(String message, Throwable cause, boolean temporaryFailure) {
            super(message, cause);
            this.temporaryFailure = temporaryFailure;
        }

        public boolean isTemporaryFailure() {
            return temporaryFailure;
        }
    }
}
