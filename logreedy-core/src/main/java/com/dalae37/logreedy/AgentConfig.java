package com.dalae37.logreedy;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

public final class AgentConfig {
    public final Properties values;
    public final Path base, spool, checkpoint, agentLog;
    public final String agentId, host, environment;
    public final List<Source> sources = new ArrayList<>();
    public final URI loki;
    public final int bufferBytes, pollMs, readBytes, maxFiles, scanEntries, scanDepth;
    public final int eventBytes, eventLines, batchEvents, batchBytes, connectMs, readMs;
    public final int retryMs, retryMaxMs, shutdownMs, concurrency, retireMs;
    public final long spoolBytes;
    public final String auth;
    public final boolean gzip, metadata;

    public AgentConfig(Path file) throws IOException {
        base = file.toAbsolutePath().normalize().getParent();
        values = new Properties();
        try (Reader input = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            values.load(input);
        }
        agentId = Text("agent.id", "logreedy");
        host = Text("agent.host", DefaultHost());
        environment = Text("agent.environment", "default");
        spool = Resolve(Text("spool.path", "data/spool"));
        checkpoint = Resolve(Text("checkpoint.path", "data/checkpoint/state.bin"));
        agentLog = Resolve(Text("agent.log.path", "data/agent.log"));
        if (checkpoint.startsWith(spool) || agentLog.startsWith(spool) || checkpoint.equals(agentLog)) {
            throw new IllegalArgumentException("Checkpoint/log paths must be separate from spool and each other");
        }
        bufferBytes = Number("read.buffer.bytes", 65536, 256, 1048576);
        pollMs = Number("poll.ms", 1000, 10, 3600000);
        readBytes = Number("read.max.bytes.per.file", 262144, bufferBytes, 16777216);
        retireMs = Number("file.retire.ms", 60000, pollMs, 86400000);
        maxFiles = Number("files.max", 128, 1, 4096);
        scanEntries = Number("scan.entries.max", 10000, 1, 1000000);
        scanDepth = Number("scan.depth.max", 16, 1, 128);
        eventBytes = Number("event.max.bytes", 262144, 256, 16777216);
        eventLines = Number("event.max.lines", 1000, 1, 100000);
        batchEvents = Number("loki.batch.events", 100, 1, 10000);
        batchBytes = Number("loki.batch.bytes", 1048576, eventBytes, 67108864);
        connectMs = Number("loki.connect.timeout.ms", 5000, 1, 300000);
        readMs = Number("loki.read.timeout.ms", 10000, 1, 300000);
        retryMs = Number("loki.retry.initial.ms", 1000, 1, 3600000);
        retryMaxMs = Number("loki.retry.max.ms", 60000, retryMs, 3600000);
        shutdownMs = Number("shutdown.timeout.ms", 15000, 1, 600000);
        concurrency = Number("send.concurrency", 1, 1, 16);
        spoolBytes = LongNumber("spool.max.bytes", 268435456L, eventBytes * 4L + 8192, 1099511627776L);
        if (!Arrays.asList("ingest", "parsed").contains(Text("loki.timestamp.mode", "ingest"))) {
            throw new IllegalArgumentException("loki.timestamp.mode must be ingest/parsed");
        }
        gzip = Flag("loki.gzip", true);
        metadata = Flag("loki.structured.metadata", false);
        try { loki = new URI(Text("loki.url", "http://127.0.0.1:3100/loki/api/v1/push")); }
        catch (URISyntaxException exception) { throw new IllegalArgumentException("Invalid Loki URL", exception); }
        if (loki.getHost() == null || loki.getUserInfo() != null || loki.getFragment() != null
                || !"/loki/api/v1/push".equals(loki.getPath())
                || !("https".equals(loki.getScheme()) || "http".equals(loki.getScheme()))) {
            throw new IllegalArgumentException("Loki URL must be HTTP(S) /loki/api/v1/push without embedded credentials");
        }
        if (!"https".equals(loki.getScheme()) && !Flag("loki.allow.http", false)) {
            throw new IllegalArgumentException("HTTP requires loki.allow.http=true (trusted network/mock only)");
        }
        String authEnv = Text("loki.auth.env", "");
        auth = authEnv.isEmpty() ? "" : System.getenv(authEnv);
        if (auth == null || auth.indexOf('\r') >= 0 || auth.indexOf('\n') >= 0 || (!authEnv.isEmpty() && auth.isEmpty())) {
            throw new IllegalArgumentException("Missing/invalid Loki authorization environment variable");
        }
        java.util.logging.Level.parse(Text("agent.log.level", "INFO"));
        Set<String> names = new HashSet<>();
        for (String raw : Text("sources", "").split(",")) {
            String name = raw.trim();
            if (!name.matches("[A-Za-z0-9_-]+") || !names.add(name)) {
                throw new IllegalArgumentException("sources must contain unique simple names");
            }
            sources.add(new Source(name));
        }
    }

    private static String DefaultHost() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (UnknownHostException exception) { return "unknown-host"; }
    }
    public Path Resolve(String path) { return base.resolve(path).toAbsolutePath().normalize(); }
    public String Text(String name, String fallback) { return values.getProperty(name, fallback).trim(); }
    public boolean Flag(String name, boolean fallback) {
        String value = Text(name, String.valueOf(fallback));
        if (!value.equals("true") && !value.equals("false")) { throw new IllegalArgumentException(name + " must be true/false"); }
        return Boolean.parseBoolean(value);
    }
    public int Number(String name, int fallback, int min, int max) { return (int) LongNumber(name, fallback, min, max); }
    public long LongNumber(String name, long fallback, long min, long max) {
        long value;
        try { value = Long.parseLong(Text(name, String.valueOf(fallback))); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(name + " must be an integer"); }
        if (value < min || value > max) { throw new IllegalArgumentException(name + " out of range"); }
        return value;
    }

    public final class Source {
        public final String name, application, initial;
        public final Charset charset;
        public final List<Path> patterns = new ArrayList<>();
        public final Pattern multiline, extract;
        public final int timeoutMs;
        Source(String name) {
            this.name = name;
            String prefix = "source." + name + ".";
            application = Text(prefix + "application", name);
            initial = Text(prefix + "initial", "end");
            if (!initial.equals("begin") && !initial.equals("end")) { throw new IllegalArgumentException(prefix + "initial must be begin/end"); }
            charset = Charset.forName(Text(prefix + "charset", "UTF-8"));
            String paths = Text(prefix + "paths", "");
            if (paths.isEmpty()) { throw new IllegalArgumentException(prefix + "paths is required"); }
            for (String path : paths.split("\\|")) { patterns.add(Resolve(path.trim())); }
            String start = Text(prefix + "multiline.start", "");
            multiline = start.isEmpty() ? null : Pattern.compile(start);
            String regex = Text(prefix + "extract.regex", "");
            extract = regex.isEmpty() ? null : Pattern.compile(regex, Pattern.DOTALL);
            timeoutMs = Number(prefix + "multiline.timeout.ms", 2000, pollMs, 3600000);
        }
    }
}
