package com.dalae37.logreedy;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.*;

/** Blocking HTTP, normal TLS validation, redirects disabled to protect authorization. */
public final class LokiClient {
    private final AgentConfig config;
    private final SSLSocketFactory tls;
    private volatile HttpURLConnection active;
    public LokiClient(AgentConfig config) throws IOException {
        this.config = config;
        String path = config.Text("loki.tls.truststore.path", "");
        if (path.isEmpty()) { tls = null; return; }
        String name = config.Text("loki.tls.truststore.password.env", "");
        String password = name.isEmpty() ? "" : System.getenv(name);
        if (password == null) { throw new IllegalArgumentException("Missing TLS truststore password environment variable"); }
        try {
            KeyStore store = KeyStore.getInstance(config.Text("loki.tls.truststore.type", KeyStore.getDefaultType()));
            try (InputStream input = Files.newInputStream(config.Resolve(path))) { store.load(input, password.toCharArray()); }
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(store); SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), null); tls = context.getSocketFactory();
        } catch (GeneralSecurityException exception) { throw new IOException("Cannot initialize TLS truststore", exception); }
    }
    public Result Send(List<Event> events) throws IOException {
        byte[] bytes = Payload(events, config.metadata).getBytes(StandardCharsets.UTF_8);
        if (config.gzip) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(bytes); }
            bytes = compressed.toByteArray();
        }
        HttpURLConnection connection = (HttpURLConnection) config.loki.toURL().openConnection(); active = connection;
        try {
            if (connection instanceof HttpsURLConnection && tls != null) { ((HttpsURLConnection) connection).setSSLSocketFactory(tls); }
            connection.setRequestMethod("POST"); connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(config.connectMs); connection.setReadTimeout(config.readMs);
            connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (config.gzip) { connection.setRequestProperty("Content-Encoding", "gzip"); }
            if (!config.auth.isEmpty()) { connection.setRequestProperty("Authorization", config.auth); }
            String tenant = config.Text("loki.tenant", "");
            if (tenant.indexOf('\r') >= 0 || tenant.indexOf('\n') >= 0) { throw new IllegalArgumentException("Invalid tenant header"); }
            if (!tenant.isEmpty()) { connection.setRequestProperty("X-Scope-OrgID", tenant); }
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int code = connection.getResponseCode();
            long retryAfter = 0;
            try { retryAfter = Math.min(config.retryMaxMs, Math.multiplyExact(Long.parseLong(connection.getHeaderField("Retry-After")), 1000)); }
            catch (RuntimeException ignored) { /* Backoff applies for absent/non-numeric values. */ }
            return new Result(code, retryAfter);
        } finally { connection.disconnect(); active = null; }
    }
    public void Cancel() { HttpURLConnection connection = active; if (connection != null) { connection.disconnect(); } }
    public static String Payload(List<Event> events, boolean metadata) {
        Map<Map<String, String>, List<Event>> streams = new LinkedHashMap<>();
        for (Event event : events) { streams.computeIfAbsent(event.labels, key -> new ArrayList<>()).add(event); }
        StringBuilder output = new StringBuilder("{\"streams\":["); boolean firstStream = true;
        for (Map.Entry<Map<String, String>, List<Event>> stream : streams.entrySet()) {
            if (!firstStream) { output.append(','); } firstStream = false;
            output.append("{\"stream\":{"); boolean firstLabel = true;
            for (Map.Entry<String, String> label : stream.getKey().entrySet()) {
                if (!firstLabel) { output.append(','); } firstLabel = false;
                output.append(Codec.Quote(label.getKey())).append(':').append(Codec.Quote(label.getValue()));
            }
            output.append("},\"values\":["); boolean firstValue = true;
            for (Event event : stream.getValue()) {
                if (!firstValue) { output.append(','); } firstValue = false;
                output.append('[').append(Codec.Quote(String.valueOf(event.nanos))).append(',').append(Codec.Quote(event.message));
                if (metadata) {
                    output.append(",{\"level\":").append(Codec.Quote(event.level))
                            .append(",\"logger\":").append(Codec.Quote(event.logger))
                            .append(",\"timestamp\":").append(Codec.Quote(event.timestamp))
                            .append(",\"message\":").append(Codec.Quote(event.parsedMessage))
                            .append(",\"path\":").append(Codec.Quote(event.cursor.path)).append('}');
                }
                output.append(']');
            }
            output.append("]}");
        }
        return output.append("]}").toString();
    }
    public static final class Result {
        public final int code;
        public final long retryAfter;
        Result(int code, long retryAfter) { this.code = code; this.retryAfter = retryAfter; }
        public boolean Success() { return code >= 200 && code < 300; }
        public boolean Retry() { return code == 429 || code == 408 || code >= 500; }
    }
}
