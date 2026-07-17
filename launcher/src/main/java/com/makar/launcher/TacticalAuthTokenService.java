package com.makar.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TacticalAuthTokenService {
    private static final String CONFIG_DIRECTORY_NAME = "config";
    private static final String TOKEN_FILE_NAME = "tactical_auth_token.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Path writeToken(
            Path gameDirectory,
            String nickname,
            String token,
            String backendUrl,
            long expiresAt
    ) {
        try {
            Path configDirectory = Files.createDirectories(gameDirectory.resolve(CONFIG_DIRECTORY_NAME));
            Path tokenPath = configDirectory.resolve(TOKEN_FILE_NAME);
            Path temporaryPath = configDirectory.resolve(TOKEN_FILE_NAME + ".tmp");

            AuthTokenFile tokenFile = new AuthTokenFile(nickname, token, backendUrl, expiresAt);
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tokenFile);

            Files.writeString(temporaryPath, json, StandardCharsets.UTF_8);
            moveReplacingExisting(temporaryPath, tokenPath);

            return tokenPath;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write tactical auth token file.", exception);
        }
    }

    private void moveReplacingExisting(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveException) {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record AuthTokenFile(
            String nickname,
            String token,
            String backendUrl,
            long expiresAt
    ) {
    }
}
