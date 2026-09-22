package com.makar.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class DefaultGameSettingsService {
    private static final String DEFAULT_OPTIONS_RESOURCE = "/defaults/options.txt";
    private static final String OPTIONS_FILE_NAME = "options.txt";
    private static final String LEGACY_MARKER_FILE_NAME = ".deluxewarfare-default-settings-applied";
    private static final String VERSION_MARKER_FILE_NAME = ".deluxewarfare-default-settings-version";
    private static final int CURRENT_MIGRATION_VERSION = 2;
    private static final Map<String, Migration> V2_MIGRATIONS = createV2Migrations();

    public void applyOnce(Path gameDirectory, Consumer<String> logConsumer) {
        Path markerPath = gameDirectory.resolve(VERSION_MARKER_FILE_NAME);
        int appliedVersion = readAppliedVersion(markerPath);
        if (appliedVersion >= CURRENT_MIGRATION_VERSION) {
            return;
        }

        Path optionsPath = gameDirectory.resolve(OPTIONS_FILE_NAME);
        try {
            Files.createDirectories(gameDirectory);
            if (!Files.exists(optionsPath)) {
                copyDefaultOptions(optionsPath);
                logConsumer.accept("Applied DeluxeWarfare default Minecraft settings v" + CURRENT_MIGRATION_VERSION + ".");
            } else {
                int changed = migrateExistingOptions(optionsPath);
                logConsumer.accept(changed == 0
                        ? "Minecraft settings migration preserved all player options."
                        : "Migrated " + changed + " legacy launcher Minecraft settings; player overrides were preserved.");
            }
            writeVersionAtomically(markerPath);
            Files.deleteIfExists(gameDirectory.resolve(LEGACY_MARKER_FILE_NAME));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize default Minecraft settings.", exception);
        }
    }

    int migrateExistingOptions(Path optionsPath) throws IOException {
        List<String> source = Files.readAllLines(optionsPath, StandardCharsets.UTF_8);
        List<String> migrated = new ArrayList<>(source.size());
        int changes = 0;
        for (String line : source) {
            int separator = line.indexOf(':');
            if (separator < 0) {
                migrated.add(line);
                continue;
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            Migration migration = V2_MIGRATIONS.get(key);
            if (migration != null && migration.oldValue.equals(value)) {
                if (migration.newValue != null) {
                    migrated.add(key + ":" + migration.newValue);
                }
                changes++;
            } else {
                migrated.add(line);
            }
        }
        if (changes > 0) {
            Path temporary = optionsPath.resolveSibling(optionsPath.getFileName() + ".migration.tmp");
            Files.write(temporary, migrated, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, optionsPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveException) {
                Files.move(temporary, optionsPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return changes;
    }

    private int readAppliedVersion(Path markerPath) {
        try {
            if (!Files.isRegularFile(markerPath)) {
                return 0;
            }
            return Integer.parseInt(Files.readString(markerPath, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException ignored) {
            return 0;
        }
    }

    private void writeVersionAtomically(Path markerPath) throws IOException {
        Path temporary = markerPath.resolveSibling(markerPath.getFileName() + ".tmp");
        Files.writeString(temporary, Integer.toString(CURRENT_MIGRATION_VERSION), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, markerPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveException) {
            Files.move(temporary, markerPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void copyDefaultOptions(Path optionsPath) throws IOException {
        try (InputStream inputStream = DefaultGameSettingsService.class.getResourceAsStream(DEFAULT_OPTIONS_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Default Minecraft settings resource was not found.");
            }
            Files.copy(inputStream, optionsPath);
        }
    }

    private static Map<String, Migration> createV2Migrations() {
        Map<String, Migration> migrations = new LinkedHashMap<>();
        migrations.put("entityDistanceScaling", new Migration("5.0", "1.0"));
        migrations.put("simulationDistance", new Migration("12", "8"));
        migrations.put("graphicsMode", new Migration("1", "0"));
        migrations.put("entityShadows", new Migration("true", "false"));
        migrations.put("renderClouds", new Migration("\"true\"", "\"false\""));
        migrations.put("syncChunkWrites", new Migration("true", "false"));
        migrations.put("fullscreenResolution", new Migration("1920x1080@120:24", null));
        return Map.copyOf(migrations);
    }

    private record Migration(String oldValue, String newValue) { }
}
