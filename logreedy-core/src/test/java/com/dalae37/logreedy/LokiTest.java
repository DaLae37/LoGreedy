package com.dalae37.logreedy;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.*;
import javax.net.ssl.*;
import com.sun.net.httpserver.*;
import com.google.gson.*;

public final class LokiTest {
    @Test public void GzipPayloadMetadataLabelsAndMonotonicTime() throws Exception {
        try (Fixture fixture = new Fixture(); Fixture.Mock mock = new Fixture.Mock()) {
            fixture.properties.setProperty("loki.url", mock.Url()); fixture.properties.setProperty("loki.structured.metadata", "true");
            fixture.properties.setProperty("source.app.extract.regex", "(?<timestamp>\\S+) (?<level>\\S+) (?<logger>\\S+) (?<message>.*)");
            AgentConfig config = fixture.Config();
            try (DiskQueue queue = fixture.Queue()) {
                queue.Put(fixture.Event(1, "2026-01-01T00:00:01Z ERROR Logger message\r\n"));
                queue.Put(fixture.Event(2, "2026-01-01T00:00:00Z WARN Logger old\n"));
                List<Event> batch = queue.Batch(10, 100000); assertTrue(batch.get(1).nanos > batch.get(0).nanos);
                assertTrue(new LokiClient(config).Send(batch).Success());
                JsonObject stream = JsonParser.parseString(mock.bodies.get(0)).getAsJsonObject().getAsJsonArray("streams").get(0).getAsJsonObject();
                assertEquals(5, stream.getAsJsonObject("stream").size());
                JsonArray values = stream.getAsJsonArray("values"); assertEquals("ERROR", values.get(0).getAsJsonArray().get(2).getAsJsonObject().get("level").getAsString());
                assertEquals("message\r\n", values.get(0).getAsJsonArray().get(2).getAsJsonObject().get("message").getAsString());
                assertEquals(2, mock.Messages().size());
            }
        }
    }
    @Test public void Http429And500RetryThenAck() throws Exception {
        try (Fixture fixture = new Fixture(); Fixture.Mock mock = new Fixture.Mock()) {
            mock.response = count -> count == 1 ? 429 : count == 2 ? 500 : 204;
            fixture.properties.setProperty("loki.url", mock.Url());
            try (DiskQueue queue = fixture.Queue()) {
                queue.Put(fixture.Event(5, "retry\n")); Sender sender = new Sender(fixture.Config(), queue, message -> { });
                Thread thread = new Thread(sender); thread.start();
                try { Fixture.Until(() -> queue.Pending() == 0); assertEquals(2, sender.retries.get()); assertNull(sender.failure); }
                finally { sender.Stop(0); thread.join(2000); assertFalse(thread.isAlive()); }
                assertEquals(3, mock.calls.get());
            }
        }
    }
    @Test public void TimeoutRetainsThenRetries() throws Exception {
        try (Fixture fixture = new Fixture(); Fixture.Mock mock = new Fixture.Mock()) {
            mock.sleepMs = 300; fixture.properties.setProperty("loki.url", mock.Url());
            fixture.properties.setProperty("loki.read.timeout.ms", "100");
            try (DiskQueue queue = fixture.Queue()) {
                queue.Put(fixture.Event(5, "timeout\n")); Sender sender = new Sender(fixture.Config(), queue, message -> { });
                Thread thread = new Thread(sender); thread.start();
                try {
                    Fixture.Until(() -> sender.retries.get() >= 1); assertEquals(1, queue.Pending());
                    mock.sleepMs = 0; Fixture.Until(() -> queue.Pending() == 0);
                } finally { sender.Stop(0); thread.join(2000); assertFalse(thread.isAlive()); }
            }
        }
    }
    @Test public void Permanent4xxIsDurablyQuarantined() throws Exception {
        try (Fixture fixture = new Fixture(); Fixture.Mock mock = new Fixture.Mock()) {
            mock.response = count -> 400; fixture.properties.setProperty("loki.url", mock.Url());
            try (DiskQueue queue = fixture.Queue()) {
                queue.Put(fixture.Event(5, "bad")); Sender sender = new Sender(fixture.Config(), queue, message -> { });
                Thread thread = new Thread(sender); thread.start();
                try { Fixture.Until(() -> queue.Quarantined() == 1); assertEquals(0, queue.Pending()); }
                finally { sender.Stop(0); thread.join(2000); }
            }
            try (DiskQueue queue = fixture.Queue()) { assertEquals(1, queue.Quarantined()); assertEquals(5, queue.Cursors().get(0).offset); }
        }
    }
    @Test public void HttpsRejectsUntrustedCertificateAndAcceptsConfiguredTruststore() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.properties.setProperty("loki.connect.timeout.ms", "10000");
            fixture.properties.setProperty("loki.read.timeout.ms", "10000");
            Path storePath = fixture.root.resolve("tls.p12");
            Process keytool = new ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair", "-alias", "local",
                    "-keyalg", "RSA", "-storetype", "PKCS12", "-keystore", storePath.toString(), "-storepass", "test-pass",
                    "-keypass", "test-pass", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-noprompt")
                    .redirectErrorStream(true).redirectOutput(fixture.root.resolve("keytool.out").toFile()).start();
            assertTrue(keytool.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, keytool.exitValue());
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(storePath)) { store.load(input, "test-pass".toCharArray()); }
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(store, "test-pass".toCharArray());
            SSLContext context = SSLContext.getInstance("TLS"); context.init(keys.getKeyManagers(), null, null);
            HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(context));
            server.createContext("/loki/api/v1/push", exchange -> { exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(204, -1); exchange.close(); }); server.start();
            try {
                fixture.properties.setProperty("loki.url", "https://localhost:" + server.getAddress().getPort() + "/loki/api/v1/push");
                try { new LokiClient(fixture.Config()).Send(Arrays.asList(fixture.Event(5, "tls"))); fail(); }
                catch (SSLHandshakeException expected) { /* Verification stays enabled. */ }
                fixture.properties.setProperty("loki.tls.truststore.path", storePath.toString());
                fixture.properties.setProperty("loki.tls.truststore.password.env", "LOGREEDY_TEST_STORE_PASSWORD");
                assertTrue(new LokiClient(fixture.Config()).Send(Arrays.asList(fixture.Event(5, "tls"))).Success());
            } finally { server.stop(0); }
        }
    }
    @Test public void AppendToMockAndGracefulAgentShutdown() throws Exception {
        try (Fixture fixture = new Fixture(); Fixture.Mock mock = new Fixture.Mock()) {
            fixture.properties.setProperty("loki.url", mock.Url()); Path path = fixture.File("a.log", "first\n");
            Agent agent = new Agent(fixture.Config(), PlatformExecution::new); int[] code = {-1};
            Thread thread = new Thread(() -> code[0] = agent.Run()); thread.start();
            try {
                Fixture.Until(() -> mock.Messages().contains("first\n")); fixture.Append(path, "append\n");
                Fixture.Until(() -> mock.Messages().contains("append\n"));
            } finally { agent.Stop(); thread.join(5000); assertFalse(thread.isAlive()); }
            assertEquals(0, code[0]);
            try (DiskQueue queue = fixture.Queue()) { assertEquals(0, queue.Pending()); assertEquals(13, queue.Cursors().get(0).offset); }
        }
    }
    @Test public void PackagedLegacyAndVirtualThreadModernProduceSameMessagesAndGracefullyStop() throws Exception {
        List<List<String>> results = new ArrayList<>();
        for (String property : Arrays.asList("legacy.jar", "modern.jar")) {
            try (Fixture fixture = new Fixture(); Fixture.Mock mock = new Fixture.Mock()) {
                fixture.properties.setProperty("loki.url", mock.Url());
                fixture.properties.setProperty("source.app.multiline.start", "^START");
                fixture.File("a.log", "START 첫째\n at method\nSTART second\n"); fixture.Config();
                Process process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-jar", System.getProperty(property),
                        "--config", fixture.root.resolve("LoGreedy.properties").toString())
                        .redirectErrorStream(true).redirectOutput(fixture.root.resolve("agent.out").toFile()).start();
                try { Fixture.Until(() -> mock.Messages().size() >= 2); }
                finally { process.destroy(); assertTrue(process.waitFor(15, TimeUnit.SECONDS)); }
                results.add(mock.Messages());
                try (DiskQueue queue = fixture.Queue()) { Fixture.CheckStopped(fixture.root.resolve("data/agent.log"), queue, mock.Messages().size()); }
            }
        }
        assertEquals(results.get(0), results.get(1));
    }
    @Test public void ConfigurationRejectsUnsafeOrInvalidSettings() throws Exception {
        for (String[] entry : new String[][] {{"poll.ms", "0"}, {"source.app.charset", "not-a-charset"},
                {"source.app.initial", "middle"}, {"source.app.multiline.start", "["}, {"loki.allow.http", "false"},
                {"loki.url", "http://user:password@localhost/loki/api/v1/push"}, {"spool.max.bytes", "1"},
                {"sources", "app,app"}, {"agent.log.path", "data/spool/self.log"}}) {
            try (Fixture fixture = new Fixture()) {
                fixture.properties.setProperty(entry[0], entry[1]);
                try { fixture.Config(); fail(entry[0]); } catch (IllegalArgumentException expected) { }
            }
        }
    }

    @Test public void AdditionalJava8And21RuntimeMatrix() throws Exception {
        String java8 = System.getenv("LOGREEDY_JAVA8_HOME"), java21 = System.getenv("LOGREEDY_JAVA21_HOME");
        org.junit.Assume.assumeTrue("Optional Java 8/21 runtime homes must both be supplied", java8 != null && java21 != null);
        for (String[] runtime : new String[][] {{java8, "legacy.jar"}, {java21, "modern.jar"}}) {
            try (Fixture fixture = new Fixture(); Fixture.Mock mock = new Fixture.Mock()) {
                fixture.properties.setProperty("loki.url", mock.Url()); Path file = fixture.File("a.log", "first 한글\n"); fixture.Config();
                Process process = new ProcessBuilder(runtime[0] + "/bin/java", "-jar", System.getProperty(runtime[1]),
                        "--config", fixture.root.resolve("LoGreedy.properties").toString())
                        .redirectErrorStream(true).redirectOutput(fixture.root.resolve("runtime.out").toFile()).start();
                try {
                    Fixture.Until(() -> mock.Messages().contains("first 한글\n")); fixture.Append(file, "second\n");
                    Fixture.Until(() -> mock.Messages().contains("second\n"));
                } finally { process.destroy(); assertTrue(process.waitFor(15, TimeUnit.SECONDS)); }
                try (DiskQueue queue = fixture.Queue()) { Fixture.CheckStopped(fixture.root.resolve("data/agent.log"), queue, mock.Messages().size()); }
                assertEquals(Arrays.asList("first 한글\n", "second\n"), mock.Messages());
            }
        }
    }
}
