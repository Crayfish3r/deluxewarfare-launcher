package com.makar.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class DefaultGameSettingsService {
    private static final String DEFAULT_OPTIONS_RESOURCE = "/defaults/options.txt";
    private static final String OPTIONS_FILE_NAME = "options.txt";
    private static final String APPLIED_MARKER_FILE_NAME = ".deluxewarfare-default-settings-applied";

    public void applyOnce(Path gameDirectory, Consumer<String> logConsumer) {
        Path markerPath = gameDirectory.resolve(APPLIED_MARKER_FILE_NAME);
        if (Files.exists(markerPath)) {
            return;
        }

        Path optionsPath = gameDirectory.resolve(OPTIONS_FILE_NAME);
        try {
            Files.createDirectories(gameDirectory);
            if (Files.exists(optionsPath)) {
                logConsumer.accept("Existing Minecraft settings detected. Keeping player options.");
            } else {
                copyDefaultOptions(optionsPath);
                logConsumer.accept("Applied DeluxeWarfare default Minecraft settings.");
            }

            Files.writeString(
                    markerPath,
                    "Default settings initialized by DeluxeWarfare Launcher.\n",
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize default Minecraft settings.", exception);
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
}
