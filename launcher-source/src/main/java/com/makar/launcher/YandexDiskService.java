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
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class YandexDiskService implements MirrorDownloadResolver {
    private static final String PUBLIC_DOWNLOAD_API =
            "https://cloud-api.yandex.net/v1/disk/public/resources/download";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String publicDownloadApi;

    public YandexDiskService() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), new ObjectMapper(), PUBLIC_DOWNLOAD_API);
    }

    YandexDiskService(HttpClient httpClient, ObjectMapper objectMapper, String publicDownloadApi) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.publicDownloadApi = publicDownloadApi;
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
            throw new YandexDiskException("Yandex Disk API is unreachable.", exception);
        } catch (IOException exception) {
            throw new YandexDiskException("Unable to request a Yandex Disk download link.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new YandexDiskException("Yandex Disk API request was interrupted.", exception);
        }

        if (response.statusCode() != 200) {
            throw new YandexDiskException("Yandex Disk API returned HTTP "
                    + response.statusCode()
                    + " for "
                    + normalizedPath
                    + ".");
        }

        try {
            JsonNode root = objectMapper.readTree(response.body());
            String href = root.path("href").asText("").trim();
            if (href.isEmpty()) {
                throw new YandexDiskException("Yandex Disk API response does not contain a download URL.");
            }

            URI downloadUri = URI.create(href);
            if (!"https".equalsIgnoreCase(downloadUri.getScheme())) {
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
        if (normalized.isBlank() || normalized.contains("../") || normalized.equals("..")) {
            throw new YandexDiskException("Invalid Yandex Disk file path: " + filePath);
        }
        return "/" + normalized;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static final class YandexDiskException extends RuntimeException {
        public YandexDiskException(String message) {
            super(message);
        }

        public YandexDiskException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
