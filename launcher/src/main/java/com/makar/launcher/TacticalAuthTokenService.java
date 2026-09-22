package com.makar.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;

public final class TacticalAuthTokenService {
    private static final String DESCRIPTOR_FILE_NAME = "tactical_auth_descriptor.json";
    private static final String LEGACY_TOKEN_FILE_NAME = "tactical_auth_token.json";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PreparedAuth prepare(
            Path gameDirectory,
            BackendAuthService backend,
            String launcherSessionToken,
            String nickname,
            String serverId,
            String serverAddress,
            String playerUuid,
            String clientBuildId
    ) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = generator.generateKeyPair();
            byte[] spki = keyPair.getPublic().getEncoded();
            String publicKeySpki = Base64.getEncoder().encodeToString(spki);
            String expectedKeyId = GameAuthProtocol.keyId(spki);
            String canonicalAddress = GameAuthProtocol.canonicalizeServerAddress(serverAddress);

            BackendAuthService.LaunchSessionResponse session = backend.createLaunchSession(
                    launcherSessionToken, nickname, serverId, canonicalAddress, publicKeySpki, clientBuildId);
            if (session.getLaunchSessionId().isBlank() || !expectedKeyId.equals(session.getKeyId())
                    || !serverId.equals(session.getServerId())
                    || !canonicalAddress.equals(GameAuthProtocol.canonicalizeServerAddress(session.getServerAddress()))) {
                throw new IllegalStateException("Backend returned an invalid launch authentication binding.");
            }

            String pipeName = "\\\\.\\pipe\\DeluxeWarfareAuth-" + UUID.randomUUID();
            GameAuthProtocol.ProofFields initial = new GameAuthProtocol.ProofFields(
                    session.getLaunchSessionId(), session.getKeyId(), serverId, canonicalAddress,
                    playerUuid.toLowerCase(), nickname, placeholderChallenge(), session.getExpiresAt());
            WindowsAuthSigningBroker broker = new WindowsAuthSigningBroker(pipeName, keyPair.getPrivate(), initial);
            broker.start();

            Path configDirectory = Files.createDirectories(gameDirectory.resolve("config"));
            Files.deleteIfExists(configDirectory.resolve(LEGACY_TOKEN_FILE_NAME));
            Path descriptorPath = configDirectory.resolve(DESCRIPTOR_FILE_NAME);
            Path temporaryPath = configDirectory.resolve(DESCRIPTOR_FILE_NAME + ".tmp");
            Descriptor descriptor = new Descriptor(nickname, session.getLaunchSessionId(), session.getKeyId(),
                    backend.getBackendUrl(), serverId, canonicalAddress, playerUuid.toLowerCase(),
                    session.getExpiresAt(), pipeName);
            Files.writeString(temporaryPath,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(descriptor), StandardCharsets.UTF_8);
            moveReplacingExisting(temporaryPath, descriptorPath);
            return new PreparedAuth(descriptorPath, broker);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to prepare proof-of-possession authentication.", exception);
        }
    }

    private String placeholderChallenge() {
        return "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    }

    private void moveReplacingExisting(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Descriptor(String nickname, String launchSessionId, String keyId, String backendUrl,
                              String serverId, String serverAddress, String playerUuid, long expiresAt,
                              String brokerPipe) { }

    public static final class PreparedAuth implements AutoCloseable {
        private final Path descriptorPath;
        private final WindowsAuthSigningBroker broker;

        private PreparedAuth(Path descriptorPath, WindowsAuthSigningBroker broker) {
            this.descriptorPath = descriptorPath;
            this.broker = broker;
        }

        public Path descriptorPath() { return descriptorPath; }

        public void bindMinecraftProcess(Process process) { broker.bindMinecraftProcess(process); }

        @Override
        public void close() {
            broker.close();
            try { Files.deleteIfExists(descriptorPath); } catch (IOException ignored) { }
        }
    }
}
