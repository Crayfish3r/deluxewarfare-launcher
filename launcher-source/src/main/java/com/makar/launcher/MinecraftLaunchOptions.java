package com.makar.launcher;

import java.nio.file.Path;

public final class MinecraftLaunchOptions {
    private final String javaExecutable;
    private final Path gameDirectory;
    private final String nickname;
    private final String minecraftVersion;
    private final String forgeVersion;
    private final String serverHost;
    private final int serverPort;
    private final boolean autoJoinServer;
    private final Path skinSystemAgent;
    private final String playerUuid;
    private final int memoryGb;

    public MinecraftLaunchOptions(
            String javaExecutable,
            Path gameDirectory,
            String nickname,
            String minecraftVersion,
            String forgeVersion,
            String serverHost,
            int serverPort,
            boolean autoJoinServer,
            Path skinSystemAgent,
            String playerUuid,
            int memoryGb
    ) {
        this.javaExecutable = javaExecutable;
        this.gameDirectory = gameDirectory;
        this.nickname = nickname;
        this.minecraftVersion = minecraftVersion;
        this.forgeVersion = forgeVersion;
        this.serverHost = serverHost == null ? "" : serverHost.trim();
        this.serverPort = serverPort;
        this.autoJoinServer = autoJoinServer;
        this.skinSystemAgent = skinSystemAgent;
        this.playerUuid = playerUuid == null ? "" : playerUuid;
        this.memoryGb = Math.max(4, memoryGb);
    }

    public String getJavaExecutable() {
        return javaExecutable;
    }

    public Path getGameDirectory() {
        return gameDirectory;
    }

    public String getNickname() {
        return nickname;
    }

    public String getMinecraftVersion() {
        return minecraftVersion;
    }

    public String getForgeVersion() {
        return forgeVersion;
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public boolean isAutoJoinServer() {
        return autoJoinServer;
    }

    public boolean hasServerAddress() {
        return !serverHost.isBlank();
    }

    public boolean shouldAutoJoinServer() {
        return autoJoinServer && hasServerAddress();
    }

    public String getServerAddress() {
        if (!hasServerAddress()) {
            return "";
        }

        return serverHost + ":" + serverPort;
    }

    public String getForgeVersionName() {
        return minecraftVersion + "-forge-" + forgeVersion;
    }

    public Path getSkinSystemAgent() {
        return skinSystemAgent;
    }

    public boolean isSkinSystemEnabled() {
        return skinSystemAgent != null;
    }

    public String getPlayerUuid() {
        return playerUuid;
    }

    public int getMemoryGb() {
        return memoryGb;
    }
}
