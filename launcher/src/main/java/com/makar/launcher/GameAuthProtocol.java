package com.makar.launcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class GameAuthProtocol {
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern CHALLENGE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43,128}$");

    private GameAuthProtocol() { }

    public static String canonicalPayload(ProofFields proof) {
        requireSafe("launchSessionId", proof.launchSessionId(), 128);
        requireSafe("keyId", proof.keyId(), 128);
        requireSafe("serverId", proof.serverId(), 128);
        requireSafe("nickname", proof.nickname(), 16);
        requireSafe("challenge", proof.challenge(), 128);
        String playerUuid = proof.playerUuid().toLowerCase(Locale.ROOT);
        if (!UUID_PATTERN.matcher(playerUuid).matches()) {
            throw new IllegalArgumentException("playerUuid is invalid.");
        }
        if (!CHALLENGE_PATTERN.matcher(proof.challenge()).matches()) {
            throw new IllegalArgumentException("challenge is invalid.");
        }
        if (proof.expiresAt() <= 0) {
            throw new IllegalArgumentException("expiresAt is invalid.");
        }
        return "DW-AUTH-V1\n"
                + "launchSessionId=" + proof.launchSessionId() + "\n"
                + "keyId=" + proof.keyId() + "\n"
                + "serverId=" + proof.serverId() + "\n"
                + "serverAddress=" + canonicalizeServerAddress(proof.serverAddress()) + "\n"
                + "playerUuid=" + playerUuid + "\n"
                + "nickname=" + proof.nickname() + "\n"
                + "challenge=" + proof.challenge() + "\n"
                + "expiresAt=" + proof.expiresAt() + "\n";
    }

    public static String canonicalizeServerAddress(String value) {
        requireSafe("serverAddress", value, 255);
        String input = value.trim().toLowerCase(Locale.ROOT);
        String host;
        int port = 25565;
        if (input.startsWith("[")) {
            int closing = input.indexOf(']');
            if (closing < 2) throw new IllegalArgumentException("serverAddress is invalid.");
            host = input.substring(0, closing + 1);
            String suffix = input.substring(closing + 1);
            if (!suffix.isEmpty()) port = parsePort(suffix.startsWith(":") ? suffix.substring(1) : "");
        } else {
            long colons = input.chars().filter(character -> character == ':').count();
            if (colons == 1) {
                int separator = input.lastIndexOf(':');
                host = input.substring(0, separator);
                port = parsePort(input.substring(separator + 1));
            } else if (colons > 1) {
                host = "[" + input + "]";
            } else {
                host = input;
            }
        }
        if (host.endsWith(".") && !host.startsWith("[")) host = host.substring(0, host.length() - 1);
        if (host.isBlank() || (!host.startsWith("[") && !host.matches("[a-z0-9.-]+"))) {
            throw new IllegalArgumentException("serverAddress host is invalid.");
        }
        return port == 25565 ? host : host + ":" + port;
    }

    public static String offlinePlayerUuid(String nickname) {
        requireSafe("nickname", nickname, 16);
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + nickname).getBytes(StandardCharsets.UTF_8))
                .toString().toLowerCase(Locale.ROOT);
    }

    public static String keyId(byte[] spki) {
        try {
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(spki));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public static void requireSafe(String name, String value, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " is invalid.");
        }
    }

    private static int parsePort(String text) {
        if (!text.matches("[0-9]{1,5}")) throw new IllegalArgumentException("serverAddress port is invalid.");
        int port = Integer.parseInt(text);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("serverAddress port is invalid.");
        return port;
    }

    public record ProofFields(
            String launchSessionId,
            String keyId,
            String serverId,
            String serverAddress,
            String playerUuid,
            String nickname,
            String challenge,
            long expiresAt
    ) { }
}
