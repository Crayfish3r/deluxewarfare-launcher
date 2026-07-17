package com.makar.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DownloadServiceTest {
    @TempDir
    Path tempDirectory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToMirrorAndVerifiesDownloadedFile() throws Exception {
        byte[] expectedBytes = "mirror-content".getBytes();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "download-test-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/primary", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/mirror", exchange -> {
            exchange.sendResponseHeaders(200, expectedBytes.length);
            exchange.getResponseBody().write(expectedBytes);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ManifestFileEntry entry = new ManifestFileEntry();
        entry.setPath("mods/example.jar");
        entry.setUrl(baseUrl + "/primary");
        entry.setSize(expectedBytes.length);
        entry.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expectedBytes)));

        Path finalPath = tempDirectory.resolve("mods/example.jar");
        FileHashService.FileCheckResult check = new FileHashService.FileCheckResult(
                entry,
                finalPath,
                FileHashService.FileStatus.MISSING);
        List<String> events = new ArrayList<>();

        DownloadService service = new DownloadService(
                HttpClient.newBuilder().build(),
                new FileHashService(),
                (publicUrl, filePath) -> URI.create(baseUrl + "/mirror"));
        service.downloadMissingOrOutdatedFiles(
                List.of(check),
                "https://disk.yandex.ru/d/test",
                new RecordingListener(events));

        assertArrayEquals(expectedBytes, Files.readAllBytes(finalPath));
        assertTrue(events.stream().anyMatch(event -> event.startsWith("failed:GitHub:")));
        assertTrue(events.contains("started:Yandex Disk"));
        assertEquals(false, Files.exists(finalPath.resolveSibling("example.jar.download")));
    }

    private static final class RecordingListener implements DownloadService.DownloadProgressListener {
        private final List<String> events;

        private RecordingListener(List<String> events) {
            this.events = events;
        }

        @Override
        public void onProgress(long downloadedBytes, long totalBytes) {
        }

        @Override
        public void onFileStarted(ManifestFileEntry entry) {
        }

        @Override
        public void onFileFinished(ManifestFileEntry entry) {
        }

        @Override
        public void onSourceStarted(ManifestFileEntry entry, String sourceName) {
            events.add("started:" + sourceName);
        }

        @Override
        public void onSourceFailed(ManifestFileEntry entry, String sourceName, String reason) {
            events.add("failed:" + sourceName + ":" + reason);
        }
    }
}
