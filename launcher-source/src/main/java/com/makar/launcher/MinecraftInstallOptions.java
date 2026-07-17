package com.makar.launcher;

import java.nio.file.Path;

public final class MinecraftInstallOptions {
    private final String javaExecutable;
    private final Path gameDirectory;
    private final String minecraftVersion;
    private final String forgeVersion;

    public MinecraftInstallOptions(
            String javaExecutable,
            Path gameDirectory,
            String minecraftVersion,
            String forgeVersion
    ) {
        this.javaExecutable = javaExecutable;
        this.gameDirectory = gameDirectory;
        this.minecraftVersion = minecraftVersion;
        this.forgeVersion = forgeVersion;
    }

    public String getJavaExecutable() {
        return javaExecutable;
    }

    public Path getGameDirectory() {
        return gameDirectory;
    }

    public String getMinecraftVersion() {
        return minecraftVersion;
    }

    public String getForgeVersion() {
        return forgeVersion;
    }

    public String getForgeVersionName() {
        return minecraftVersion + "-forge-" + forgeVersion;
    }
}
