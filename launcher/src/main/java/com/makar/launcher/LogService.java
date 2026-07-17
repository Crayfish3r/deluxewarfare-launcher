package com.makar.launcher;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

public final class LogService {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_UI_LOG_LINES = 600;

    private final Deque<String> uiLines = new ArrayDeque<>();
    private final ReadOnlyStringWrapper text = new ReadOnlyStringWrapper("");
    private final Path logFile;

    public LogService() {
        this.logFile = LauncherPaths.getLogFile();
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    public ReadOnlyStringProperty textProperty() {
        return text.getReadOnlyProperty();
    }

    public Path getLogFile() {
        return logFile;
    }

    public void clearUiLog() {
        synchronized (uiLines) {
            uiLines.clear();
        }
        updateUiText("");
    }

    public void info(String message) {
        append("INFO", message);
    }

    public void warn(String message) {
        append("WARN", message);
    }

    private void append(String level, String message) {
        String line = "["
                + LocalTime.now().format(TIME_FORMATTER)
                + "] "
                + level
                + " - "
                + sanitize(message)
                + System.lineSeparator();
        String uiText;
        synchronized (uiLines) {
            uiLines.addLast(line);
            while (uiLines.size() > MAX_UI_LOG_LINES) {
                uiLines.removeFirst();
            }
            uiText = String.join("", uiLines);
        }
        updateUiText(uiText);
        writeToFile(line);
    }

    private void updateUiText(String value) {
        if (Platform.isFxApplicationThread()) {
            text.set(value);
        } else {
            Platform.runLater(() -> text.set(value));
        }
    }

    private String sanitize(String message) {
        if (message == null) {
            return "";
        }

        return message
                .replaceAll("(?i)(access_token|launcherSessionToken|token|secret)=([^\\s&]+)", "$1=***")
                .replaceAll("(?i)(\"(?:access_token|launcherSessionToken|token|secret)\"\\s*:\\s*\")([^\"]+)(\")", "$1***$3");
    }

    private void writeToFile(String line) {
        try {
            Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
