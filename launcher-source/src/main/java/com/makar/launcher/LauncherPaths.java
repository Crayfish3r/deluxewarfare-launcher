package com.makar.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LauncherPaths {
    private static final String CONFIG_DIRECTORY_NAME = ".tactical-launcher";
    private static final String GAME_DIRECTORY_NAME = "game";

    private LauncherPaths() {
    }

    public static Path getConfigDirectory() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, CONFIG_DIRECTORY_NAME);
            }
        }

        return Path.of(System.getProperty("user.home"), CONFIG_DIRECTORY_NAME);
    }

    public static Path createConfigDirectory() {
        Path configDirectory = getConfigDirectory();
        try {
            return Files.createDirectories(configDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create launcher config directory", exception);
        }
    }

    public static Path getGameDirectory() {
        return getDefaultGameDirectory();
    }

    public static Path getDefaultGameDirectory() {
        return Path.of(System.getProperty("user.home"), CONFIG_DIRECTORY_NAME, GAME_DIRECTORY_NAME);
    }

    public static Path getGameDirectory(LauncherConfig config) {
        return Path.of(config.getGameDir());
    }

    public static Path createGameDirectory() {
        Path gameDirectory = getDefaultGameDirectory();
        return createDirectory(gameDirectory, "launcher game directory");
    }

    public static Path createGameDirectory(LauncherConfig config) {
        Path gameDirectory = getGameDirectory(config);
        return createDirectory(gameDirectory, "launcher game directory");
    }

    public static Path getLogFile() {
        return getConfigDirectory().resolve("logs").resolve("launcher-latest.log");
    }

    private static Path createDirectory(Path directory, String description) {
        try {
            return Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create " + description, exception);
        }
    }
}
