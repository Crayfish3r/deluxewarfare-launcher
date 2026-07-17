package com.makar.launcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public final class DonationShopService {
    private static final String BUNDLED_PRODUCTS_RESOURCE = "/donation_products.json";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DonationShopService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public List<DonationProduct> loadProducts(String productsUrl) {
        try {
            if (productsUrl != null && !productsUrl.isBlank()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(productsUrl.trim()))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parseEnabledProducts(response.body());
                }
            }
        } catch (RuntimeException | IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        return loadBundledProducts();
    }

    private List<DonationProduct> loadBundledProducts() {
        try (InputStream inputStream = DonationShopService.class.getResourceAsStream(BUNDLED_PRODUCTS_RESOURCE)) {
            if (inputStream == null) {
                return List.of();
            }
            return parseEnabledProducts(inputStream.readAllBytes());
        } catch (IOException exception) {
            return List.of();
        }
    }

    private List<DonationProduct> parseEnabledProducts(String json) throws IOException {
        return parseEnabledProducts(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private List<DonationProduct> parseEnabledProducts(byte[] json) throws IOException {
        List<DonationProduct> products = objectMapper.readValue(json, new TypeReference<>() {
        });
        return products.stream()
                .filter(DonationProduct::isEnabled)
                .filter(product -> !product.getCode().isBlank())
                .filter(product -> !product.getTitle().isBlank())
                .toList();
    }
}
