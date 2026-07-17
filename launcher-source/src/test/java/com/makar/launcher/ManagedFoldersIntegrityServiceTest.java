package com.makar.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManagedFoldersIntegrityServiceTest {
    @TempDir
    Path gameDirectory;

    @Test
    void treatsTaczAsManagedDirectory() throws Exception {
        Path defaultGunFile = gameDirectory.resolve("tacz/tacz_default_gun/assets/tacz/guns/ak47.json");
        Path requiredFile = gameDirectory.resolve("tacz/Vanguard_Armament_v1.0.0/assets/tacz/guns/custom.json");
        Path requiredRootFile = gameDirectory.resolve("tacz/tacz-pre.toml");
        Path unknownFile = gameDirectory.resolve("tacz/extra-pack/readme.txt");
        Files.createDirectories(defaultGunFile.getParent());
        Files.createDirectories(requiredFile.getParent());
        Files.createDirectories(unknownFile.getParent());
        Files.writeString(defaultGunFile, "default");
        Files.writeString(requiredFile, "required");
        Files.writeString(requiredRootFile, "root");
        Files.writeString(unknownFile, "unknown");

        LauncherManifest manifest = new LauncherManifest();
        manifest.setFiles(List.of(
                manifestFile(
                        "tacz/Vanguard_Armament_v1.0.0/assets/tacz/guns/custom.json",
                        Files.readString(requiredFile)),
                manifestFile("tacz/tacz-pre.toml", Files.readString(requiredRootFile))));
        ManifestDirectoryEntry allowedDirectory = new ManifestDirectoryEntry();
        allowedDirectory.setPath("tacz/tacz_default_gun/");
        manifest.setOptionalAllowedDirectories(List.of(allowedDirectory));

        ManagedFoldersIntegrityService service = new ManagedFoldersIntegrityService();

        List<String> unknownFiles = service.findUnknownFiles(manifest, gameDirectory);

        assertEquals(List.of("tacz/extra-pack/readme.txt"), unknownFiles);
        assertEquals(unknownFiles, service.deleteUnknownFiles(gameDirectory, unknownFiles));
        assertFalse(Files.exists(unknownFile));
        assertTrue(Files.exists(defaultGunFile));
        assertTrue(Files.exists(requiredFile));
        assertTrue(Files.exists(requiredRootFile));
    }

    private ManifestFileEntry manifestFile(String path, String content) throws Exception {
        byte[] bytes = content.getBytes();
        ManifestFileEntry entry = new ManifestFileEntry();
        entry.setPath(path);
        entry.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        entry.setSize(bytes.length);
        entry.setRequired(true);
        return entry;
    }
}
