package com.makar.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public final class LauncherConfig {
    private static final String CONFIG_FILE_NAME = "launcher.properties";

    private static final int CURRENT_CONFIG_VERSION = 8;

    private static final String DEFAULT_MANIFEST_URL =
            "https://Crayfish3r.github.io/deluxewarfare-launcher/launcher_manifest.json";
    private static final String DEFAULT_YANDEX_DISK_PUBLIC_URL =
            "https://disk.yandex.ru/d/UTf9nb2ZDDlJ0A";
    private static final String DEFAULT_BACKEND_URL =
            "https://auth.deluxe-warfare.ru";
    private static final String DEFAULT_LAUNCHER_UPDATE_URL =
            "https://Crayfish3r.github.io/deluxewarfare-launcher/launcher/latest.json";
    private static final String DEFAULT_DONATION_PRODUCTS_URL =
            "https://Crayfish3r.github.io/deluxewarfare-launcher/donation_products.json";
    private static final String DEFAULT_DONATION_ALERTS_URL =
            "https://www.donationalerts.com/r/zumadeluxe2004";
    private static final String DEFAULT_SERVER_HOST = "deluxewarfare.sosal.today";
    private static final int DEFAULT_SERVER_PORT = 25565;
    private static final boolean DEFAULT_AUTO_JOIN_SERVER = true;
    private static final boolean DEFAULT_DISCORD_AUTH_ENABLED = true;
    private static final boolean DEFAULT_BACKEND_AUTH_ENABLED = true;
    private static final boolean DEFAULT_SKIN_SYSTEM_ENABLED = true;
    private static final boolean DEFAULT_RUNTIME_MANAGED_FOLDER_PROTECTION_ENABLED = true;
    private static final boolean DEFAULT_TERMINATE_GAME_ON_RUNTIME_FORBIDDEN_FILE = true;
    private static final int DEFAULT_RUNTIME_MANAGED_FOLDER_SCAN_INTERVAL_SECONDS = 3;
    private static final int DEFAULT_MEMORY_GB = 4;

    private static final String LEGACY_DEFAULT_MANIFEST_URL = "http://localhost:3000/manifest";
    private static final String LEGACY_DEFAULT_BACKEND_URL = "http://localhost:3000";
    private static final String LEGACY_WORKERS_BACKEND_URL =
            "https://deluxe-warfare-auth.zuma-deluxe.workers.dev";
    private static final String LEGACY_DEFAULT_SERVER_HOST = "zuma.sos-al.net";
    private static final String LEGACY_DEFAULT_DONATION_ALERTS_URL =
            "https://www.donationalerts.com/r/USERNAME";

    private static final String CONFIG_VERSION_KEY = "configVersion";
    private static final String NICKNAME_KEY = "nickname";
    private static final String MANIFEST_URL_KEY = "manifestUrl";
    private static final String YANDEX_DISK_PUBLIC_URL_KEY = "yandexDiskPublicUrl";
    private static final String BACKEND_URL_KEY = "backendUrl";
    private static final String LAUNCHER_UPDATE_URL_KEY = "launcherUpdateUrl";
    private static final String DONATION_PRODUCTS_URL_KEY = "donationProductsUrl";
    private static final String DONATION_ALERTS_URL_KEY = "donationAlertsUrl";
    private static final String GAME_DIR_KEY = "gameDir";
    private static final String JAVA_PATH_KEY = "javaPath";
    private static final String SERVER_HOST_KEY = "serverHost";
    private static final String SERVER_PORT_KEY = "serverPort";
    private static final String AUTO_JOIN_SERVER_KEY = "autoJoinServer";
    private static final String DISCORD_AUTH_ENABLED_KEY = "discordAuthEnabled";
    private static final String BACKEND_AUTH_ENABLED_KEY = "backendAuthEnabled";
    private static final String SKIN_SYSTEM_ENABLED_KEY = "skinSystemEnabled";
    private static final String LAUNCHER_SESSION_TOKEN_KEY = "launcherSessionToken";
    private static final String DISCORD_USERNAME_KEY = "discordUsername";
    private static final String DISCORD_USER_ID_KEY = "discordUserId";
    private static final String RUNTIME_MANAGED_FOLDER_PROTECTION_ENABLED_KEY =
            "runtimeManagedFolderProtectionEnabled";
    private static final String TERMINATE_GAME_ON_RUNTIME_FORBIDDEN_FILE_KEY =
            "terminateGameOnRuntimeForbiddenFile";
    private static final String RUNTIME_MANAGED_FOLDER_SCAN_INTERVAL_SECONDS_KEY =
            "runtimeManagedFolderScanIntervalSeconds";
    private static final String MEMORY_GB_KEY = "memoryGb";

    private String nickname = "";
    private String manifestUrl = DEFAULT_MANIFEST_URL;
    private String yandexDiskPublicUrl = DEFAULT_YANDEX_DISK_PUBLIC_URL;
    private String backendUrl = DEFAULT_BACKEND_URL;
    private String launcherUpdateUrl = DEFAULT_LAUNCHER_UPDATE_URL;
    private String donationProductsUrl = DEFAULT_DONATION_PRODUCTS_URL;
    private String donationAlertsUrl = DEFAULT_DONATION_ALERTS_URL;
    private String gameDir = LauncherPaths.getDefaultGameDirectory().toString();
    private String javaPath = "";
    private String serverHost = DEFAULT_SERVER_HOST;
    private int serverPort = DEFAULT_SERVER_PORT;
    private boolean autoJoinServer = DEFAULT_AUTO_JOIN_SERVER;
    private boolean discordAuthEnabled = DEFAULT_DISCORD_AUTH_ENABLED;
    private boolean backendAuthEnabled = DEFAULT_BACKEND_AUTH_ENABLED;
    private boolean skinSystemEnabled = DEFAULT_SKIN_SYSTEM_ENABLED;
    private String launcherSessionToken = "";
    private String discordUsername = "";
    private String discordUserId = "";
    private boolean runtimeManagedFolderProtectionEnabled = DEFAULT_RUNTIME_MANAGED_FOLDER_PROTECTION_ENABLED;
    private boolean terminateGameOnRuntimeForbiddenFile = DEFAULT_TERMINATE_GAME_ON_RUNTIME_FORBIDDEN_FILE;
    private int runtimeManagedFolderScanIntervalSeconds = DEFAULT_RUNTIME_MANAGED_FOLDER_SCAN_INTERVAL_SECONDS;
    private int memoryGb = DEFAULT_MEMORY_GB;

    public static LauncherConfig load() {
        LauncherConfig config = new LauncherConfig();
        Path configFile = LauncherPaths.getConfigDirectory().resolve(CONFIG_FILE_NAME);

        if (!Files.exists(configFile)) {
            config.save();
            return config;
        }

        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(configFile)) {
            properties.load(inputStream);

            int loadedConfigVersion = parseInteger(properties.getProperty(CONFIG_VERSION_KEY, "0"), 0);
            boolean needsProductionMigration = loadedConfigVersion < CURRENT_CONFIG_VERSION;
            boolean legacyDefaultConfig = isLegacyDefaultConfig(properties);

            config.nickname = properties.getProperty(NICKNAME_KEY, "");
            config.gameDir = properties.getProperty(GAME_DIR_KEY, LauncherPaths.getDefaultGameDirectory().toString());
            config.launcherSessionToken = properties.getProperty(LAUNCHER_SESSION_TOKEN_KEY, "");
            config.discordUsername = properties.getProperty(DISCORD_USERNAME_KEY, "");
            config.discordUserId = properties.getProperty(DISCORD_USER_ID_KEY, "");

            if (needsProductionMigration || legacyDefaultConfig) {
                config.manifestUrl = DEFAULT_MANIFEST_URL;
                config.backendUrl = DEFAULT_BACKEND_URL;
                config.launcherUpdateUrl = DEFAULT_LAUNCHER_UPDATE_URL;
                config.donationProductsUrl = DEFAULT_DONATION_PRODUCTS_URL;
                config.donationAlertsUrl = DEFAULT_DONATION_ALERTS_URL;
                config.javaPath = "";
                config.serverHost = DEFAULT_SERVER_HOST;
                config.serverPort = DEFAULT_SERVER_PORT;
                config.autoJoinServer = DEFAULT_AUTO_JOIN_SERVER;
                config.discordAuthEnabled = DEFAULT_DISCORD_AUTH_ENABLED;
                config.backendAuthEnabled = DEFAULT_BACKEND_AUTH_ENABLED;
                config.skinSystemEnabled = DEFAULT_SKIN_SYSTEM_ENABLED;
                config.runtimeManagedFolderProtectionEnabled = DEFAULT_RUNTIME_MANAGED_FOLDER_PROTECTION_ENABLED;
                config.terminateGameOnRuntimeForbiddenFile = DEFAULT_TERMINATE_GAME_ON_RUNTIME_FORBIDDEN_FILE;
                config.runtimeManagedFolderScanIntervalSeconds = DEFAULT_RUNTIME_MANAGED_FOLDER_SCAN_INTERVAL_SECONDS;
                config.memoryGb = DEFAULT_MEMORY_GB;
                config.save();
                return config;
            }

            config.manifestUrl = properties.getProperty(MANIFEST_URL_KEY, DEFAULT_MANIFEST_URL);
            config.yandexDiskPublicUrl = properties.getProperty(
                    YANDEX_DISK_PUBLIC_URL_KEY,
                    DEFAULT_YANDEX_DISK_PUBLIC_URL).trim();
            String backendUrlProperty = properties.getProperty(BACKEND_URL_KEY, DEFAULT_BACKEND_URL);
            boolean backendUrlMigrated = isLegacyWorkersBackendUrl(backendUrlProperty);
            config.backendUrl = migrateBackendUrl(backendUrlProperty);
            config.launcherUpdateUrl = properties.getProperty(LAUNCHER_UPDATE_URL_KEY, DEFAULT_LAUNCHER_UPDATE_URL);
            config.donationProductsUrl = properties.getProperty(DONATION_PRODUCTS_URL_KEY, DEFAULT_DONATION_PRODUCTS_URL);
            config.donationAlertsUrl = properties.getProperty(DONATION_ALERTS_URL_KEY, DEFAULT_DONATION_ALERTS_URL);
            config.javaPath = properties.getProperty(JAVA_PATH_KEY, "");
            config.serverHost = properties.getProperty(SERVER_HOST_KEY, DEFAULT_SERVER_HOST);
            config.serverPort = parseServerPort(properties.getProperty(
                    SERVER_PORT_KEY,
                    Integer.toString(DEFAULT_SERVER_PORT)));
            config.autoJoinServer = Boolean.parseBoolean(properties.getProperty(
                    AUTO_JOIN_SERVER_KEY,
                    Boolean.toString(DEFAULT_AUTO_JOIN_SERVER)));
            config.discordAuthEnabled = Boolean.parseBoolean(properties.getProperty(
                    DISCORD_AUTH_ENABLED_KEY,
                    Boolean.toString(DEFAULT_DISCORD_AUTH_ENABLED)));
            config.backendAuthEnabled = Boolean.parseBoolean(properties.getProperty(
                    BACKEND_AUTH_ENABLED_KEY,
                    Boolean.toString(DEFAULT_BACKEND_AUTH_ENABLED)));
            config.skinSystemEnabled = Boolean.parseBoolean(properties.getProperty(
                    SKIN_SYSTEM_ENABLED_KEY,
                    Boolean.toString(DEFAULT_SKIN_SYSTEM_ENABLED)));
            config.runtimeManagedFolderProtectionEnabled = Boolean.parseBoolean(properties.getProperty(
                    RUNTIME_MANAGED_FOLDER_PROTECTION_ENABLED_KEY,
                    Boolean.toString(DEFAULT_RUNTIME_MANAGED_FOLDER_PROTECTION_ENABLED)));
            config.terminateGameOnRuntimeForbiddenFile = Boolean.parseBoolean(properties.getProperty(
                    TERMINATE_GAME_ON_RUNTIME_FORBIDDEN_FILE_KEY,
                    Boolean.toString(DEFAULT_TERMINATE_GAME_ON_RUNTIME_FORBIDDEN_FILE)));
            config.runtimeManagedFolderScanIntervalSeconds = parsePositiveInteger(properties.getProperty(
                    RUNTIME_MANAGED_FOLDER_SCAN_INTERVAL_SECONDS_KEY,
                    Integer.toString(DEFAULT_RUNTIME_MANAGED_FOLDER_SCAN_INTERVAL_SECONDS)),
                    DEFAULT_RUNTIME_MANAGED_FOLDER_SCAN_INTERVAL_SECONDS);
            config.memoryGb = parsePositiveInteger(properties.getProperty(
                    MEMORY_GB_KEY,
                    Integer.toString(DEFAULT_MEMORY_GB)),
                    DEFAULT_MEMORY_GB);
            config.memoryGb = Math.max(DEFAULT_MEMORY_GB, config.memoryGb);

            if (LEGACY_DEFAULT_SERVER_HOST.equalsIgnoreCase(config.serverHost)) {
                config.serverHost = DEFAULT_SERVER_HOST;
                config.save();
            }
            if (LEGACY_DEFAULT_DONATION_ALERTS_URL.equalsIgnoreCase(config.donationAlertsUrl)
                    || config.donationAlertsUrl.contains("USERNAME")) {
                config.donationAlertsUrl = DEFAULT_DONATION_ALERTS_URL;
                config.save();
            }
            if (backendUrlMigrated) {
                config.save();
            }
        } catch (IOException ignored) {
            config.nickname = "";
            config.manifestUrl = DEFAULT_MANIFEST_URL;
            config.backendUrl = DEFAULT_BACKEND_URL;
            config.launcherUpdateUrl = DEFAULT_LAUNCHER_UPDATE_URL;
            config.donationProductsUrl = DEFAULT_DONATION_PRODUCTS_URL;
            config.donationAlertsUrl = DEFAULT_DONATION_ALERTS_URL;
            config.gameDir = LauncherPaths.getDefaultGameDirectory().toString();
            config.javaPath = "";
            config.serverHost = DEFAULT_SERVER_HOST;
            config.serverPort = DEFAULT_SERVER_PORT;
            config.autoJoinServer = DEFAULT_AUTO_JOIN_SERVER;
            config.discordAuthEnabled = DEFAULT_DISCORD_AUTH_ENABLED;
            config.backendAuthEnabled = DEFAULT_BACKEND_AUTH_ENABLED;
            config.skinSystemEnabled = DEFAULT_SKIN_SYSTEM_ENABLED;
            config.launcherSessionToken = "";
            config.discordUsername = "";
            config.discordUserId = "";
            config.runtimeManagedFolderProtectionEnabled = DEFAULT_RUNTIME_MANAGED_FOLDER_PROTECTION_ENABLED;
            config.terminateGameOnRuntimeForbiddenFile = DEFAULT_TERMINATE_GAME_ON_RUNTIME_FORBIDDEN_FILE;
            config.runtimeManagedFolderScanIntervalSeconds = DEFAULT_RUNTIME_MANAGED_FOLDER_SCAN_INTERVAL_SECONDS;
            config.memoryGb = DEFAULT_MEMORY_GB;
        }

        return config;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname == null ? "" : nickname;
    }

    public String getManifestUrl() {
        return manifestUrl;
    }

    public void setManifestUrl(String manifestUrl) {
        this.manifestUrl = manifestUrl == null || manifestUrl.isBlank()
                ? DEFAULT_MANIFEST_URL
                : manifestUrl.trim();
    }

    public String getYandexDiskPublicUrl() {
        return yandexDiskPublicUrl;
    }

    public void setYandexDiskPublicUrl(String yandexDiskPublicUrl) {
        this.yandexDiskPublicUrl = yandexDiskPublicUrl == null
                ? ""
                : yandexDiskPublicUrl.trim();
    }

    public String getBackendUrl() {
        return backendUrl;
    }

    public void setBackendUrl(String backendUrl) {
        this.backendUrl = backendUrl == null || backendUrl.isBlank()
                ? DEFAULT_BACKEND_URL
                : normalizeBackendUrl(backendUrl);
    }

    public String getLauncherUpdateUrl() {
        return launcherUpdateUrl;
    }

    public void setLauncherUpdateUrl(String launcherUpdateUrl) {
        this.launcherUpdateUrl = launcherUpdateUrl == null || launcherUpdateUrl.isBlank()
                ? DEFAULT_LAUNCHER_UPDATE_URL
                : launcherUpdateUrl.trim();
    }

    public String getDonationProductsUrl() {
        return donationProductsUrl;
    }

    public String getDonationAlertsUrl() {
        return donationAlertsUrl;
    }

    public String getGameDir() {
        return gameDir;
    }

    public void setGameDir(String gameDir) {
        this.gameDir = gameDir == null || gameDir.isBlank()
                ? LauncherPaths.getDefaultGameDirectory().toString()
                : gameDir;
    }

    public String getJavaPath() {
        return javaPath;
    }

    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath == null ? "" : javaPath;
    }

    public String getServerHost() {
        return serverHost;
    }

    public void setServerHost(String serverHost) {
        this.serverHost = serverHost == null || serverHost.isBlank()
                ? DEFAULT_SERVER_HOST
                : serverHost.trim();
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort > 0 && serverPort <= 65535
                ? serverPort
                : DEFAULT_SERVER_PORT;
    }

    public boolean isAutoJoinServer() {
        return autoJoinServer;
    }

    public void setAutoJoinServer(boolean autoJoinServer) {
        this.autoJoinServer = autoJoinServer;
    }

    public boolean isDiscordAuthEnabled() {
        return discordAuthEnabled;
    }

    public void setDiscordAuthEnabled(boolean discordAuthEnabled) {
        this.discordAuthEnabled = discordAuthEnabled;
    }

    public boolean isBackendAuthEnabled() {
        return backendAuthEnabled;
    }

    public void setBackendAuthEnabled(boolean backendAuthEnabled) {
        this.backendAuthEnabled = backendAuthEnabled;
    }

    public boolean isSkinSystemEnabled() {
        return skinSystemEnabled;
    }

    public void setSkinSystemEnabled(boolean skinSystemEnabled) {
        this.skinSystemEnabled = skinSystemEnabled;
    }

    public String getLauncherSessionToken() {
        return launcherSessionToken;
    }

    public void setLauncherSessionToken(String launcherSessionToken) {
        this.launcherSessionToken = launcherSessionToken == null ? "" : launcherSessionToken;
    }

    public String getDiscordUsername() {
        return discordUsername;
    }

    public void setDiscordUsername(String discordUsername) {
        this.discordUsername = discordUsername == null ? "" : discordUsername;
    }

    public String getDiscordUserId() {
        return discordUserId;
    }

    public void setDiscordUserId(String discordUserId) {
        this.discordUserId = discordUserId == null ? "" : discordUserId;
    }

    public boolean isRuntimeManagedFolderProtectionEnabled() {
        return runtimeManagedFolderProtectionEnabled;
    }

    public boolean isTerminateGameOnRuntimeForbiddenFile() {
        return terminateGameOnRuntimeForbiddenFile;
    }

    public int getRuntimeManagedFolderScanIntervalSeconds() {
        return runtimeManagedFolderScanIntervalSeconds;
    }

    public int getMemoryGb() {
        return Math.max(DEFAULT_MEMORY_GB, memoryGb);
    }

    public void setMemoryGb(int memoryGb) {
        this.memoryGb = Math.max(DEFAULT_MEMORY_GB, memoryGb);
    }

    public void save() {
        Path configDirectory = LauncherPaths.createConfigDirectory();
        Path configFile = configDirectory.resolve(CONFIG_FILE_NAME);
        Path temporaryConfigFile = configDirectory.resolve(CONFIG_FILE_NAME + ".tmp");

        try {
            Properties properties = new Properties();

            properties.setProperty(CONFIG_VERSION_KEY, Integer.toString(CURRENT_CONFIG_VERSION));
            properties.setProperty(NICKNAME_KEY, nickname);
            properties.setProperty(MANIFEST_URL_KEY, manifestUrl);
            properties.setProperty(YANDEX_DISK_PUBLIC_URL_KEY, yandexDiskPublicUrl);
            properties.setProperty(BACKEND_URL_KEY, backendUrl);
            properties.setProperty(LAUNCHER_UPDATE_URL_KEY, launcherUpdateUrl);
            properties.setProperty(DONATION_PRODUCTS_URL_KEY, donationProductsUrl);
            properties.setProperty(DONATION_ALERTS_URL_KEY, donationAlertsUrl);
            properties.setProperty(GAME_DIR_KEY, gameDir);
            properties.setProperty(JAVA_PATH_KEY, javaPath);
            properties.setProperty(SERVER_HOST_KEY, serverHost);
            properties.setProperty(SERVER_PORT_KEY, Integer.toString(serverPort));
            properties.setProperty(AUTO_JOIN_SERVER_KEY, Boolean.toString(autoJoinServer));
            properties.setProperty(DISCORD_AUTH_ENABLED_KEY, Boolean.toString(discordAuthEnabled));
            properties.setProperty(BACKEND_AUTH_ENABLED_KEY, Boolean.toString(backendAuthEnabled));
            properties.setProperty(SKIN_SYSTEM_ENABLED_KEY, Boolean.toString(skinSystemEnabled));
            properties.setProperty(LAUNCHER_SESSION_TOKEN_KEY, launcherSessionToken);
            properties.setProperty(DISCORD_USERNAME_KEY, discordUsername);
            properties.setProperty(DISCORD_USER_ID_KEY, discordUserId);
            properties.setProperty(
                    RUNTIME_MANAGED_FOLDER_PROTECTION_ENABLED_KEY,
                    Boolean.toString(runtimeManagedFolderProtectionEnabled));
            properties.setProperty(
                    TERMINATE_GAME_ON_RUNTIME_FORBIDDEN_FILE_KEY,
                    Boolean.toString(terminateGameOnRuntimeForbiddenFile));
            properties.setProperty(
                    RUNTIME_MANAGED_FOLDER_SCAN_INTERVAL_SECONDS_KEY,
                    Integer.toString(runtimeManagedFolderScanIntervalSeconds));
            properties.setProperty(MEMORY_GB_KEY, Integer.toString(getMemoryGb()));

            try (OutputStream outputStream = Files.newOutputStream(temporaryConfigFile)) {
                properties.store(outputStream, "Tactical Launcher settings");
            }
            replaceConfigFile(temporaryConfigFile, configFile);
        } catch (IOException exception) {
            deleteQuietly(temporaryConfigFile);
            throw new IllegalStateException("Unable to save launcher config", exception);
        }
    }

    private static void replaceConfigFile(Path temporaryConfigFile, Path configFile) throws IOException {
        try {
            Files.move(
                    temporaryConfigFile,
                    configFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException atomicMoveException) {
            try {
                Files.move(temporaryConfigFile, configFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackException) {
                fallbackException.addSuppressed(atomicMoveException);
                throw fallbackException;
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static boolean isLegacyDefaultConfig(Properties properties) {
        String manifestUrl = properties.getProperty(MANIFEST_URL_KEY, "");
        String backendUrl = normalizeBackendUrl(properties.getProperty(BACKEND_URL_KEY, ""));
        String serverHost = properties.getProperty(SERVER_HOST_KEY, "");
        String discordAuthEnabled = properties.getProperty(DISCORD_AUTH_ENABLED_KEY, "false");
        String backendAuthEnabled = properties.getProperty(BACKEND_AUTH_ENABLED_KEY, "false");

        return LEGACY_DEFAULT_MANIFEST_URL.equals(manifestUrl)
                && LEGACY_DEFAULT_BACKEND_URL.equals(backendUrl)
                && serverHost.isBlank()
                && !Boolean.parseBoolean(discordAuthEnabled)
                && !Boolean.parseBoolean(backendAuthEnabled);
    }

    private static String migrateBackendUrl(String value) {
        String backendUrl = normalizeBackendUrl(value);
        return isLegacyWorkersBackendUrl(backendUrl)
                ? DEFAULT_BACKEND_URL
                : backendUrl;
    }

    private static boolean isLegacyWorkersBackendUrl(String value) {
        return LEGACY_WORKERS_BACKEND_URL.equals(normalizeBackendUrl(value));
    }

    private static int parseServerPort(String value) {
        return parsePort(value, DEFAULT_SERVER_PORT);
    }

    private static int parsePort(String value, int fallback) {
        int parsed = parseInteger(value, fallback);
        return parsed > 0 && parsed <= 65535 ? parsed : fallback;
    }

    private static int parseInteger(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parsePositiveInteger(String value, int fallback) {
        int parsed = parseInteger(value, fallback);
        return parsed > 0 ? parsed : fallback;
    }

    private static String normalizeBackendUrl(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_BACKEND_URL;
        }

        return stripTrailingSlash(value.trim());
    }

    private static String stripTrailingSlash(String value) {
        while (value.endsWith("/") && value.length() > "https://".length()) {
            value = value.substring(0, value.length() - 1);
        }

        return value;
    }
}
