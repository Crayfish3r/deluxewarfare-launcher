package com.makar.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ElyBySkinService {
    private static final String PATCH_ARCHIVE_FILE_NAME = "elyby-1.20-authlib.zip";
    private static final String PATCH_ARCHIVE_URL =
            "https://ely.by/load/system?minecraftVersion=1.20-authlib";
    private static final String PATCH_ARCHIVE_SHA256 =
            "baba57035cad394e86738b2f2b4e5662e1d3add9a5dcb0bec7c7d8be3a6c2de5";
    private static final String PATCHED_AUTHLIB_FILE_NAME = "authlib-4.0.43.jar";
    private static final String PATCHED_AUTHLIB_SHA256 =
            "c32513856ea3008d8f9ad3a25824901c75573085f597c81829b8475014b4ee57";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public void prepare(Path gameDirectory, Consumer<String> logConsumer) {
        Path skinSystemDirectory = gameDirectory.resolve("skin-system");
        Path archivePath = skinSystemDirectory.resolve(PATCH_ARCHIVE_FILE_NAME);
        Path patchedAuthlibPath = skinSystemDirectory.resolve(PATCHED_AUTHLIB_FILE_NAME);
        Path installedAuthlibPath = gameDirectory
                .resolve("libraries")
                .resolve("com")
                .resolve("mojang")
                .resolve("authlib")
                .resolve("4.0.43")
                .resolve(PATCHED_AUTHLIB_FILE_NAME);

        ensurePatchArchive(archivePath, logConsumer);
        ensurePatchedAuthlib(archivePath, patchedAuthlibPath);
        installPatchedAuthlib(patchedAuthlibPath, installedAuthlibPath);
        logConsumer.accept("Ely.by skin system is ready.");
    }

    private void ensurePatchArchive(Path archivePath, Consumer<String> logConsumer) {
        if (hasSha256(archivePath, PATCH_ARCHIVE_SHA256)) {
            return;
        }

        logConsumer.accept("Downloading official Ely.by skin patch...");
        Path temporaryPath = archivePath.resolveSibling(archivePath.getFileName() + ".download");

        try {
            Files.createDirectories(archivePath.getParent());

            HttpRequest request = HttpRequest.newBuilder(URI.create(PATCH_ARCHIVE_URL))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ElyBySkinException("Ely.by patch download failed with HTTP " + response.statusCode());
            }

            try (InputStream inputStream = response.body()) {
                Files.copy(inputStream, temporaryPath, StandardCopyOption.REPLACE_EXISTING);
            }

            if (!hasSha256(temporaryPath, PATCH_ARCHIVE_SHA256)) {
                Files.deleteIfExists(temporaryPath);
                throw new ElyBySkinException("Ely.by patch checksum mismatch");
            }

            Files.move(temporaryPath, archivePath, StandardCopyOption.REPLACE_EXISTING);
            logConsumer.accept("Official Ely.by skin patch downloaded.");
        } catch (IOException exception) {
            throw new ElyBySkinException("unable to store Ely.by patch", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ElyBySkinException("Ely.by patch download was interrupted", exception);
        }
    }

    private void ensurePatchedAuthlib(Path archivePath, Path patchedAuthlibPath) {
        if (hasSha256(patchedAuthlibPath, PATCHED_AUTHLIB_SHA256)) {
            return;
        }

        try (InputStream inputStream = Files.newInputStream(archivePath);
                ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory() || !PATCHED_AUTHLIB_FILE_NAME.equals(entry.getName())) {
                    continue;
                }

                Files.copy(zipInputStream, patchedAuthlibPath, StandardCopyOption.REPLACE_EXISTING);
                if (!hasSha256(patchedAuthlibPath, PATCHED_AUTHLIB_SHA256)) {
                    Files.deleteIfExists(patchedAuthlibPath);
                    throw new ElyBySkinException("patched authlib checksum mismatch");
                }
                return;
            }
        } catch (IOException exception) {
            throw new ElyBySkinException("unable to extract Ely.by authlib patch", exception);
        }

        throw new ElyBySkinException("Ely.by archive does not contain " + PATCHED_AUTHLIB_FILE_NAME);
    }

    private void installPatchedAuthlib(Path patchedAuthlibPath, Path installedAuthlibPath) {
        if (hasSha256(installedAuthlibPath, PATCHED_AUTHLIB_SHA256)) {
            return;
        }

        try {
            Files.createDirectories(installedAuthlibPath.getParent());
            Files.copy(patchedAuthlibPath, installedAuthlibPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new ElyBySkinException("unable to install Ely.by authlib patch", exception);
        }
    }

    private boolean hasSha256(Path path, String expectedSha256) {
        return Files.isRegularFile(path) && expectedSha256.equalsIgnoreCase(sha256(path));
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream inputStream = Files.newInputStream(path);
                    DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
                digestInputStream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ElyBySkinException("unable to verify Ely.by patch", exception);
        }
    }

    public static final class ElyBySkinException extends RuntimeException {
        public ElyBySkinException(String message) {
            super(message);
        }

        public ElyBySkinException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

