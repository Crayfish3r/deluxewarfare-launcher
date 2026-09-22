package com.makar.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class YandexDiskServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resolvesPublicFolderFileAndEncodesParameters() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "yandex-test-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/download", exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            assertEquals("https://disk.yandex.ru/d/test key", query.get("public_key"));
            assertEquals("/mods/file name+[1].jar", query.get("path"));
            byte[] body = "{\"href\":\"https://downloader.disk.yandex.ru/test\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        String apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/download";
        YandexDiskService service = new YandexDiskService(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                apiUrl);

        assertEquals(
                "https://downloader.disk.yandex.ru/test",
                service.resolveDownloadUri(
                        "https://disk.yandex.ru/d/test key",
                        "mods/file name+[1].jar").toString());
    }

    @Test
    void classifiesTemporaryApiStatus() throws Exception {
        YandexDiskService service = serviceReturningStatus(503);

        YandexDiskService.YandexDiskException exception = assertThrows(
                YandexDiskService.YandexDiskException.class,
                () -> service.resolveDownloadUri("https://disk.yandex.ru/d/test", "launcher/latest.json"));

        assertTrue(exception.isTemporaryFailure());
    }

    @Test
    void classifiesPermanentApiStatus() throws Exception {
        YandexDiskService service = serviceReturningStatus(404);

        YandexDiskService.YandexDiskException exception = assertThrows(
                YandexDiskService.YandexDiskException.class,
                () -> service.resolveDownloadUri("https://disk.yandex.ru/d/test", "launcher/latest.json"));

        assertFalse(exception.isTemporaryFailure());
    }

    private YandexDiskService serviceReturningStatus(int statusCode) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/download", exchange -> {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        });
        server.start();
        return new YandexDiskService(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/download");
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        return Arrays.stream(rawQuery.split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> decode(pair[0]),
                        pair -> decode(pair.length > 1 ? pair[1] : "")));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
