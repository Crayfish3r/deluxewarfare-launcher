package com.makar.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Detects simple file injection attempts during a game session.
 * This is an integrity layer, not a replacement for server-side anti-cheat.
 */
public final class RuntimeManagedFolderProtectionService implements AutoCloseable {
    private final ManagedFoldersIntegrityService integrityService;
    private final FileHashService fileHashService;
    private final LauncherManifest manifest;
    private final Path gameDirectory;
    private final Process minecraftProcess;
    private final boolean terminateGameOnDetection;
    private final int scanIntervalSeconds;
    private final Consumer<String> logger;
    private final Consumer<List<DetectedFile>> reportCallback;
    private final ScheduledExecutorService executor;
    private volatile boolean stopped;

    public RuntimeManagedFolderProtectionService(
            ManagedFoldersIntegrityService integrityService,
            FileHashService fileHashService,
            LauncherManifest manifest,
            Path gameDirectory,
            Process minecraftProcess,
            boolean terminateGameOnDetection,
            int scanIntervalSeconds,
            Consumer<String> logger,
            Consumer<List<DetectedFile>> reportCallback
    ) {
        this.integrityService = integrityService;
        this.fileHashService = fileHashService;
        this.manifest = manifest;
        this.gameDirectory = gameDirectory;
        this.minecraftProcess = minecraftProcess;
        this.terminateGameOnDetection = terminateGameOnDetection;
        this.scanIntervalSeconds = Math.max(1, scanIntervalSeconds);
        this.logger = logger;
        this.reportCallback = reportCallback;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "runtime-managed-folder-protection");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        logger.accept("Runtime managed folder protection started.");
        executor.scheduleWithFixedDelay(this::scanSafely, scanIntervalSeconds, scanIntervalSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        if (stopped) {
            return;
        }

        stopped = true;
        executor.shutdownNow();
        logger.accept("Runtime managed folder protection stopped.");
    }

    @Override
    public void close() {
        stop();
    }

    private void scanSafely() {
        if (stopped || !minecraftProcess.isAlive()) {
            stop();
            return;
        }

        try {
            scan();
        } catch (RuntimeException exception) {
            logger.accept("RUNTIME MANAGED FOLDER SCAN FAILED | " + exception.getMessage());
        }
    }

    private void scan() {
        List<String> unknownFiles = integrityService.findUnknownFiles(manifest, gameDirectory);
        if (unknownFiles.isEmpty()) {
            return;
        }

        List<DetectedFile> detectedFiles = new ArrayList<>();
        for (String unknownFile : unknownFiles) {
            logger.accept("RUNTIME FORBIDDEN FILE DETECTED | " + unknownFile);
            detectedFiles.add(readMetadata(unknownFile));
        }

        try {
            reportCallback.accept(List.copyOf(detectedFiles));
        } catch (RuntimeException exception) {
            logger.accept("RUNTIME MODERATION ALERT FAILED | " + exception.getMessage());
        }

        for (String unknownFile : unknownFiles) {
            try {
                List<String> deletedFiles = integrityService.deleteUnknownFiles(gameDirectory, List.of(unknownFile));
                if (deletedFiles.contains(unknownFile)) {
                    logger.accept("RUNTIME FORBIDDEN FILE REMOVED | " + unknownFile);
                } else {
                    logger.accept("RUNTIME FORBIDDEN FILE DELETE FAILED | " + unknownFile);
                }
            } catch (RuntimeException exception) {
                logger.accept("RUNTIME FORBIDDEN FILE DELETE FAILED | " + unknownFile);
            }
        }

        if (terminateGameOnDetection && minecraftProcess.isAlive()) {
            minecraftProcess.destroy();
            if (minecraftProcess.isAlive()) {
                minecraftProcess.destroyForcibly();
            }
            logger.accept("Minecraft was terminated because a forbidden file was detected during runtime: "
                    + unknownFiles.get(0));
        }

        stop();
    }

    private DetectedFile readMetadata(String relativePath) {
        Path file = gameDirectory.resolve(relativePath).normalize();
        long size = -1;
        String sha256 = "";

        try {
            size = Files.size(file);
        } catch (IOException ignored) {
        }

        try {
            sha256 = fileHashService.calculateSha256(file);
        } catch (RuntimeException ignored) {
        }

        int slashIndex = relativePath.indexOf('/');
        String directory = slashIndex >= 0 ? relativePath.substring(0, slashIndex) : "";
        String fileName = slashIndex >= 0 ? relativePath.substring(slashIndex + 1) : relativePath;
        return new DetectedFile(relativePath, directory, fileName, size, sha256);
    }

    public record DetectedFile(String path, String directory, String fileName, long size, String sha256) {
    }
}
