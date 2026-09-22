package com.makar.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LauncherSelfUpdateServiceTest {
    private static final String YANDEX_PUBLIC_URL = "https://disk.yandex.ru/d/test";
    private static final String NOTES = "Test update";

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger yandexApiCalls = new AtomicInteger();
    private HttpServer server;
    private HttpClient httpClient;
    private LauncherSelfUpdateService service;
    private String baseUrl;
    private Response primaryMetadata;
    private Response yandexMetadata;
    private Response primaryInstaller;
    private Response yandexInstaller;
    private Response yandexApiFailure;
    private byte[] installerBytes;

    @BeforeEach
    void startServer() throws Exception {
        installerBytes = "verified launcher installer".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "launcher-self-update-test-http");
            thread.setDaemon(true);
            return thread;
        }));
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        primaryInstaller = response(200, installerBytes);
        yandexInstaller = response(200, installerBytes);
        primaryMetadata = response(200, metadataJson(
                "2.0.2",
                baseUrl + "/primary-installer",
                sha256(installerBytes),
                installerBytes.length));
        yandexMetadata = response(200, metadataJson(
                "2.0.2",
                "launcher/DeluxeWarfareLauncher-Setup-2.0.2.exe",
                sha256(installerBytes),
                installerBytes.length));

        server.createContext("/primary-metadata", exchange -> respond(exchange, primaryMetadata));
        server.createContext("/yandex-metadata", exchange -> respond(exchange, yandexMetadata));
        server.createContext("/primary-installer", exchange -> respond(exchange, primaryInstaller));
        server.createContext("/yandex-installer", exchange -> respond(exchange, yandexInstaller));
        server.createContext("/yandex-api", this::handleYandexApi);
        server.start();

        httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        YandexDiskService yandexDiskService = new YandexDiskService(
                httpClient,
                objectMapper,
                baseUrl + "/yandex-api",
                true);
        service = new LauncherSelfUpdateService(
                httpClient,
                objectMapper,
                yandexDiskService,
                "2.0.1",
                temporaryDirectory.resolve("updates"),
                true);
    }

    @AfterEach
    void stopServerAndClearInterrupt() {
        Thread.interrupted();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void primaryMetadataSuccessDoesNotCallYandex() {
        LauncherUpdateCandidate candidate = service.checkForUpdate(
                        baseUrl + "/primary-metadata",
                        YANDEX_PUBLIC_URL)
                .orElseThrow();

        assertEquals(LauncherUpdateSource.PRIMARY_HTTP, candidate.source());
        assertEquals("2.0.2", candidate.updateInfo().getVersion());
        assertEquals(0, yandexApiCalls.get());
    }

    @Test
    void temporaryPrimaryMetadataStatusUsesYandex() {
        primaryMetadata = response(503, "temporarily unavailable");
        List<String> messages = new ArrayList<>();

        LauncherUpdateCandidate candidate = service.checkForUpdate(
                        baseUrl + "/primary-metadata",
                        YANDEX_PUBLIC_URL,
                        messages::add)
                .orElseThrow();

        assertEquals(LauncherUpdateSource.YANDEX_DISK, candidate.source());
        assertEquals(1, yandexApiCalls.get());
        assertTrue(messages.contains("Primary launcher update metadata is temporarily unavailable."));
        assertTrue(messages.contains("Trying Yandex Disk launcher update metadata fallback."));
        assertTrue(messages.contains("Launcher update metadata loaded from Yandex Disk."));
    }

    @Test
    void primaryMetadataNetworkFailureUsesYandex() throws Exception {
        String unreachableUrl;
        try (ServerSocket socket = new ServerSocket(0)) {
            unreachableUrl = "http://127.0.0.1:" + socket.getLocalPort() + "/latest.json";
        }

        LauncherUpdateCandidate candidate = service.checkForUpdate(
                        unreachableUrl,
                        YANDEX_PUBLIC_URL)
                .orElseThrow();

        assertEquals(LauncherUpdateSource.YANDEX_DISK, candidate.source());
        assertEquals(1, yandexApiCalls.get());
    }

    @Test
    void malformedPrimaryMetadataDoesNotCallYandex() {
        primaryMetadata = response(200, "{not-json");

        LauncherSelfUpdateService.SelfUpdateException exception = assertThrows(
                LauncherSelfUpdateService.SelfUpdateException.class,
                () -> service.checkForUpdate(baseUrl + "/primary-metadata", YANDEX_PUBLIC_URL));

        assertFalse(exception.isTemporaryFailure());
        assertEquals(0, yandexApiCalls.get());
    }

    @Test
    void invalidPrimaryVersionDoesNotCallYandex() {
        primaryMetadata = response(200, metadataJson(
                "2.999999999999999999999.2",
                baseUrl + "/primary-installer",
                sha256(installerBytes),
                installerBytes.length));

        LauncherSelfUpdateService.SelfUpdateException exception = assertThrows(
                LauncherSelfUpdateService.SelfUpdateException.class,
                () -> service.checkForUpdate(baseUrl + "/primary-metadata", YANDEX_PUBLIC_URL));

        assertFalse(exception.isTemporaryFailure());
        assertEquals(0, yandexApiCalls.get());
    }

    @Test
    void missingPrimaryMetadataReturnsEmptyWithoutYandex() {
        primaryMetadata = response(404, "missing");

        assertTrue(service.checkForUpdate(
                baseUrl + "/primary-metadata",
                YANDEX_PUBLIC_URL).isEmpty());
        assertEquals(0, yandexApiCalls.get());
    }

    @Test
    void forbiddenPrimaryMetadataDoesNotCallYandex() {
        primaryMetadata = response(403, "forbidden");

        LauncherSelfUpdateService.SelfUpdateException exception = assertThrows(
                LauncherSelfUpdateService.SelfUpdateException.class,
                () -> service.checkForUpdate(baseUrl + "/primary-metadata", YANDEX_PUBLIC_URL));

        assertFalse(exception.isTemporaryFailure());
        assertEquals(0, yandexApiCalls.get());
    }

    @Test
    void temporaryPrimaryInstallerStatusUsesYandexInstaller() throws Exception {
        primaryInstaller = response(503, "temporarily unavailable");

        Path installer = service.downloadInstaller(
                primaryCandidate(),
                YANDEX_PUBLIC_URL,
                ignored -> { });

        assertArrayEquals(installerBytes, Files.readAllBytes(installer));
        assertEquals(2, yandexApiCalls.get());
    }

    @Test
    void missingPrimaryInstallerDoesNotUseFallback() {
        primaryInstaller = response(404, "missing");

        LauncherSelfUpdateService.SelfUpdateException exception = assertThrows(
                LauncherSelfUpdateService.SelfUpdateException.class,
                () -> service.downloadInstaller(primaryCandidate(), YANDEX_PUBLIC_URL, ignored -> { }));

        assertFalse(exception.isTemporaryFailure());
        assertEquals(0, yandexApiCalls.get());
    }

    @Test
    void primaryInstallerHashMismatchDoesNotUseFallback() {
        LauncherUpdateInfo invalidHash = primaryInfo();
        invalidHash.setSha256("0".repeat(64));

        LauncherSelfUpdateService.SelfUpdateException exception = assertThrows(
                LauncherSelfUpdateService.SelfUpdateException.class,
                () -> service.downloadInstaller(
                        new LauncherUpdateCandidate(invalidHash, LauncherUpdateSource.PRIMARY_HTTP),
                        YANDEX_PUBLIC_URL,
                        ignored -> { }));

        assertFalse(exception.isTemporaryFailure());
        assertEquals(0, yandexApiCalls.get());
    }

    @Test
    void installerFallbackRejectsVersionMismatch() {
        primaryInstaller = response(503, "temporarily unavailable");
        yandexMetadata = response(200, metadataJson(
                "2.0.3",
                "launcher/DeluxeWarfareLauncher-Setup-2.0.3.exe",
                sha256(installerBytes),
                installerBytes.length));

        LauncherSelfUpdateService.SelfUpdateException exception = assertThrows(
                LauncherSelfUpdateService.SelfUpdateException.class,
                () -> service.downloadInstaller(primaryCandidate(), YANDEX_PUBLIC_URL, ignored -> { }));

        assertEquals("Launcher update mirror metadata mismatch.", exception.getMessage());
        assertEquals(1, yandexApiCalls.get());
    }

    @Test
    void installerFallbackRejectsSha256Mismatch() {
        primaryInstaller = response(503, "temporarily unavailable");
        yandexMetadata = response(200, metadataJson(
                "2.0.2",
                "launcher/DeluxeWarfareLauncher-Setup-2.0.2.exe",
                "a".repeat(64),
                installerBytes.length));

        LauncherSelfUpdateService.SelfUpdateException exception = assertThrows(
                LauncherSelfUpdateService.SelfUpdateException.class,
                () -> service.downloadInstaller(primaryCandidate(), YANDEX_PUBLIC_URL, ignored -> { }));

        assertEquals("Launcher update mirror metadata mismatch.", exception.getMessage());
        assertEquals(1, yandexApiCalls.get());
    }

    @Test
    void installerFallbackAllowsOnlyUrlToDiffer() throws Exception {
        primaryInstaller = response(503, "temporarily unavailable");

        Path installer = service.downloadInstaller(
                primaryCandidate(),
                YANDEX_PUBLIC_URL,
                ignored -> { });

        assertArrayEquals(installerBytes, Files.readAllBytes(installer));
        assertEquals(2, yandexApiCalls.get());
    }

    @Test
    void yandexInstallerIsVerifiedBySizeAndSha256() {
        byte[] corrupted = "corrupted launcher installer".getBytes(StandardCharsets.UTF_8);
        yandexInstaller = response(200, corrupted);

        LauncherSelfUpdateService.SelfUpdateException exception = assertThrows(
                LauncherSelfUpdateService.SelfUpdateException.class,
                () -> service.downloadInstaller(
                        new LauncherUpdateCandidate(yandexInfo(), LauncherUpdateSource.YANDEX_DISK),
                        YANDEX_PUBLIC_URL,
                        ignored -> { }));

        assertTrue(exception.getMessage().contains("size mismatch")
                || exception.getMessage().contains("sha256 mismatch"));
        assertFalse(Files.exists(installerPath()));
    }

    @Test
    void encodedYandexTraversalPathIsRejected() {
        LauncherUpdateInfo updateInfo = yandexInfo();
        updateInfo.setUrl("launcher/%2e%2e/setup.exe");

        LauncherSelfUpdateService.SelfUpdateException exception = assertThrows(
                LauncherSelfUpdateService.SelfUpdateException.class,
                () -> service.downloadInstaller(
                        new LauncherUpdateCandidate(updateInfo, LauncherUpdateSource.YANDEX_DISK),
                        YANDEX_PUBLIC_URL,
                        ignored -> { }));

        assertFalse(exception.isTemporaryFailure());
        assertEquals(0, yandexApiCalls.get());
    }

    @Test
    void stalePrimaryDownloadIsRemovedBeforeFallback() throws Exception {
        Files.createDirectories(installerPath().getParent());
        Path temporaryPath = Path.of(installerPath() + ".download");
        Files.writeString(temporaryPath, "stale");
        primaryInstaller = response(503, "temporarily unavailable");

        Path installer = service.downloadInstaller(
                primaryCandidate(),
                YANDEX_PUBLIC_URL,
                ignored -> { });

        assertFalse(Files.exists(temporaryPath));
        assertArrayEquals(installerBytes, Files.readAllBytes(installer));
    }

    @Test
    void bothTemporaryMetadataFailuresRemainTemporary() {
        primaryMetadata = response(503, "temporarily unavailable");
        yandexApiFailure = response(503, "temporarily unavailable");

        LauncherSelfUpdateService.SelfUpdateException exception = assertThrows(
                LauncherSelfUpdateService.SelfUpdateException.class,
                () -> service.checkForUpdate(baseUrl + "/primary-metadata", YANDEX_PUBLIC_URL));

        assertTrue(exception.isTemporaryFailure());
        assertEquals(1, yandexApiCalls.get());
    }

    @Test
    void interruptionRestoresFlagAndDoesNotUseFallback() {
        Thread.currentThread().interrupt();

        LauncherSelfUpdateService.SelfUpdateException exception = assertThrows(
                LauncherSelfUpdateService.SelfUpdateException.class,
                () -> service.checkForUpdate(baseUrl + "/primary-metadata", YANDEX_PUBLIC_URL));

        assertFalse(exception.isTemporaryFailure());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(0, yandexApiCalls.get());
    }

    private LauncherUpdateCandidate primaryCandidate() {
        return new LauncherUpdateCandidate(primaryInfo(), LauncherUpdateSource.PRIMARY_HTTP);
    }

    private LauncherUpdateInfo primaryInfo() {
        return updateInfo(baseUrl + "/primary-installer");
    }

    private LauncherUpdateInfo yandexInfo() {
        return updateInfo("launcher/DeluxeWarfareLauncher-Setup-2.0.2.exe");
    }

    private LauncherUpdateInfo updateInfo(String url) {
        LauncherUpdateInfo updateInfo = new LauncherUpdateInfo();
        updateInfo.setVersion("2.0.2");
        updateInfo.setUrl(url);
        updateInfo.setSha256(sha256(installerBytes));
        updateInfo.setSize(installerBytes.length);
        updateInfo.setMandatory(true);
        updateInfo.setNotes(NOTES);
        return updateInfo;
    }

    private Path installerPath() {
        return temporaryDirectory.resolve("updates/DeluxeWarfareLauncher-Setup-2.0.2.exe");
    }

    private void handleYandexApi(HttpExchange exchange) throws IOException {
        yandexApiCalls.incrementAndGet();
        if (yandexApiFailure != null) {
            respond(exchange, yandexApiFailure);
            return;
        }

        String path = queryParameter(exchange.getRequestURI(), "path");
        String href = path.endsWith("/latest.json")
                ? baseUrl + "/yandex-metadata"
                : baseUrl + "/yandex-installer";
        respond(exchange, response(200, "{\"href\":\"" + href + "\"}"));
    }

    private String queryParameter(URI uri, String name) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null) {
            return "";
        }
        for (String part : rawQuery.split("&")) {
            String[] pair = part.split("=", 2);
            if (name.equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
                return pair.length == 1
                        ? ""
                        : URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private String metadataJson(String version, String url, String sha256, long size) {
        return """
                {
                  "version": "%s",
                  "url": "%s",
                  "sha256": "%s",
                  "size": %d,
                  "mandatory": true,
                  "notes": "%s"
                }
                """.formatted(version, url, sha256, size, NOTES);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Response response(int statusCode, String body) {
        return response(statusCode, body.getBytes(StandardCharsets.UTF_8));
    }

    private Response response(int statusCode, byte[] body) {
        return new Response(statusCode, body);
    }

    private void respond(HttpExchange exchange, Response response) throws IOException {
        exchange.sendResponseHeaders(response.statusCode(), response.body().length);
        exchange.getResponseBody().write(response.body());
        exchange.close();
    }

    private record Response(int statusCode, byte[] body) {
    }
}
