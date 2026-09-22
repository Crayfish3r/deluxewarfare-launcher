package com.makar.launcher;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Event-driven integrity protection for the lifetime of a Minecraft process. */
public final class RuntimeManagedFolderProtectionService implements AutoCloseable {
    private static final long RECONCILIATION_INTERVAL_SECONDS = 60;
    private static final long EVENT_SETTLE_MILLIS = 150;
    // TaCZ rewrites this required installation file as runtime export state.
    // It remains hash-checked by the strict pre-launch scan, but its in-game lifecycle is trusted.
    private static final Set<String> RUNTIME_MUTABLE_PATHS = Set.of("tacz/.export-state.json");

    private final ManagedFoldersIntegrityService integrityService;
    private final FileHashService fileHashService;
    private final LauncherManifest manifest;
    private final Path gameDirectory;
    private final Process minecraftProcess;
    private final Consumer<String> logger;
    private final Consumer<List<DetectedFile>> reportCallback;
    private final WatchServiceFactory watchServiceFactory;
    private final ManagedFoldersIntegrityService.HashCache hashCache = new ManagedFoldersIntegrityService.HashCache();
    private final ScheduledExecutorService eventExecutor;
    private final ScheduledExecutorService reconciliationExecutor;
    private final Map<WatchKey, Path> watchedDirectories = new ConcurrentHashMap<>();
    private final Map<Path, Long> pendingPaths = new ConcurrentHashMap<>();
    private final AtomicLong watchEvents = new AtomicLong();
    private final AtomicLong fallbackScans = new AtomicLong();
    private final AtomicLong examinedFiles = new AtomicLong();
    private final AtomicLong hashedFiles = new AtomicLong();
    private final AtomicLong hashedBytes = new AtomicLong();
    private volatile WatchService watchService;
    private volatile Thread watchThread;
    private volatile boolean stopped;
    private volatile boolean violationHandled;

    public RuntimeManagedFolderProtectionService(
            ManagedFoldersIntegrityService integrityService,
            FileHashService fileHashService,
            LauncherManifest manifest,
            Path gameDirectory,
            Process minecraftProcess,
            Consumer<String> logger,
            Consumer<List<DetectedFile>> reportCallback
    ) {
        this(integrityService, fileHashService, manifest, gameDirectory, minecraftProcess, logger, reportCallback,
                () -> FileSystems.getDefault().newWatchService());
    }

