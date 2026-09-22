package com.makar.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

public final class ManagedFoldersIntegrityService {
    static final List<String> MANAGED_DIRECTORIES = List.of("mods", "resourcepacks", "shaderpacks", "tacz");

    private final FileHashService fileHashService;

    public ManagedFoldersIntegrityService() {
        this(new FileHashService());
    }

    ManagedFoldersIntegrityService(FileHashService fileHashService) {
        this.fileHashService = fileHashService;
    }

    /** A strict, uncached scan intended for the mandatory pre-launch validation. */
    public ScanResult scanFully(LauncherManifest manifest, Path gameDirectory) {
        return scan(manifest, gameDirectory, null);
    }

    /** A reconciliation scan which skips hashing files whose size and timestamp did not change. */
    public ScanResult reconcile(LauncherManifest manifest, Path gameDirectory, HashCache hashCache) {
        return scan(manifest, gameDirectory, hashCache);
    }

    public List<String> findUnknownFiles(LauncherManifest manifest, Path gameDirectory) {
        return scanFully(manifest, gameDirectory).violations();
    }

    public PathCheckResult checkPath(
            LauncherManifest manifest,
            Path gameDirectory,
            Path path,
            HashCache hashCache,
            boolean forceHash
    ) {
        Policy policy = Policy.from(manifest);
        return checkPath(policy, gameDirectory, path, hashCache, forceHash, new ScanCounters());
    }

    public List<String> deleteUnknownFiles(Path gameDirectory, List<String> unknownFiles) {
        Path normalizedGameDirectory = gameDirectory.toAbsolutePath().normalize();
        List<String> deletedFiles = new ArrayList<>();
        for (String unknownFile : unknownFiles) {
            String normalizedPath = normalizeManifestPath(unknownFile);
            if (!isManagedPath(normalizedPath)) {
                throw new IntegrityCheckException("Refusing to delete file outside managed folders: " + normalizedPath);
            }
            Path target = normalizedGameDirectory.resolve(normalizedPath).normalize();
            if (!target.startsWith(normalizedGameDirectory)) {
                throw new IntegrityCheckException("Refusing to delete file outside game directory: " + normalizedPath);
            }
            try {
                if (Files.deleteIfExists(target)) {
                    deletedFiles.add(normalizedPath);
                }
            } catch (IOException exception) {
                throw new IntegrityCheckException("Could not delete forbidden file: " + normalizedPath, exception);
            }
        }
        return deletedFiles;
    }

    private ScanResult scan(LauncherManifest manifest, Path gameDirectory, HashCache hashCache) {
        long startedAt = System.nanoTime();
        Policy policy = Policy.from(manifest);
        ScanCounters counters = new ScanCounters();
        List<String> violations = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        for (String directoryName : MANAGED_DIRECTORIES) {
            Path managedDirectory = gameDirectory.resolve(directoryName);
            if (!Files.exists(managedDirectory, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(managedDirectory)) {
                paths.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).forEach(path -> {
                    PathCheckResult result = checkPath(policy, gameDirectory, path, hashCache, false, counters);
                    visited.add(result.relativePath().toLowerCase(Locale.ROOT));
                    if (!result.allowed()) {
                        violations.add(result.relativePath());
                    }
                });
            } catch (IOException exception) {
                throw new IntegrityCheckException("Unable to validate managed directory: " + managedDirectory, exception);
            }
        }

        for (String requiredPath : policy.requiredFiles.keySet()) {
            if (!visited.contains(requiredPath)) {
                counters.examined.increment();
                violations.add(policy.requiredFiles.get(requiredPath).getPath());
            }
        }
        violations.replaceAll(ManagedFoldersIntegrityService::normalizeManifestPath);
        violations.sort(String.CASE_INSENSITIVE_ORDER);
        return new ScanResult(
                List.copyOf(violations),
                counters.examined.sum(),
                counters.hashed.sum(),
                counters.hashedBytes.sum(),
                System.nanoTime() - startedAt
        );
    }

    private PathCheckResult checkPath(
            Policy policy,
            Path gameDirectory,
            Path path,
            HashCache hashCache,
            boolean forceHash,
            ScanCounters counters
    ) {
        Path normalizedGameDirectory = gameDirectory.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        String relativePath;
        try {
            relativePath = normalizeManifestPath(normalizedGameDirectory.relativize(normalizedPath).toString());
        } catch (IllegalArgumentException exception) {
            return new PathCheckResult("", false, ViolationReason.OUTSIDE_GAME_DIRECTORY, false, 0);
        }
        counters.examined.increment();
        String lookupPath = relativePath.toLowerCase(Locale.ROOT);
        if (!isManagedPath(lookupPath) || !normalizedPath.startsWith(normalizedGameDirectory)) {
            return new PathCheckResult(relativePath, false, ViolationReason.OUTSIDE_GAME_DIRECTORY, false, 0);
        }

        ManifestFileEntry expected = policy.requiredFiles.get(lookupPath);
        boolean required = expected != null;
        if (expected == null) {
            expected = policy.optionalFiles.get(lookupPath);
        }
        if (expected == null) {
            if (isInOptionalAllowedDirectory(lookupPath, policy.optionalDirectories)) {
                return new PathCheckResult(relativePath, true, ViolationReason.NONE, false, 0);
            }
            return new PathCheckResult(relativePath, false, ViolationReason.UNKNOWN_FILE, false, 0);
        }
        if (!Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS)) {
            return new PathCheckResult(relativePath, false,
                    required ? ViolationReason.REQUIRED_FILE_MISSING : ViolationReason.OPTIONAL_FILE_MODIFIED, false, 0);
        }

