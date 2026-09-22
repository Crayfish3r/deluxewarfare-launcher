package com.makar.launcher;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

public final class LogService implements AutoCloseable {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    static final int MAX_UI_LOG_LINES = 600;
    static final int MAX_QUEUE_LINES = 8_192;
    private static final int WRITE_BATCH_SIZE = 256;
    private static final long FLUSH_INTERVAL_MILLIS = 350;
    private static final long UI_INTERVAL_MILLIS = 250;

    private final Deque<String> uiLines = new ArrayDeque<>();
    private final BlockingQueue<String> writeQueue;
    private final ReadOnlyStringWrapper text = new ReadOnlyStringWrapper("");
    private final Path logFile;
    private final Consumer<Runnable> uiDispatcher;
    private final Thread writerThread;
    private final ScheduledExecutorService uiScheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean uiDispatchPending = new AtomicBoolean();
    private final AtomicLong uiBatchUpdates = new AtomicLong();
    private volatile boolean uiVisible;

    public LogService() {
        this(LauncherPaths.getLogFile(), Platform::runLater, MAX_QUEUE_LINES);
    }

    LogService(Path logFile, Consumer<Runnable> uiDispatcher, int queueCapacity) {
        this.logFile = logFile;
        this.uiDispatcher = uiDispatcher;
        this.writeQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.uiScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "launcher-log-ui-throttle");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException ignored) { }
        writerThread = new Thread(this::writeLoop, "launcher-log-writer");
        writerThread.setDaemon(true);
        writerThread.setPriority(Thread.MIN_PRIORITY);
        writerThread.start();
    }

    public ReadOnlyStringProperty textProperty() { return text.getReadOnlyProperty(); }
    public Path getLogFile() { return logFile; }

    public void setUiVisible(boolean visible) {
        uiVisible = visible;
        if (visible) {
            dispatchUiSnapshot();
        }
    }

    public void clearUiLog() {
        synchronized (uiLines) {
            uiLines.clear();
        }
        dispatchUiValue("");
    }

    public void info(String message) { append("INFO", message); }
    public void warn(String message) { append("WARN", message); }

    public Metrics metrics() {
        return new Metrics(writeQueue.size(), uiBatchUpdates.get(), uiLineCount(), writerThread.isAlive());
    }

    private void append(String level, String message) {
        if (closed.get()) {
            return;
        }
        String line = "[" + LocalTime.now().format(TIME_FORMATTER) + "] " + level + " - "
                + sanitize(message) + System.lineSeparator();
        synchronized (uiLines) {
            uiLines.addLast(line);
            while (uiLines.size() > MAX_UI_LOG_LINES) {
                uiLines.removeFirst();
            }
        }
        // Blocking at the bounded edge preserves the complete file log under extreme spam.
        try {
            writeQueue.put(line);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        }
        if (uiVisible) {
            scheduleUiSnapshot();
        }
    }

    private void writeLoop() {
        List<String> batch = new ArrayList<>(WRITE_BATCH_SIZE);
        try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            long lastFlush = System.nanoTime();
            while (!closed.get() || !writeQueue.isEmpty()) {
                String first = writeQueue.poll(FLUSH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    writeQueue.drainTo(batch, WRITE_BATCH_SIZE - 1);
                    for (String line : batch) {
                        writer.write(line);
                    }
                    batch.clear();
                }
                long now = System.nanoTime();
                if (first == null || now - lastFlush >= TimeUnit.MILLISECONDS.toNanos(FLUSH_INTERVAL_MILLIS)) {
                    writer.flush();
                    lastFlush = now;
                }
            }
            writer.flush();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            drainRemainingToFallback();
        } catch (IOException exception) {
            drainRemainingToFallback();
        }
    }

    private void drainRemainingToFallback() {
        List<String> remaining = new ArrayList<>();
        writeQueue.drainTo(remaining);
        if (remaining.isEmpty()) {
            return;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (String line : remaining) {
                writer.write(line);
            }
        } catch (IOException ignored) { }
    }

    private void scheduleUiSnapshot() {
        if (!uiDispatchPending.compareAndSet(false, true)) {
            return;
        }
        uiScheduler.schedule(() -> {
            uiDispatchPending.set(false);
            if (uiVisible && !closed.get()) {
                dispatchUiSnapshot();
            }
        }, UI_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void dispatchUiSnapshot() {
        String snapshot;
        synchronized (uiLines) {
            StringBuilder builder = new StringBuilder(uiLines.size() * 96);
            for (String line : uiLines) {
                builder.append(line);
            }
            snapshot = builder.toString();
        }
        dispatchUiValue(snapshot);
    }

    private void dispatchUiValue(String value) {
        uiDispatcher.accept(() -> {
            text.set(value);
            uiBatchUpdates.incrementAndGet();
        });
    }

    private int uiLineCount() {
        synchronized (uiLines) {
            return uiLines.size();
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        uiScheduler.shutdownNow();
        try {
            writerThread.join(5_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (writerThread.isAlive()) {
            writerThread.interrupt();
            try {
                writerThread.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public record Metrics(int queueDepth, long uiBatchUpdates, int uiHistoryLines, boolean writerAlive) { }
}
