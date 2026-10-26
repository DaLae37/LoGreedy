package com.dalae37.logreedy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.logging.Level;

/** Independent of LogManager shutdown hooks; no retained handle blocking Windows rotation. */
final class AgentLog {
    private final Path path;
    private final Level threshold;
    AgentLog(Path path, String level) throws IOException {
        this.path = path; threshold = Level.parse(level); Files.createDirectories(path.getParent());
    }
    void Info(String text) { Write(Level.INFO, text); }
    void Warning(String text) { Write(Level.WARNING, text); }
    void Severe(String text) { Write(Level.SEVERE, text); }
    private synchronized void Write(Level level, String text) {
        if (level.intValue() < threshold.intValue()) { return; }
        try {
            if (Files.exists(path) && Files.size(path) >= 1048576) {
                Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".2"));
                Path previous = path.resolveSibling(path.getFileName() + ".1");
                if (Files.exists(previous)) { Files.move(previous, path.resolveSibling(path.getFileName() + ".2")); }
                Files.move(path, previous);
            }
            String safe = text.replace('\r', ' ').replace('\n', ' ');
            if (safe.length() > 2000) { safe = safe.substring(0, 2000); }
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(Instant.now() + " " + level + " " + safe); writer.newLine();
            }
        } catch (IOException exception) { throw new UncheckedIOException("Agent log I/O failure", exception); }
    }
}