        String expectedHash = FileHashService.normalizeSha256(expected.getSha256());
        if (expectedHash.isEmpty()) {
            return new PathCheckResult(relativePath, false,
                    required ? ViolationReason.REQUIRED_FILE_MODIFIED : ViolationReason.OPTIONAL_FILE_MODIFIED, false, 0);
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    normalizedPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            FileFingerprint fingerprint = new FileFingerprint(attributes.size(), attributes.lastModifiedTime().toMillis());
            String actualHash = !forceHash && hashCache != null
                    ? hashCache.get(lookupPath, fingerprint)
                    : null;
            boolean hashed = actualHash == null;
            if (hashed) {
                actualHash = fileHashService.calculateSha256(normalizedPath);
                counters.hashed.increment();
                counters.hashedBytes.add(attributes.size());
                if (hashCache != null) {
                    hashCache.put(lookupPath, fingerprint, actualHash);
                }
            }
            boolean allowed = expectedHash.equals(actualHash);
            return new PathCheckResult(relativePath, allowed, allowed ? ViolationReason.NONE
                    : required ? ViolationReason.REQUIRED_FILE_MODIFIED : ViolationReason.OPTIONAL_FILE_MODIFIED,
                    hashed, hashed ? attributes.size() : 0);
        } catch (IOException | FileHashService.FileHashException exception) {
            return new PathCheckResult(relativePath, false,
                    required ? ViolationReason.REQUIRED_FILE_MODIFIED : ViolationReason.OPTIONAL_FILE_MODIFIED, false, 0);
        }
    }

    public static String normalizeManifestPath(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    public static boolean isManagedPath(String path) {
        String normalized = normalizeManifestPath(path).toLowerCase(Locale.ROOT);
        for (String directoryName : MANAGED_DIRECTORIES) {
            if (normalized.equals(directoryName) || normalized.startsWith(directoryName + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInOptionalAllowedDirectory(String path, Set<String> optionalDirectories) {
        for (String directory : optionalDirectories) {
            if (path.startsWith(directory)) {
                return true;
            }
        }
        return false;
    }

    private record Policy(
            Map<String, ManifestFileEntry> requiredFiles,
            Map<String, ManifestFileEntry> optionalFiles,
            Set<String> optionalDirectories
    ) {
        private static Policy from(LauncherManifest manifest) {
            Map<String, ManifestFileEntry> required = new HashMap<>();
            Map<String, ManifestFileEntry> optional = new HashMap<>();
            Set<String> directories = new HashSet<>();
            for (ManifestFileEntry file : manifest.getFiles()) {
                String path = normalizeManifestPath(file.getPath());
                if (isManagedPath(path)) {
                    required.put(path.toLowerCase(Locale.ROOT), file);
                }
            }
            for (ManifestFileEntry file : manifest.getOptionalAllowedFiles()) {
                String path = normalizeManifestPath(file.getPath());
                if (isManagedPath(path)) {
                    optional.put(path.toLowerCase(Locale.ROOT), file);
                }
            }
            for (ManifestDirectoryEntry directory : manifest.getOptionalAllowedDirectories()) {
                String path = normalizeManifestPath(directory.getPath());
                while (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                if (isManagedPath(path)) {
                    directories.add(path.toLowerCase(Locale.ROOT) + "/");
                }
            }
            return new Policy(Map.copyOf(required), Map.copyOf(optional), Set.copyOf(directories));
        }
    }

    private static final class ScanCounters {
        private final LongAdder examined = new LongAdder();
        private final LongAdder hashed = new LongAdder();
        private final LongAdder hashedBytes = new LongAdder();
    }

    public static final class HashCache {
        private final Map<String, CachedHash> entries = new ConcurrentHashMap<>();

        String get(String path, FileFingerprint fingerprint) {
            CachedHash cached = entries.get(path);
            return cached != null && cached.fingerprint.equals(fingerprint) ? cached.sha256 : null;
        }

        void put(String path, FileFingerprint fingerprint, String sha256) {
            entries.put(path, new CachedHash(fingerprint, sha256));
        }

        public void invalidate(String relativePath) {
            entries.remove(normalizeManifestPath(relativePath).toLowerCase(Locale.ROOT));
        }

        public int size() {
            return entries.size();
        }
    }

    private record FileFingerprint(long size, long modifiedMillis) { }
    private record CachedHash(FileFingerprint fingerprint, String sha256) { }

    public record ScanResult(List<String> violations, long examinedFiles, long hashedFiles, long hashedBytes,
                             long durationNanos) { }

    public record PathCheckResult(String relativePath, boolean allowed, ViolationReason reason,
                                  boolean hashed, long hashedBytes) { }

    public enum ViolationReason {
        NONE, UNKNOWN_FILE, REQUIRED_FILE_MISSING, REQUIRED_FILE_MODIFIED, OPTIONAL_FILE_MODIFIED,
        OUTSIDE_GAME_DIRECTORY
    }

    public static final class IntegrityCheckException extends RuntimeException {
        public IntegrityCheckException(String message) { super(message); }
        public IntegrityCheckException(String message, Throwable cause) { super(message, cause); }
    }
}
