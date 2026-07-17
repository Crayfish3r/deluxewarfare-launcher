package com.makar.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class ManifestServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToYandexManifest() throws Exception {
        byte[] manifest = "{\"minecraftVersion\":\"1.20.1\",\"files\":[]}".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "manifest-test-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/primary", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/mirror", exchange -> {
            exchange.sendResponseHeaders(200, manifest.length);
            exchange.getResponseBody().write(manifest);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ManifestService service = new ManifestService(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                (publicUrl, filePath) -> URI.create(baseUrl + "/mirror"));
        List<String> statuses = new ArrayList<>();

        LauncherManifest result = service.downloadManifest(
                baseUrl + "/primary",
                "https://disk.yandex.ru/d/test",
                statuses::add);

        assertEquals("1.20.1", result.getMinecraftVersion());
        assertTrue(statuses.stream().anyMatch(status -> status.contains("Trying Yandex Disk")));
        assertTrue(statuses.stream().anyMatch(status -> status.contains("loaded from Yandex Disk")));
    }
}
