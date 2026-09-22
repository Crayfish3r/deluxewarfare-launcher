package com.makar.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameAuthProtocolTest {
    private static final String SESSION = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String KEY_ID = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    private static final String CHALLENGE = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";

    @Test
    void goldenVectorUsesExactPayloadAndJavaVerifiesDerSignature() throws Exception {
        GameAuthProtocol.ProofFields fields = fields(SESSION, CHALLENGE, 1786662000L);
        String expected = "DW-AUTH-V1\nlaunchSessionId=" + SESSION
                + "\nkeyId=" + KEY_ID
                + "\nserverId=deluxewarfare-main"
                + "\nserverAddress=play.deluxe-warfare.ru"
                + "\nplayerUuid=12345678-1234-3234-9234-123456789abc"
                + "\nnickname=ZumaDeluxe\nchallenge=" + CHALLENGE
                + "\nexpiresAt=1786662000\n";
        assertEquals(expected, GameAuthProtocol.canonicalPayload(fields));

        byte[] publicSpki = Base64.getDecoder().decode(
                "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEPFjb410bj0zxqHQaZ3ujMjr7rrAmYiCYN4drvm1xk+wT3qnZiTlD5mDL75satkDJn3mlyxEmz7a9/xfbcfR1+A==");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new java.security.spec.X509EncodedKeySpec(publicSpki)));
        verifier.update(expected.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(
                "MEUCIGimWGBZ7j4hc4CSBPkNHJIMhPtUgrz346myfycyJym/AiEAhXNbubU7LaThhvo/c81qyK48q8h98Pjyf3/4NUuOMC0=")));
    }

    @Test
    void signingPolicyRejectsWrongBindingAndSecondInitialChallenge() throws Exception {
        PrivateKey privateKey = testPrivateKey();
        long expiresAt = java.time.Instant.now().getEpochSecond() + 60;
        GameAuthProtocol.ProofFields initial = fields(SESSION, CHALLENGE, expiresAt);
        WindowsAuthSigningBroker.SigningPolicy policy = new WindowsAuthSigningBroker.SigningPolicy(privateKey, initial);
        assertFalse(policy.sign(initial).isBlank());
        assertThrows(IllegalArgumentException.class,
                () -> policy.sign(fields(SESSION, "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD", expiresAt)));
        GameAuthProtocol.ProofFields wrongServer = new GameAuthProtocol.ProofFields(
                "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR", KEY_ID, "other-server",
                "play.deluxe-warfare.ru", "12345678-1234-3234-9234-123456789abc",
                "ZumaDeluxe", "EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE", expiresAt);
        assertThrows(IllegalArgumentException.class, () -> policy.sign(wrongServer));
        policy.destroy();
    }

    @Test
    void signingPolicyAcceptsThreeHourReconnectButRejectsLongerExpiry() throws Exception {
        long now = java.time.Instant.now().getEpochSecond();
        GameAuthProtocol.ProofFields initial = fields(SESSION, CHALLENGE, now + 600);
        WindowsAuthSigningBroker.SigningPolicy policy = new WindowsAuthSigningBroker.SigningPolicy(
                testPrivateKey(), initial);
        assertFalse(policy.sign(initial).isBlank());

        GameAuthProtocol.ProofFields reconnect = fields(
                "RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR",
                "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD",
                now + 10_800);
        assertFalse(policy.sign(reconnect).isBlank());

        GameAuthProtocol.ProofFields tooLong = fields(
                "SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS",
                "EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE",
                now + 14_400);
        assertThrows(IllegalArgumentException.class, () -> policy.sign(tooLong));
        policy.destroy();
    }

    @Test
    void descriptorContainsNoPrivateKeyTokenOrIpcSecret(@TempDir Path gameDirectory) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/game-auth/launch-sessions", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            byte[] spki = Base64.getDecoder().decode(request.path("publicKeySpki").asText());
            String response = mapper.writeValueAsString(java.util.Map.of(
                    "launchSessionId", SESSION,
                    "keyId", GameAuthProtocol.keyId(spki),
                    "serverId", "deluxewarfare-main",
                    "serverAddress", "play.deluxe-warfare.ru",
                    "expiresAt", java.time.Instant.now().getEpochSecond() + 45));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            BackendAuthService backend = new BackendAuthService("http://127.0.0.1:" + server.getAddress().getPort());
            TacticalAuthTokenService.PreparedAuth prepared = new TacticalAuthTokenService().prepare(
                    gameDirectory, backend, "launcher-session", "ZumaDeluxe", "deluxewarfare-main",
                    "play.deluxe-warfare.ru:25565", GameAuthProtocol.offlinePlayerUuid("ZumaDeluxe"), "test-build");
            try {
                String json = Files.readString(prepared.descriptorPath());
                JsonNode descriptor = mapper.readTree(json);
                assertFalse(descriptor.has("token"));
                assertFalse(descriptor.has("privateKey"));
                assertFalse(descriptor.has("serverSecret"));
                assertFalse(descriptor.has("ipcSecret"));
                assertTrue(descriptor.path("brokerPipe").asText().startsWith("\\\\.\\pipe\\DeluxeWarfareAuth-"));
            } finally {
                prepared.close();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void brokerUsesActualBoundProcessPidAndRejectsAnotherPid() throws Exception {
        long expiresAt = java.time.Instant.now().getEpochSecond() + 60;
        WindowsAuthSigningBroker broker = new WindowsAuthSigningBroker(
                "\\\\.\\pipe\\DeluxeWarfareAuth-" + java.util.UUID.randomUUID(),
                testPrivateKey(), fields(SESSION, CHALLENGE, expiresAt));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 2").start();
        try {
            broker.bindMinecraftProcess(process);
            assertTrue(broker.acceptsClientPid(process.pid()));
            assertFalse(broker.acceptsClientPid(process.pid() + 1));
        } finally {
            process.destroyForcibly();
            broker.close();
        }
    }

    @Test
    void signedManifestEnvelopeIsRequiredAndTamperingIsRejected() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String publicSpki = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEPFjb410bj0zxqHQaZ3ujMjr7rrAmYiCYN4drvm1xk+wT3qnZiTlD5mDL75satkDJn3mlyxEmz7a9/xfbcfR1+A==";
        ManifestSignatureVerifier verifier = new ManifestSignatureVerifier(mapper, publicSpki, false);
        String payload = "{\"minecraftVersion\":\"1.20.1\",\"files\":[]}";
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(testPrivateKey());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String envelope = mapper.writeValueAsString(java.util.Map.of(
                "signatureAlgorithm", "SHA256withECDSA",
                "signingKeyId", GameAuthProtocol.keyId(Base64.getDecoder().decode(publicSpki)),
                "signedPayload", Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)),
                "signature", Base64.getEncoder().encodeToString(signer.sign())));
        assertEquals(payload, verifier.verifyAndExtract(envelope));
        assertThrows(SecurityException.class, () -> verifier.verifyAndExtract(payload));
        com.fasterxml.jackson.databind.node.ObjectNode tampered = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(envelope);
        tampered.put("signedPayload", Base64.getEncoder().encodeToString(
                payload.replace("1.20.1", "1.20.2").getBytes(StandardCharsets.UTF_8)));
        assertThrows(SecurityException.class, () -> verifier.verifyAndExtract(mapper.writeValueAsString(tampered)));
    }

    private GameAuthProtocol.ProofFields fields(String session, String challenge, long expiresAt) {
        return new GameAuthProtocol.ProofFields(session, KEY_ID, "deluxewarfare-main",
                "play.deluxe-warfare.ru", "12345678-1234-3234-9234-123456789abc",
                "ZumaDeluxe", challenge, expiresAt);
    }

    private PrivateKey testPrivateKey() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/game-auth-golden-private-key.pk8.b64")) {
            String base64 = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            return KeyFactory.getInstance("EC").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        }
    }
}
