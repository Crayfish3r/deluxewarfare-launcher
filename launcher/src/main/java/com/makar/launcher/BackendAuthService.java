package com.makar.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLException;

public final class BackendAuthService {
    private final String backendUrl;
    private final String backendHost;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BackendAuthService(String backendUrl) {
        this.backendUrl = normalizeBackendUrl(backendUrl);
        this.backendHost = URI.create(this.backendUrl).getHost();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public URI getDiscordLoginUri() {
        return URI.create(backendUrl + "/auth/discord/login");
    }

    public String getBackendUrl() {
        return backendUrl;
    }

    public LauncherCodeExchangeResponse exchangeLauncherCode(String code) {
        return postJson(
                "/exchange-launcher-code",
                Map.of("code", code),
                "",
                LauncherCodeExchangeResponse.class,
                "Unable to exchange launcher code."
        );
    }

    public LauncherSessionInfoResponse getLauncherSessionInfo(String launcherSessionToken) {
        return getJson(
                "/session/me",
                launcherSessionToken,
                LauncherSessionInfoResponse.class,
                "Unable to validate launcher session."
        );
    }

    public BindNicknameResponse bindNickname(String launcherSessionToken, String nickname) {
        return postJson(
                "/profile/bind-nickname",
                Map.of("nickname", nickname),
                launcherSessionToken,
                BindNicknameResponse.class,
                "Unable to bind Minecraft nickname."
        );
    }

    public GameTokenResponse requestGameToken(String launcherSessionToken, String nickname) {
        return postJson(
                "/game-token",
                Map.of("nickname", nickname),
                launcherSessionToken,
                GameTokenResponse.class,
                "Unable to request game token."
        );
    }

    public ModerationReportResponse reportIntegrityViolation(
            String launcherSessionToken,
            String nickname,
            String modpackVersion,
            String launcherVersion,
            List<String> unknownFiles
    ) {
        return reportIntegrityViolation(
                launcherSessionToken,
                nickname,
                modpackVersion,
                launcherVersion,
                "pre-launch",
                unknownFiles,
                List.of()
        );
    }

    public DonationRequestResponse submitDonationRequest(
            String launcherSessionToken,
            String playerName,
            String discordId,
            String discordUsername,
            DonationProduct product,
            String donationComment
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playerName", playerName);
        payload.put("discordId", discordId);
        payload.put("discordUsername", discordUsername);
        payload.put("productCode", product.getCode());
        payload.put("productId", product.getId());
        payload.put("productTitle", product.getTitle());
        payload.put("price", product.getPrice());
        payload.put("currency", product.getCurrency());
        payload.put("donationComment", donationComment);
        payload.put("status", "PENDING_REVIEW");
        payload.put("createdAt", java.time.Instant.now().toString());

        return postJson(
                "/donation/request",
                payload,
                launcherSessionToken,
                DonationRequestResponse.class,
                "Unable to send donation request."
        );
    }

    public ModerationReportResponse reportIntegrityViolation(
            String launcherSessionToken,
            String nickname,
            String modpackVersion,
            String launcherVersion,
            String phase,
            List<String> unknownFiles,
            List<RuntimeManagedFolderProtectionService.DetectedFile> detectedFiles
    ) {
        List<Map<String, Object>> detectedFilePayload = new ArrayList<>();
        for (RuntimeManagedFolderProtectionService.DetectedFile detectedFile : detectedFiles) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", detectedFile.path());
            entry.put("directory", detectedFile.directory());
            entry.put("fileName", detectedFile.fileName());
            entry.put("size", detectedFile.size());
            entry.put("sha256", detectedFile.sha256());
            detectedFilePayload.add(entry);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nickname", nickname);
        payload.put("modpackVersion", modpackVersion);
        payload.put("launcherVersion", launcherVersion);
        payload.put("phase", phase);
        payload.put("unknownFiles", unknownFiles);
        payload.put("detectedFiles", detectedFilePayload);

        return postJson(
                "/moderation/integrity-report",
                payload,
                launcherSessionToken,
                ModerationReportResponse.class,
                "Unable to send moderation alert."
        );
    }

    private <T> T postJson(
            String path,
            Object requestBody,
            String bearerToken,
            Class<T> responseType,
            String failureMessage
    ) {
        Instant startedAt = Instant.now();
        try {
            String body = objectMapper.writeValueAsString(requestBody);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(backendUrl + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            if (bearerToken != null && !bearerToken.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + bearerToken);
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BackendAuthException(readBackendError(response.statusCode(), response.body(), startedAt));
            }

            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException exception) {
            throw new BackendAuthException(formatNetworkFailure(failureMessage, exception, startedAt), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BackendAuthException(failureMessage + " Request was interrupted.", exception);
        }
    }

    private <T> T getJson(
            String path,
            String bearerToken,
            Class<T> responseType,
            String failureMessage
    ) {
        Instant startedAt = Instant.now();
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(backendUrl + path))
                    .timeout(Duration.ofSeconds(20))
                    .GET();

            if (bearerToken != null && !bearerToken.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + bearerToken);
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BackendAuthException(readBackendError(response.statusCode(), response.body(), startedAt));
            }

            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException exception) {
            throw new BackendAuthException(formatNetworkFailure(failureMessage, exception, startedAt), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BackendAuthException(failureMessage + " Request was interrupted.", exception);
        }
    }

    private String readBackendError(int statusCode, String responseBody, Instant startedAt) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String error = root.path("error").asText("");
            String message = root.path("message").asText("");
            if (!error.isBlank() && !message.isBlank()) {
                return "Backend HTTP " + statusCode + " | " + error + ": " + message
                        + " (" + backendHost + ", " + elapsedMillis(startedAt) + " ms)";
            }

