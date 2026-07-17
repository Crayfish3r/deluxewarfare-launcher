package com.makar.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FileHashService {
    private static final int BUFFER_SIZE = 8192;

    public List<FileCheckResult> checkFiles(List<ManifestFileEntry> files, Path rootDirectory) {
        try {
            Files.createDirectories(rootDirectory);
        } catch (IOException exception) {
            throw new FileHashException("Unable to create game directory: " + rootDirectory, exception);
        }

        List<FileCheckResult> results = new ArrayList<>();

        for (ManifestFileEntry file : files) {
            Path localPath = rootDirectory.resolve(file.getPath()).normalize();
            if (!localPath.startsWith(rootDirectory.normalize())) {
                throw new FileHashException("Manifest file path escapes launcher directory: " + file.getPath());
            }
            results.add(checkFile(file, localPath));
        }

        return results;
    }

    private FileCheckResult checkFile(ManifestFileEntry file, Path localPath) {
        if (!Files.exists(localPath)) {
            return new FileCheckResult(file, localPath, FileStatus.MISSING);
        }

        if (!Files.isRegularFile(localPath)) {
            return new FileCheckResult(file, localPath, FileStatus.OUTDATED);
        }

        String expectedHash = normalizeSha256(file.getSha256());
        if (expectedHash.isEmpty()) {
            return new FileCheckResult(file, localPath, FileStatus.OUTDATED);
        }

        String actualHash = calculateSha256(localPath);
        FileStatus status = expectedHash.equals(actualHash) ? FileStatus.OK : FileStatus.OUTDATED;
        return new FileCheckResult(file, localPath, status);
    }

    public String calculateSha256(Path file) {
        MessageDigest digest = createSha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream inputStream = Files.newInputStream(file);
                DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
            while (digestInputStream.read(buffer) != -1) {
                // Reading through DigestInputStream updates the digest.
            }
        } catch (IOException exception) {
            throw new FileHashException("Unable to read local file: " + file, exception);
        }

        return toHex(digest.digest());
    }

    private MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new FileHashException("SHA-256 is not supported by this Java runtime.", exception);
        }
    }

    public static String normalizeSha256(String hash) {
        return hash == null ? "" : hash.trim().toLowerCase(Locale.ROOT);
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    public enum FileStatus {
        OK,
        MISSING,
        OUTDATED
    }

    public static final class FileCheckResult {
        private final ManifestFileEntry entry;
        private final Path localPath;
        private final FileStatus status;

        public FileCheckResult(ManifestFileEntry entry, Path localPath, FileStatus status) {
            this.entry = entry;
            this.localPath = localPath;
            this.status = status;
        }

        public ManifestFileEntry getEntry() {
            return entry;
        }

        public String getManifestPath() {
            return entry.getPath();
        }

        public Path getLocalPath() {
            return localPath;
        }

        public FileStatus getStatus() {
            return status;
        }
    }

    public static final class FileHashException extends RuntimeException {
        public FileHashException(String message) {
            super(message);
        }

        public FileHashException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
