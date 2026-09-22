package com.makar.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;

final class ManifestSignatureVerifier {
    private final ObjectMapper objectMapper;
    private final PublicKey publicKey;
    private final String keyId;
    private final boolean allowUnsigned;

    static ManifestSignatureVerifier fromEmbeddedTrust(ObjectMapper objectMapper) {
        try (InputStream stream = ManifestSignatureVerifier.class.getResourceAsStream("/manifest-trust.properties")) {
            if (stream == null) throw new IllegalStateException("Manifest trust configuration is missing.");
            Properties properties = new Properties();
            properties.load(stream);
            String spkiBase64 = properties.getProperty("publicKeySpki", "").trim();
            if (spkiBase64.isBlank() || "UNCONFIGURED".equals(spkiBase64)) {
                throw new IllegalStateException("Launcher was built without the official manifest public key.");
            }
            return new ManifestSignatureVerifier(objectMapper, spkiBase64, false);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load manifest trust configuration.", exception);
        }
    }

    static ManifestSignatureVerifier allowUnsignedForTests(ObjectMapper objectMapper) {
        return new ManifestSignatureVerifier(objectMapper, "", true);
    }

    ManifestSignatureVerifier(ObjectMapper objectMapper, String publicKeySpki, boolean allowUnsigned) {
        this.objectMapper = objectMapper;
        this.allowUnsigned = allowUnsigned;
        if (allowUnsigned) {
            this.publicKey = null;
            this.keyId = "";
            return;
        }
        try {
            byte[] spki = Base64.getDecoder().decode(publicKeySpki);
            this.publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
            this.keyId = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(spki));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Manifest public key is invalid.", exception);
        }
    }

    String verifyAndExtract(String envelopeJson) {
        if (allowUnsigned) return envelopeJson;
        try {
            JsonNode envelope = objectMapper.readTree(envelopeJson);
            if (!"SHA256withECDSA".equals(envelope.path("signatureAlgorithm").asText())
                    || !keyId.equals(envelope.path("signingKeyId").asText())) {
                throw new SecurityException("Manifest signer is not trusted.");
            }
            byte[] payload = Base64.getDecoder().decode(envelope.path("signedPayload").asText());
            byte[] signature = Base64.getDecoder().decode(envelope.path("signature").asText());
            if (payload.length > 5 * 1024 * 1024 || signature.length > 128) {
                throw new SecurityException("Signed manifest envelope is too large.");
            }
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            if (!verifier.verify(signature)) throw new SecurityException("Manifest signature is invalid.");
            return new String(payload, StandardCharsets.UTF_8);
        } catch (SecurityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SecurityException("Signed manifest envelope is malformed.", exception);
        }
    }
}
