package com.makar.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LauncherConfigTest {
    private static final String[] DEPRECATED_RUNTIME_PROTECTION_KEYS = {
            "runtimeManagedFolderProtectionEnabled",
            "terminateGameOnRuntimeForbiddenFile",
            "runtimeManagedFolderScanIntervalSeconds"
    };

    @TempDir
    Path temporaryHome;

    private String originalOsName;
    private String originalUserHome;

    @BeforeEach
    void useTemporaryConfigDirectory() {
        originalOsName = System.getProperty("os.name");
        originalUserHome = System.getProperty("user.home");
        System.setProperty("os.name", "Linux");
        System.setProperty("user.home", temporaryHome.toString());
    }

    @AfterEach
    void restoreSystemProperties() {
        restoreSystemProperty("os.name", originalOsName);
        restoreSystemProperty("user.home", originalUserHome);
    }

    @Test
    void newConfigDoesNotContainRuntimeProtectionProperties() throws Exception {
        LauncherConfig.load();

        Properties properties = loadConfigProperties();

        assertDeprecatedRuntimeProtectionPropertiesAreAbsent(properties);
    }

    @Test
    void loadRemovesDeprecatedRuntimeProtectionPropertiesFromExistingConfig() throws Exception {
        LauncherConfig config = LauncherConfig.load();
        config.setNickname("existing-user");
        config.save();

        Properties properties = loadConfigProperties();
        properties.setProperty("runtimeManagedFolderProtectionEnabled", "false");
        properties.setProperty("terminateGameOnRuntimeForbiddenFile", "false");
        properties.setProperty("runtimeManagedFolderScanIntervalSeconds", "3600");
        try (OutputStream outputStream = Files.newOutputStream(configFile())) {
            properties.store(outputStream, "Existing launcher settings");
        }

        LauncherConfig loadedConfig = LauncherConfig.load();
        Properties migratedProperties = loadConfigProperties();

        assertEquals("existing-user", loadedConfig.getNickname());
        assertDeprecatedRuntimeProtectionPropertiesAreAbsent(migratedProperties);
    }

    private Properties loadConfigProperties() throws Exception {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(configFile())) {
            properties.load(inputStream);
        }
        return properties;
    }

    private Path configFile() {
        return temporaryHome.resolve(".tactical-launcher/launcher.properties");
    }

    private void assertDeprecatedRuntimeProtectionPropertiesAreAbsent(Properties properties) {
        for (String key : DEPRECATED_RUNTIME_PROTECTION_KEYS) {
            assertFalse(properties.containsKey(key), () -> "Deprecated property is still present: " + key);
        }
    }

    private void restoreSystemProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
