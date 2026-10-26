package com.dalae37.logreedy;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

public final class TailerTest {
    private List<Event> Events(DiskQueue queue) { return queue.Batch(1000, 10000000); }
    private String Text(DiskQueue queue) { StringBuilder text = new StringBuilder(); for (Event event : Events(queue)) { text.append(event.message); } return text.toString(); }
    @Test public void MultipleFilesAndIndependentByteOffsets() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path first = fixture.File("a.log", "one\r\n"); Path second = fixture.File("b.log", "둘\n");
            try (DiskQueue queue = fixture.Queue()) {
                Tailer tailer = fixture.Tailer(queue); tailer.Poll(); assertEquals(2, queue.Pending());
                assertEquals(new HashSet<>(Arrays.asList(5L, 4L)), new HashSet<>(queue.Cursors().stream().map(cursor -> cursor.offset).toList()));
                fixture.Append(first, "three\n"); fixture.Append(second, "four\n"); tailer.Poll(); assertEquals(4, queue.Pending());
            }
            try (DiskQueue queue = fixture.Queue()) { fixture.Tailer(queue).Poll(); assertEquals(4, queue.Pending()); }
        }
    }
    @Test public void ExistingEndButNewFilesBegin() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.properties.setProperty("source.app.initial", "end"); Path old = fixture.File("old.log", "skip\n");
            try (DiskQueue queue = fixture.Queue()) {
                Tailer tailer = fixture.Tailer(queue); tailer.Poll(); assertEquals(0, queue.Pending());
                fixture.File("new.log", "keep\n"); fixture.Append(old, "append\n"); tailer.Poll();
                assertEquals(2, queue.Pending()); assertFalse(Text(queue).contains("skip"));
            }
        }
    }
    @Test public void TenGiBSparseFileUsesLongOffsetsAndBoundedRead() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.properties.setProperty("source.app.initial", "end"); Path path = fixture.logs.resolve("sparse.log");
            long size = 10L * 1024 * 1024 * 1024 + 1;
            try (java.nio.channels.FileChannel file = java.nio.channels.FileChannel.open(path,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
                file.position(size - 1); file.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
            }
            try (DiskQueue queue = fixture.Queue()) {
                Tailer tailer = fixture.Tailer(queue); tailer.Poll(); assertEquals(size, queue.Cursors().get(0).offset);
                fixture.Append(path, "tail\n"); tailer.Poll(); assertEquals("tail\n", Text(queue));
                assertEquals(size + 5, queue.Cursors().get(0).offset); assertEquals(5, tailer.readBytes);
            }
        }
    }
    @Test public void RenameAppendReplaceTruncateDeleteAndRecreate() throws Exception {
        try (Fixture fixture = new Fixture(); DiskQueue queue = fixture.Queue()) {
            Path path = fixture.File("app.log", "first\n"); Tailer tailer = fixture.Tailer(queue); tailer.Poll();
            Path rotated = fixture.logs.resolve("app.log.1"); Files.move(path, rotated); fixture.Append(rotated, "late\n");
            fixture.File("app.log", "new\n"); tailer.Poll();
            Files.write(path, "cut\n".getBytes(StandardCharsets.UTF_8)); tailer.Poll();
            Files.delete(path); tailer.Poll(); fixture.File("app.log", "recreated\n"); tailer.Poll();
            assertEquals("first\nlate\nnew\ncut\nrecreated\n", Text(queue));
        }
    }
    @Test public void CopytruncateRegrownSameSizeDetectedByBoundedContent() throws Exception {
        try (Fixture fixture = new Fixture(); DiskQueue queue = fixture.Queue()) {
            Path path = fixture.File("a.log", "a".repeat(300) + "\n"); Tailer tailer = fixture.Tailer(queue); tailer.Poll();
            Files.copy(path, fixture.logs.resolve("a.log.1"));
            Files.writeString(path, "a".repeat(256) + "b".repeat(44) + "\n"); tailer.Poll();
            assertEquals(2, queue.Pending()); assertTrue(Text(queue).contains("b".repeat(44)));
        }
    }
    @Test public void FileKeyNullRenameAndReplace() throws Exception {
        try (Fixture fixture = new Fixture(); DiskQueue queue = fixture.Queue()) {
            Path path = fixture.File("a.log", "old\n");
            Tailer tailer = new Tailer(fixture.Config(), queue, message -> { }, (file, attributes) -> ""); tailer.Poll();
            Path rotated = fixture.logs.resolve("a.log.1"); Files.move(path, rotated); fixture.Append(rotated, "late\n");
            fixture.File("a.log", "replacement\n"); tailer.Poll(); assertEquals("old\nlate\nreplacement\n", Text(queue));
        }
    }
    @Test public void RotationWhileAgentStoppedResumesOldGenerationAndNewFromBegin() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.properties.setProperty("source.app.initial", "end"); Path path = fixture.File("a.log", "skip\n");
            try (DiskQueue queue = fixture.Queue()) { fixture.Tailer(queue).Poll(); }
            Path rotated = fixture.logs.resolve("a.log.1"); Files.move(path, rotated); fixture.Append(rotated, "offline-old\n");
            fixture.File("a.log", "offline-new\n");
            try (DiskQueue queue = fixture.Queue()) { fixture.Tailer(queue).Poll(); assertEquals("offline-old\noffline-new\n", Text(queue)); }
        }
    }
    @Test public void Utf8Ms949EucKrAndUtf16Boundaries() throws Exception {
        for (String encoding : Arrays.asList("UTF-8", "MS949", "EUC-KR", "UTF-16LE")) {
            try (Fixture fixture = new Fixture()) {
                fixture.properties.setProperty("source.app.charset", encoding);
                String text = "x".repeat(255) + "한글 😀\r\n";
                byte[] bytes = text.getBytes(Charset.forName(encoding));
                Files.write(fixture.logs.resolve("a.log"), bytes);
                try (DiskQueue queue = fixture.Queue()) {
                    Tailer tailer = fixture.Tailer(queue); for (int index = 0; index < 4; index++) { tailer.Poll(); }
                    assertEquals(new String(bytes, Charset.forName(encoding)), Text(queue));
                    assertEquals(bytes.length, queue.Cursors().get(0).offset);
                }
            }
        }
    }
    @Test public void IncompleteUtf8AndNoNewlineDoNotCommitPrematurely() throws Exception {
        try (Fixture fixture = new Fixture(); DiskQueue queue = fixture.Queue()) {
            byte[] korean = "한".getBytes(StandardCharsets.UTF_8); Path path = fixture.logs.resolve("a.log");
            Files.write(path, Arrays.copyOf(korean, 2)); Tailer tailer = fixture.Tailer(queue); tailer.Poll();
            Thread.sleep(30); tailer.Poll(); assertEquals(0, queue.Pending()); assertEquals(0, queue.Cursors().get(0).offset);
            assertEquals(2L, tailer.ReadOffsets().values().iterator().next().longValue());
            Files.write(path, new byte[] {korean[2]}, StandardOpenOption.APPEND); tailer.Poll(); Thread.sleep(30); tailer.Poll();
            assertEquals("한", Text(queue)); assertEquals(3, queue.Cursors().get(0).offset);
        }
    }
    @Test public void MultilineTimeoutLimitsAndRestart() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.properties.setProperty("source.app.multiline.start", "^2026");
            Path path = fixture.File("a.log", "2026 ERROR fail\n java.lang.Exception\n  at Main.main(Main.java:1)\n");
            try (DiskQueue queue = fixture.Queue()) {
                Tailer tailer = fixture.Tailer(queue); tailer.Poll(); assertEquals(0, queue.Pending()); assertEquals(0, queue.Cursors().get(0).offset);
            }
            try (DiskQueue queue = fixture.Queue()) {
                Tailer tailer = fixture.Tailer(queue); tailer.Poll(); Thread.sleep(30); tailer.Poll();
                assertEquals(1, queue.Pending()); assertTrue(Text(queue).contains("at Main"));
                fixture.Append(path, "2026 next\n continuation\n"); tailer.Poll(); tailer.Flush(); assertEquals(2, queue.Pending());
            }
        }
        try (Fixture fixture = new Fixture()) {
            fixture.properties.setProperty("source.app.multiline.start", "^START"); fixture.properties.setProperty("event.max.lines", "2");
            String text = "START\n 1\n 2\n" + "x".repeat(2200) + "\n"; fixture.File("a.log", text);
            try (DiskQueue queue = fixture.Queue()) {
                Tailer tailer = fixture.Tailer(queue); for (int index = 0; index < 10; index++) { tailer.Poll(); } tailer.Flush();
                assertEquals(text, Text(queue)); assertTrue(queue.Pending() >= 4);
            }
        }
    }
    @Test public void MultipleSourcesAndAgentLogProtection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.properties.setProperty("sources", "app,other"); fixture.properties.setProperty("source.other.paths", "logs/*.txt");
            fixture.properties.setProperty("source.other.initial", "begin"); fixture.properties.setProperty("agent.log.path", "logs/self.log");
            fixture.File("a.log", "app\n"); fixture.File("b.txt", "other\n"); fixture.File("self.log", "must not collect\n");
            try (DiskQueue queue = fixture.Queue()) { fixture.Tailer(queue).Poll(); assertEquals("app\nother\n", Text(queue)); }
        }
    }

    @Test public void QueueFullPausesReadsThenResumesWithoutLosingBytes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String text = ("x".repeat(499) + "\n").repeat(40); fixture.File("a.log", text);
            AgentConfig config = fixture.Config(); StringBuilder received = new StringBuilder();
            try (DiskQueue queue = new DiskQueue(config.spool, config.checkpoint, 32768, 128)) {
                Tailer tailer = fixture.Tailer(queue);
                for (int index = 0; index < 40; index++) { tailer.Poll(); }
                assertTrue(tailer.backpressure > 0); long bytes = tailer.readBytes;
                for (int index = 0; index < 3; index++) { tailer.Poll(); }
                assertEquals(bytes, tailer.readBytes);
                for (int index = 0; index < 200 && received.length() < text.length(); index++) {
                    List<Event> batch = Events(queue);
                    for (Event event : batch) { received.append(event.message); }
                    queue.Ack(batch); tailer.Poll();
                }
                assertEquals(text, received.toString());
            }
        }
    }
    @Test public void DateBasedDiscoveryRetiresOldGenerationAndRemembersSourceAcrossRestart() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.properties.setProperty("source.app.paths", "logs/server-*.log");
            fixture.properties.setProperty("source.app.initial", "end"); fixture.properties.setProperty("file.retire.ms", "20");
            Path old = fixture.File("server-2026-10-25.log", "old\n");
            try (DiskQueue queue = fixture.Queue()) {
                Tailer tailer = fixture.Tailer(queue); tailer.Poll(); Files.delete(old);
                Thread.sleep(30); tailer.Poll(); assertTrue(queue.Cursors().isEmpty()); assertTrue(queue.HasSource("app"));
            }
            fixture.File("server-2026-10-26.log", "new-day\n");
            try (DiskQueue queue = fixture.Queue()) { fixture.Tailer(queue).Poll(); assertEquals("new-day\n", Text(queue)); }
        }
    }

    @Test public void SourceHandleClosedBeforeQueueForceAndDenseLinesRemainBounded() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path path = fixture.File("a.log", "x\n".repeat(1000)); AgentConfig config = fixture.Config();
            Faults check = point -> {
                if (point.equals("put.partial") && Files.isDirectory(Paths.get("/proc/self/fd"))) {
                    try (java.util.stream.Stream<Path> descriptors = Files.list(Paths.get("/proc/self/fd"))) {
                        for (Path descriptor : (Iterable<Path>) descriptors::iterator) {
                            try { assertNotEquals(path.toString(), Files.readSymbolicLink(descriptor).toString()); }
                            catch (NoSuchFileException ignored) { }
                        }
                    }
                }
            };
            try (DiskQueue queue = new DiskQueue(config.spool, config.checkpoint, config.spoolBytes, config.maxFiles, check)) {
                Tailer tailer = fixture.Tailer(queue); StringBuilder result = new StringBuilder();
                for (int index = 0; index < 20 && result.length() < 2000; index++) {
                    tailer.Poll(); List<Event> batch = Events(queue);
                    for (Event event : batch) { result.append(event.message); }
                    queue.Ack(batch);
                }
                assertEquals("x\n".repeat(1000), result.toString());
            }
        }
    }
}
