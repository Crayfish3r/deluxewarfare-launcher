package com.makar.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LogServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void batchesLargeLogAndFlushesEverythingAtShutdown() throws Exception {
        Path log = temporaryDirectory.resolve("launcher.log");
        LogService service = new LogService(log, Runnable::run, 32);
        service.setUiVisible(true);
        for (int index = 0; index < 2_000; index++) {
            service.info("line-" + index + " token=secret-" + index);
        }

        service.close();
        String file = Files.readString(log);

        assertTrue(file.contains("line-0 token=***"));
        assertTrue(file.contains("line-1999 token=***"));
        assertFalse(file.contains("secret-1999"));
        assertEquals(LogService.MAX_UI_LOG_LINES, service.metrics().uiHistoryLines());
        assertEquals(0, service.metrics().queueDepth());
        assertFalse(service.metrics().writerAlive());
        assertTrue(service.metrics().uiBatchUpdates() < 20);
    }
}