            if (!message.isBlank()) {
                return "Backend HTTP " + statusCode + ": " + message
                        + " (" + backendHost + ", " + elapsedMillis(startedAt) + " ms)";
            }

            if (!error.isBlank()) {
                return "Backend HTTP " + statusCode + " | " + error
                        + " (" + backendHost + ", " + elapsedMillis(startedAt) + " ms)";
            }
        } catch (IOException ignored) {
        }

        return "Backend HTTP " + statusCode + ": " + responseBody
                + " (" + backendHost + ", " + elapsedMillis(startedAt) + " ms)";
    }

    private String formatNetworkFailure(String failureMessage, IOException exception, Instant startedAt) {
        return failureMessage
                + " " + describeNetworkFailure(exception)
                + " Backend: " + backendHost
                + ", duration: " + elapsedMillis(startedAt) + " ms.";
    }

    private String describeNetworkFailure(IOException exception) {
        Throwable cause = rootCause(exception);
        if (exception instanceof HttpTimeoutException || cause instanceof HttpTimeoutException) {
            return "Таймаут подключения к серверу авторизации. Проверьте VPN, фаервол или сеть.";
        }
        if (exception instanceof UnknownHostException || cause instanceof UnknownHostException) {
            return "Не удалось найти сервер авторизации по DNS. Проверьте интернет, DNS или VPN.";
        }
        if (exception instanceof ConnectException || cause instanceof ConnectException) {
            return "Не удалось подключиться к серверу авторизации. Проверьте VPN, фаервол или сеть.";
        }
        if (exception instanceof SSLException || cause instanceof SSLException) {
            return "Ошибка защищенного соединения с сервером авторизации. Проверьте дату/время Windows и антивирус.";
        }

        String details = cause.getMessage();
        if (details == null || details.isBlank()) {
            details = cause.getClass().getSimpleName();
        }
        return "Сетевая ошибка авторизации: " + details + ".";
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private long elapsedMillis(Instant startedAt) {
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    private static String normalizeBackendUrl(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:3000";
        }

        String normalized = value.trim();
        while (normalized.endsWith("/") && normalized.length() > "https://".length()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    public static final class LauncherCodeExchangeResponse {
        private boolean ok;
        private String launcherSessionToken = "";
        private long expiresAt;
        private DiscordUser user = new DiscordUser();

        public boolean isOk() {
            return ok;
        }

        public void setOk(boolean ok) {
            this.ok = ok;
        }

        public String getLauncherSessionToken() {
            return launcherSessionToken;
        }

        public void setLauncherSessionToken(String launcherSessionToken) {
            this.launcherSessionToken = launcherSessionToken == null ? "" : launcherSessionToken;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(long expiresAt) {
            this.expiresAt = expiresAt;
        }

        public DiscordUser getUser() {
            return user;
        }

        public void setUser(DiscordUser user) {
            this.user = user == null ? new DiscordUser() : user;
        }
    }

    public static final class LauncherSessionInfoResponse {
        private boolean ok;
        private long expiresAt;
        private DiscordUser user = new DiscordUser();

        public boolean isOk() {
            return ok;
        }

        public void setOk(boolean ok) {
            this.ok = ok;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(long expiresAt) {
            this.expiresAt = expiresAt;
        }

        public DiscordUser getUser() {
            return user;
        }

        public void setUser(DiscordUser user) {
            this.user = user == null ? new DiscordUser() : user;
        }
    }

    public static final class DiscordUser {
        private String id = "";
        private String discordId = "";
        private String username = "";
        private String avatar = "";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id == null ? "" : id;
        }

        public String getDiscordId() {
            return discordId;
        }

        public void setDiscordId(String discordId) {
            this.discordId = discordId == null ? "" : discordId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username == null ? "" : username;
        }

        public String getAvatar() {
            return avatar;
        }

        public void setAvatar(String avatar) {
            this.avatar = avatar == null ? "" : avatar;
        }
    }

    public static final class BindNicknameResponse {
        private boolean ok;
        private boolean alreadyBound;
        private MinecraftProfile profile = new MinecraftProfile();

        public boolean isOk() {
            return ok;
        }

        public void setOk(boolean ok) {
            this.ok = ok;
        }

        public boolean isAlreadyBound() {
            return alreadyBound;
        }

        public void setAlreadyBound(boolean alreadyBound) {
            this.alreadyBound = alreadyBound;
        }

        public MinecraftProfile getProfile() {
            return profile;
        }

        public void setProfile(MinecraftProfile profile) {
            this.profile = profile == null ? new MinecraftProfile() : profile;
        }
    }

    public static final class MinecraftProfile {
        private String id = "";
        private String nickname = "";
        private String userId = "";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id == null ? "" : id;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname == null ? "" : nickname;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId == null ? "" : userId;
        }
    }

    public static final class GameTokenResponse {
        private boolean ok;
        private String token = "";
        private String nickname = "";
        private long expiresAt;

        public boolean isOk() {
            return ok;
        }

        public void setOk(boolean ok) {
            this.ok = ok;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token == null ? "" : token;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname == null ? "" : nickname;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }

    public static final class ModerationReportResponse {
        private boolean ok;

        public boolean isOk() {
            return ok;
        }

        public void setOk(boolean ok) {
            this.ok = ok;
        }
    }

    public static final class DonationRequestResponse {
        private boolean ok;

        public boolean isOk() {
            return ok;
        }

        public void setOk(boolean ok) {
            this.ok = ok;
        }
    }

    public static final class BackendAuthException extends RuntimeException {
        public BackendAuthException(String message) {
            super(message);
        }

        public BackendAuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
