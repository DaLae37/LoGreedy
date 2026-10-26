package com.dalae37.logreedy;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.zip.GZIPInputStream;
import com.sun.net.httpserver.*;
import com.google.gson.*;
import static org.junit.Assert.*;

final class Fixture implements AutoCloseable {
    final Path root = Files.createTempDirectory("logreedy-test-");
    final Path logs = Files.createDirectories(root.resolve("logs"));
    final Properties properties = new Properties();
    Fixture() throws IOException {
        properties.setProperty("sources", "app"); properties.setProperty("source.app.paths", "logs/*.log");
        properties.setProperty("source.app.initial", "begin"); properties.setProperty("poll.ms", "10");
        properties.setProperty("source.app.multiline.timeout.ms", "20");
        properties.setProperty("read.buffer.bytes", "256"); properties.setProperty("read.max.bytes.per.file", "512");
        properties.setProperty("event.max.bytes", "1024"); properties.setProperty("loki.batch.bytes", "16384");
        properties.setProperty("spool.max.bytes", "131072"); properties.setProperty("loki.allow.http", "true");
        properties.setProperty("loki.retry.initial.ms", "20"); properties.setProperty("loki.retry.max.ms", "100");
        properties.setProperty("loki.connect.timeout.ms", "2000"); properties.setProperty("loki.read.timeout.ms", "2000");
        properties.setProperty("shutdown.timeout.ms", "200"); properties.setProperty("agent.host", "test-host");
        Files.createDirectories(root.resolve("data"));
    }
    Path File(String name, String value) throws IOException { Path path = logs.resolve(name); Files.write(path, value.getBytes(StandardCharsets.UTF_8)); return path; }
    void Append(Path path, String value) throws IOException { Files.write(path, value.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND); }
    AgentConfig Config() throws IOException {
        try (Writer output = Files.newBufferedWriter(root.resolve("LoGreedy.properties"))) { properties.store(output, "test"); }
        return new AgentConfig(root.resolve("LoGreedy.properties"));
    }
    DiskQueue Queue() throws IOException { AgentConfig config = Config(); return new DiskQueue(config.spool, config.checkpoint, config.spoolBytes, config.maxFiles); }
    Tailer Tailer(DiskQueue queue) throws IOException { return new Tailer(Config(), queue, message -> { }); }
    Event Event(long offset, String message) throws IOException {
        Cursor cursor = new Cursor(); cursor.id = "file-1"; cursor.source = "app"; cursor.path = logs.resolve("a.log").toString();
        cursor.key = ""; cursor.prefix = ""; cursor.anchor = ""; cursor.offset = offset;
        AgentConfig config = Config(); return Event.Create(config, config.sources.get(0), cursor, message);
    }
    static boolean Windows() { return System.getProperty("os.name").startsWith("Windows"); }
    static void CheckStopped(Path log, DiskQueue queue, int received) throws IOException {
        if (Windows()) {
            // Process.destroy is TerminateProcess, not a graceful JVM signal on Windows.
            assertTrue(queue.Pending() <= received);
        } else {
            assertTrue(Files.readString(log).contains("Graceful shutdown")); assertEquals(0, queue.Pending());
        }
        assertFalse(queue.Cursors().isEmpty());
    }
    static void Until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(10); }
        assertTrue("Timed out waiting for expected behavior", condition.getAsBoolean());
    }
    Process Probe(String phase) throws IOException {
        return new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("test.classpath"),
                CrashProbe.class.getName(), root.resolve("LoGreedy.properties").toString(), phase)
                .redirectErrorStream(true).redirectOutput(root.resolve("probe.out").toFile()).start();
    }
    @Override public void close() throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) { Files.deleteIfExists(path); }
        }
    }
    static final class Mock implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger calls = new AtomicInteger();
        volatile IntUnaryOperator response = number -> 204;
        volatile long sleepMs;
        Mock() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/loki/api/v1/push", exchange -> {
                int count = calls.incrementAndGet();
                try {
                    InputStream stream = exchange.getRequestBody();
                    if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) { stream = new GZIPInputStream(stream); }
                    String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    JsonParser.parseString(text).getAsJsonObject(); bodies.add(text);
                    if (sleepMs > 0) { Thread.sleep(sleepMs); }
                    exchange.sendResponseHeaders(response.applyAsInt(count), -1);
                } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.setExecutor(executor); server.start();
        }
        String Url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/loki/api/v1/push"; }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
        List<String> Messages() {
            List<String> result = new ArrayList<>();
            synchronized (bodies) {
                for (String body : bodies) {
                    for (JsonElement stream : JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("streams")) {
                        for (JsonElement value : stream.getAsJsonObject().getAsJsonArray("values")) { result.add(value.getAsJsonArray().get(1).getAsString()); }
                    }
                }
            }
            return result;
        }
    }
}
