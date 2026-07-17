package com.makar.launcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

public final class ManifestService {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MirrorDownloadResolver mirrorDownloadResolver;

    public ManifestService() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(),
                new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
                new YandexDiskService());
    }

    ManifestService(HttpClient httpClient, ObjectMapper objectMapper, MirrorDownloadResolver mirrorDownloadResolver) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.mirrorDownloadResolver = mirrorDownloadResolver;
    }

    public LauncherManifest downloadManifest(String manifestUrl) {
        return downloadManifestFromUri(createUri(manifestUrl, "Manifest URL is invalid: " + manifestUrl));
    }

    public LauncherManifest downloadManifest(
            String manifestUrl,
            String yandexDiskPublicUrl,
            Consumer<String> statusConsumer
    ) {
        Consumer<String> status = statusConsumer == null ? ignored -> { } : statusConsumer;
        ManifestServiceException primaryFailure;

        try {
            return downloadManifest(manifestUrl);
        } catch (ManifestServiceException exception) {
            if (Thread.currentThread().isInterrupted() || yandexDiskPublicUrl == null || yandexDiskPublicUrl.isBlank()) {
                throw exception;
            }
            primaryFailure = exception;
            status.accept("Primary manifest source failed: " + exception.getMessage());
            status.accept("Trying Yandex Disk manifest mirror...");
        }

        try {
            URI mirrorUri = mirrorDownloadResolver.resolveDownloadUri(
                    yandexDiskPublicUrl,
                    "/launcher_manifest.json");
            LauncherManifest manifest = downloadManifestFromUri(mirrorUri);
            status.accept("Manifest loaded from Yandex Disk mirror.");
            return manifest;
        } catch (RuntimeException mirrorFailure) {
            ManifestServiceException combined = new ManifestServiceException(
                    "Unable to download manifest from GitHub or Yandex Disk. Primary: "
                            + primaryFailure.getMessage()
                            + " Mirror: "
                            + mirrorFailure.getMessage(),
                    mirrorFailure);
            combined.addSuppressed(primaryFailure);
            throw combined;
        }
    }

    private LauncherManifest downloadManifestFromUri(URI manifestUri) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(manifestUri)
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
        } catch (IllegalArgumentException exception) {
            throw new ManifestServiceException("Manifest URL is invalid: " + manifestUri, exception);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException exception) {
            throw new ManifestServiceException("No internet connection or server is unreachable.", exception);
        } catch (IOException exception) {
            throw new ManifestServiceException("Unable to download manifest. Check your internet connection.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ManifestServiceException("Manifest download was interrupted.", exception);
        }

        if (response.statusCode() != 200) {
            throw new ManifestServiceException("Manifest server returned HTTP " + response.statusCode() + ".");
        }

        try {
            return objectMapper.readValue(response.body(), LauncherManifest.class);
        } catch (JsonProcessingException exception) {
            throw new ManifestServiceException("Manifest JSON is invalid.", exception);
        }
    }

    private URI createUri(String url, String errorMessage) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new ManifestServiceException(errorMessage, exception);
        }
    }

    public static final class ManifestServiceException extends RuntimeException {
        public ManifestServiceException(String message) {
            super(message);
        }

        public ManifestServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
