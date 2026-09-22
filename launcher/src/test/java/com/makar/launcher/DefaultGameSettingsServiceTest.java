package com.makar.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DefaultGameSettingsServiceTest {
    @TempDir Path gameDirectory;

    @Test
    void migratesOnlyLegacyLauncherValuesAndIsIdempotent() throws Exception {
        Path options = gameDirectory.resolve("options.txt");
        Files.write(options, List.of(
                "entityDistanceScaling:5.0",
                "simulationDistance:6",
                "graphicsMode:1",
                "fullscreenResolution:1920x1080@120:24",
                "customPlayerSetting:keep-me"));
        DefaultGameSettingsService service = new DefaultGameSettingsService();
        List<String> messages = new ArrayList<>();

        service.applyOnce(gameDirectory, messages::add);
        String first = Files.readString(options);
        service.applyOnce(gameDirectory, messages::add);

        assertTrue(first.contains("entityDistanceScaling:1.0"));
        assertTrue(first.contains("simulationDistance:6"));
        assertTrue(first.contains("graphicsMode:0"));
        assertTrue(first.contains("customPlayerSetting:keep-me"));
        assertFalse(first.contains("fullscreenResolution:"));
        assertEquals(first, Files.readString(options));
        assertEquals("2", Files.readString(gameDirectory.resolve(".deluxewarfare-default-settings-version")));
        assertEquals(1, messages.size());
    }
}
