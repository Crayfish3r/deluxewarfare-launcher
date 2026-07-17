package com.makar.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class ManagedFoldersIntegrityService {
    private static final List<String> MANAGED_DIRECTORIES = List.of(
            "mods",
            "resourcepacks",
            "shaderpacks",
            "tacz"
    );

    private final FileHashService fileHashService = new FileHashService();

    public List<String> findUnknownFiles(LauncherManifest manifest, Path gameDirectory) {
        Set<String> requiredAllowedPaths = getRequiredAllowedManagedPaths(manifest);
        Map<String, ManifestFileEntry> optionalAllowedFiles = getOptionalAllowedManagedFiles(manifest);
        Set<String> optionalAllowedDirectories = getOptionalAllowedManagedDirectories(manifest);
        List<String> unknownFiles = new ArrayList<>();

        for (String directoryName : MANAGED_DIRECTORIES) {
            Path managedDirectory = gameDirectory.resolve(directoryName);
            if (!Files.exists(managedDirectory, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }

            try (Stream<Path> paths = Files.walk(managedDirectory)) {
                paths.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .map(path -> normalizeRelativePath(gameDirectory, path))
                        .filter(path -> !isAllowedManagedFile(
                                gameDirectory,
                                path,
                                requiredAllowedPaths,
                                optionalAllowedFiles,
                                optionalAllowedDirectories
                        ))
                        .forEach(unknownFiles::add);
            } catch (IOException exception) {
                throw new IntegrityCheckException("Unable to validate managed directory: " + managedDirectory, exception);
            }
        }

        unknownFiles.sort(String.CASE_INSENSITIVE_ORDER);
        return unknownFiles;
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

    private boolean isAllowedManagedFile(
            Path gameDirectory,
            String relativePath,
            Set<String> requiredAllowedPaths,
            Map<String, ManifestFileEntry> optionalAllowedFiles,
            Set<String> optionalAllowedDirectories
    ) {
        String normalizedPath = normalizeManifestPath(relativePath);
        String lookupPath = normalizedPath.toLowerCase(Locale.ROOT);
        if (requiredAllowedPaths.contains(lookupPath)) {
            return true;
        }

        if (isInOptionalAllowedDirectory(lookupPath, optionalAllowedDirectories)) {
            return true;
        }

        ManifestFileEntry optionalFile = optionalAllowedFiles.get(lookupPath);
        if (optionalFile == null) {
            return false;
        }

        String expectedHash = FileHashService.normalizeSha256(optionalFile.getSha256());
        if (expectedHash.isEmpty()) {
            return false;
        }

        Path target = gameDirectory.toAbsolutePath().normalize().resolve(normalizedPath).normalize();
        if (!target.startsWith(gameDirectory.toAbsolutePath().normalize())) {
            return false;
        }

        try {
            return expectedHash.equals(fileHashService.calculateSha256(target));
        } catch (FileHashService.FileHashException exception) {
            return false;
        }
    }

    private Set<String> getRequiredAllowedManagedPaths(LauncherManifest manifest) {
        Set<String> allowedPaths = new HashSet<>();
        for (ManifestFileEntry file : manifest.getFiles()) {
            String path = normalizeManifestPath(file.getPath());
            if (isManagedPath(path)) {
                allowedPaths.add(path.toLowerCase(Locale.ROOT));
            }
        }
        return allowedPaths;
    }

    private Map<String, ManifestFileEntry> getOptionalAllowedManagedFiles(LauncherManifest manifest) {
        Map<String, ManifestFileEntry> allowedFiles = new HashMap<>();
        for (ManifestFileEntry file : manifest.getOptionalAllowedFiles()) {
            String path = normalizeManifestPath(file.getPath());
            if (isManagedPath(path)) {
                allowedFiles.put(path.toLowerCase(Locale.ROOT), file);
            }
        }
        return allowedFiles;
    }

    private Set<String> getOptionalAllowedManagedDirectories(LauncherManifest manifest) {
        Set<String> allowedDirectories = new HashSet<>();
        for (ManifestDirectoryEntry directory : manifest.getOptionalAllowedDirectories()) {
            String path = normalizeDirectoryPath(directory.getPath());
            if (isManagedPath(path)) {
                allowedDirectories.add(path.toLowerCase(Locale.ROOT));
            }
        }
        return allowedDirectories;
    }

    private boolean isInOptionalAllowedDirectory(String path, Set<String> optionalAllowedDirectories) {
        for (String directory : optionalAllowedDirectories) {
            if (path.startsWith(directory)) {
                return true;
            }
        }
        return false;
    }

    private boolean isManagedPath(String path) {
        for (String directoryName : MANAGED_DIRECTORIES) {
            if (path.equals(directoryName) || path.startsWith(directoryName + "/")) {
                return true;
            }
        }
        return false;
    }

    private String normalizeManifestPath(String path) {
        return path == null ? "" : path.trim().replace('\\', '/');
    }

    private String normalizeDirectoryPath(String path) {
        String normalizedPath = normalizeManifestPath(path);
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        while (normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        return normalizedPath.isEmpty() ? "" : normalizedPath + "/";
    }

    private String normalizeRelativePath(Path gameDirectory, Path path) {
        return gameDirectory.toAbsolutePath()
                .normalize()
                .relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    public static final class IntegrityCheckException extends RuntimeException {
        public IntegrityCheckException(String message) {
            super(message);
        }

        public IntegrityCheckException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
