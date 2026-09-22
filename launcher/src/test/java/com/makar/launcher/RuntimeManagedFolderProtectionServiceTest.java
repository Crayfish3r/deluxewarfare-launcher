package com.makar.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeManagedFolderProtectionServiceTest {
    @TempDir Path gameDirectory;

    @Test
    void detectsUnknownFileInNewNestedDirectoryAndShutsDown() throws Exception {
        LauncherManifest manifest = new LauncherManifest();
        FakeProcess process = new FakeProcess();
        CountDownLatch reported = new CountDownLatch(1);
        RuntimeManagedFolderProtectionService service = new RuntimeManagedFolderProtectionService(
                new ManagedFoldersIntegrityService(), new FileHashService(), manifest, gameDirectory, process,
                ignored -> { }, files -> reported.countDown());
        service.start();
        Path forbidden = gameDirectory.resolve("mods/new/nested/forbidden.jar");
        Files.createDirectories(forbidden.getParent());
        Thread.sleep(250);
        Files.writeString(forbidden, "forbidden");
        assertTrue(reported.await(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(process.isAlive());
        assertTrue(service.metrics().watchEvents() > 0);
        service.close();
        assertFalse(service.metrics().backgroundThreadsAlive());
    }

    @Test
    void overflowReconciliationDetectsModifiedRequiredFile() throws Exception {
        Path required = gameDirectory.resolve("mods/required.jar");
        Files.createDirectories(required.getParent());
        Files.writeString(required, "original");
        LauncherManifest manifest = new LauncherManifest();
        manifest.setFiles(List.of(manifestFile("mods/required.jar", "original")));
        FakeProcess process = new FakeProcess();
        CountDownLatch reported = new CountDownLatch(1);
        RuntimeManagedFolderProtectionService service = new RuntimeManagedFolderProtectionService(
                new ManagedFoldersIntegrityService(), new FileHashService(), manifest, gameDirectory, process,
                ignored -> { }, files -> reported.countDown());
        Files.writeString(required, "tampered");
        service.handleOverflowForTest();
        assertTrue(reported.await(2, TimeUnit.SECONDS));
        assertEquals(1, service.metrics().fallbackScans());
        assertFalse(process.isAlive());
        service.close();
        assertFalse(service.metrics().backgroundThreadsAlive());
    }

    @Test
    void permitsTaczRuntimeExportStateButPreLaunchStillChecksItsHash() throws Exception {
        Path exportState = gameDirectory.resolve("tacz/.export-state.json");
        Files.createDirectories(exportState.getParent());
        Files.writeString(exportState, "installed-state");
        LauncherManifest manifest = new LauncherManifest();
        manifest.setFiles(List.of(manifestFile("tacz/.export-state.json", "installed-state")));
        ManagedFoldersIntegrityService integrityService = new ManagedFoldersIntegrityService();
        assertTrue(integrityService.scanFully(manifest, gameDirectory).violations().isEmpty());

        Files.writeString(exportState, "state-generated-by-tacz-at-runtime");
        assertEquals(List.of("tacz/.export-state.json"),
                integrityService.scanFully(manifest, gameDirectory).violations());

        FakeProcess process = new FakeProcess();
        CountDownLatch reported = new CountDownLatch(1);
        RuntimeManagedFolderProtectionService service = new RuntimeManagedFolderProtectionService(
                integrityService, new FileHashService(), manifest, gameDirectory, process,
                ignored -> { }, files -> reported.countDown());
        service.handleOverflowForTest();

        assertFalse(reported.await(250, TimeUnit.MILLISECONDS));
        assertTrue(process.isAlive());
        assertEquals(1, service.metrics().fallbackScans());
        service.close();
        assertFalse(service.metrics().backgroundThreadsAlive());
    }

    private ManifestFileEntry manifestFile(String path, String content) throws Exception {
        ManifestFileEntry entry = new ManifestFileEntry();
        entry.setPath(path);
        entry.setSha256(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes())));
        entry.setSize(content.length());
        entry.setRequired(true);
        return entry;
    }

    private static final class FakeProcess extends Process {
        private volatile boolean alive = true;
        @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
        @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
        @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
        @Override public int waitFor() { alive = false; return 0; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
    }
}