    RuntimeManagedFolderProtectionService(
            ManagedFoldersIntegrityService integrityService,
            FileHashService fileHashService,
            LauncherManifest manifest,
            Path gameDirectory,
            Process minecraftProcess,
            Consumer<String> logger,
            Consumer<List<DetectedFile>> reportCallback,
            WatchServiceFactory watchServiceFactory
    ) {
        this.integrityService = integrityService;
        this.fileHashService = fileHashService;
        this.manifest = manifest;
        this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
        this.minecraftProcess = minecraftProcess;
        this.logger = logger;
        this.reportCallback = reportCallback;
        this.watchServiceFactory = watchServiceFactory;
        this.eventExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> lowPriorityThread(
                runnable, "runtime-integrity-events"));
        this.reconciliationExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> lowPriorityThread(
                runnable, "runtime-integrity-reconciliation"));
    }

    public synchronized void start() {
        if (stopped || watchService != null) {
            return;
        }
        try {
            watchService = watchServiceFactory.create();
            registerManagedTrees();
        } catch (IOException exception) {
            closeWatchService();
            throw new ManagedFoldersIntegrityService.IntegrityCheckException(
                    "Unable to start runtime folder watcher.", exception);
        }
        watchThread = lowPriorityThread(this::watchLoop, "runtime-integrity-watcher");
        watchThread.start();
        reconciliationExecutor.scheduleWithFixedDelay(
                () -> reconcileSafely("periodic"),
                RECONCILIATION_INTERVAL_SECONDS,
                RECONCILIATION_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        logger.accept("Runtime managed folder protection started (WatchService + 60s reconciliation).");
    }

    @Override
    public void close() {
        stop();
    }

    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        closeWatchService();
        eventExecutor.shutdownNow();
        reconciliationExecutor.shutdownNow();
        Thread thread = watchThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        awaitTermination(eventExecutor);
        awaitTermination(reconciliationExecutor);
        logger.accept("Runtime protection metrics | watchEvents=" + watchEvents.get()
                + " | fallbackScans=" + fallbackScans.get()
                + " | examinedFiles=" + examinedFiles.get()
                + " | hashedFiles=" + hashedFiles.get()
                + " | hashedBytes=" + hashedBytes.get());
        logger.accept("Runtime managed folder protection stopped.");
    }

    public Metrics metrics() {
        return new Metrics(watchEvents.get(), fallbackScans.get(), examinedFiles.get(), hashedFiles.get(),
                hashedBytes.get(), watchedDirectories.size(), backgroundThreadsAlive());
    }

    void handleOverflowForTest() {
        reconcileSafely("overflow");
    }

    private void watchLoop() {
        while (!stopped && minecraftProcess.isAlive()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException exception) {
                if (!stopped) {
                    logger.accept("RUNTIME WATCH SERVICE FAILED | " + exception.getMessage());
                    reconcileSafely("watch failure");
                }
                break;
            }
            Path directory = watchedDirectories.get(key);
            boolean overflow = directory == null;
            for (WatchEvent<?> event : key.pollEvents()) {
                watchEvents.incrementAndGet();
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    overflow = true;
                    continue;
                }
                if (directory == null || !(event.context() instanceof Path context)) {
                    continue;
                }
                Path affected = directory.resolve(context).normalize();
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                        && Files.isDirectory(affected, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        registerTree(affected);
                        // Creation can outrun recursive registration (for example mkdirs + immediate file copy).
                        // Check only the new subtree so files copied before registration cannot evade protection.
                        eventExecutor.execute(() -> checkSubtreeSafely(affected));
                    } catch (IOException exception) {
                        overflow = true;
                    }
                }
                queuePathCheck(affected);
            }
            if (!key.reset()) {
                watchedDirectories.remove(key);
            }
            if (overflow) {
                eventExecutor.execute(() -> reconcileSafely("overflow"));
            }
        }
        if (!stopped) {
            stop();
        }
    }

    private void queuePathCheck(Path path) {
        if (!path.startsWith(gameDirectory)) {
            return;
        }
        long generation = pendingPaths.merge(path, 1L, Long::sum);
        eventExecutor.schedule(() -> {
            if (!Long.valueOf(generation).equals(pendingPaths.get(path))) {
                return;
            }
            pendingPaths.remove(path, generation);
            checkChangedPath(path);
        }, EVENT_SETTLE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void checkChangedPath(Path path) {
        if (stopped || violationHandled || !minecraftProcess.isAlive()) {
            return;
        }
        String relative = relativePath(path);
        hashCache.invalidate(relative);
        if (isRuntimeMutablePath(relative)) {
            return;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        ManagedFoldersIntegrityService.PathCheckResult result =
                integrityService.checkPath(manifest, gameDirectory, path, hashCache, true);
        examinedFiles.incrementAndGet();
        if (result.hashed()) {
            hashedFiles.incrementAndGet();
            hashedBytes.addAndGet(result.hashedBytes());
        }
        boolean deletedOptional = !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && result.reason() == ManagedFoldersIntegrityService.ViolationReason.OPTIONAL_FILE_MODIFIED;
        boolean deletedUnknown = !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && result.reason() == ManagedFoldersIntegrityService.ViolationReason.UNKNOWN_FILE;
        if (!result.allowed() && !deletedOptional && !deletedUnknown) {
            handleViolations(List.of(result.relativePath()));
        }
    }

    private void reconcileSafely(String reason) {
        if (stopped || violationHandled || !minecraftProcess.isAlive()) {
            return;
        }
        fallbackScans.incrementAndGet();
        try {
            ManagedFoldersIntegrityService.ScanResult result = integrityService.reconcile(
                    manifest, gameDirectory, hashCache);
            examinedFiles.addAndGet(result.examinedFiles());
            hashedFiles.addAndGet(result.hashedFiles());
            hashedBytes.addAndGet(result.hashedBytes());
            List<String> runtimeViolations = result.violations().stream()
                    .filter(path -> !isRuntimeMutablePath(path))
                    .toList();
            if (!runtimeViolations.isEmpty()) {
                logger.accept("Runtime reconciliation detected an integrity violation (" + reason + ").");
                handleViolations(runtimeViolations);
            }
        } catch (RuntimeException exception) {
            logger.accept("RUNTIME RECONCILIATION FAILED | " + exception.getMessage());
        }
    }

    private void checkSubtreeSafely(Path root) {
        if (stopped || violationHandled || !minecraftProcess.isAlive()) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    queuePathCheck(file);
                    return violationHandled ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            logger.accept("RUNTIME NEW DIRECTORY CHECK FAILED | " + exception.getMessage());
            reconcileSafely("new directory failure");
        }
    }

    private synchronized void handleViolations(List<String> paths) {
        if (violationHandled || stopped) {
            return;
        }
        violationHandled = true;
        List<DetectedFile> detectedFiles = new ArrayList<>();
        for (String path : paths) {
            logger.accept("RUNTIME INTEGRITY VIOLATION | " + path);
            detectedFiles.add(readMetadata(path));
        }
        try {
            reportCallback.accept(List.copyOf(detectedFiles));
        } catch (RuntimeException exception) {
            logger.accept("RUNTIME MODERATION ALERT FAILED | " + exception.getMessage());
        }
        for (String path : paths) {
            try {
                if (integrityService.deleteUnknownFiles(gameDirectory, List.of(path)).contains(path)) {
                    logger.accept("RUNTIME FORBIDDEN FILE REMOVED | " + path);
                }
            } catch (RuntimeException exception) {
                logger.accept("RUNTIME FORBIDDEN FILE DELETE FAILED | " + path);
            }
        }
        if (minecraftProcess.isAlive()) {
            minecraftProcess.destroy();
            if (minecraftProcess.isAlive()) {
                minecraftProcess.destroyForcibly();
            }
            logger.accept("Minecraft was terminated because an integrity violation was detected: " + paths.get(0));
        }
        stop();
    }

    private void registerManagedTrees() throws IOException {
        for (String name : ManagedFoldersIntegrityService.MANAGED_DIRECTORIES) {
            Path directory = gameDirectory.resolve(name);
            Files.createDirectories(directory);
            registerTree(directory);
        }
    }

    private void registerTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                WatchKey key = directory.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                watchedDirectories.put(key, directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private DetectedFile readMetadata(String relativePath) {
        Path file = gameDirectory.resolve(relativePath).normalize();
        long size = -1;
        String sha256 = "";
        try { size = Files.size(file); } catch (IOException ignored) { }
        try { sha256 = fileHashService.calculateSha256(file); } catch (RuntimeException ignored) { }
        int slashIndex = relativePath.indexOf('/');
        String directory = slashIndex >= 0 ? relativePath.substring(0, slashIndex) : "";
        String fileName = slashIndex >= 0 ? relativePath.substring(slashIndex + 1) : relativePath;
        return new DetectedFile(relativePath, directory, fileName, size, sha256);
    }

    private String relativePath(Path path) {
        return gameDirectory.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static boolean isRuntimeMutablePath(String path) {
        return RUNTIME_MUTABLE_PATHS.contains(
                ManagedFoldersIntegrityService.normalizeManifestPath(path).toLowerCase(java.util.Locale.ROOT));
    }

    private void closeWatchService() {
        WatchService service = watchService;
        watchService = null;
        if (service != null) {
            try { service.close(); } catch (IOException ignored) { }
        }
        watchedDirectories.clear();
    }

    private static Thread lowPriorityThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    }

    private static void awaitTermination(ScheduledExecutorService executor) {
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean backgroundThreadsAlive() {
        Thread thread = watchThread;
        return (thread != null && thread.isAlive())
                || !eventExecutor.isTerminated()
                || !reconciliationExecutor.isTerminated();
    }

    interface WatchServiceFactory {
        WatchService create() throws IOException;
    }

    public record Metrics(long watchEvents, long fallbackScans, long examinedFiles, long hashedFiles,
                          long hashedBytes, int watchedDirectories, boolean backgroundThreadsAlive) { }
    public record DetectedFile(String path, String directory, String fileName, long size, String sha256) { }
}
