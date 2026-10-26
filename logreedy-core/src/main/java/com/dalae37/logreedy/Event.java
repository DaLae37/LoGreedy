package com.dalae37.logreedy;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.regex.Matcher;

public final class Event {
    public long id, nanos;
    public Cursor cursor;
    public String message, level = "", logger = "", timestamp = "", parsedMessage = "";
    public final Map<String, String> labels = new TreeMap<>();

    public static Event Create(AgentConfig config, AgentConfig.Source source, Cursor cursor, String message) {
        Event event = new Event(); event.cursor = cursor; event.message = message;
        event.labels.put("agent", config.agentId); event.labels.put("host", config.host);
        event.labels.put("environment", config.environment); event.labels.put("application", source.application);
        event.labels.put("source", source.name);
        if (source.extract != null) {
            Matcher match = source.extract.matcher(message);
            if (match.find()) {
                event.level = Group(match, "level"); event.logger = Group(match, "logger");
                event.timestamp = Group(match, "timestamp"); event.parsedMessage = Group(match, "message");
                try {
                    Instant instant = OffsetDateTime.parse(event.timestamp).toInstant();
                    if (config.Text("loki.timestamp.mode", "ingest").equals("parsed")) { event.nanos = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1000000000L), instant.getNano()); }
                } catch (RuntimeException ignored) { /* Unparsed timestamp stays available as metadata. */ }
            }
        }
        if (event.nanos <= 0) { event.nanos = System.currentTimeMillis() * 1000000L; }
        return event;
    }

    private static String Group(Matcher match, String name) {
        try { String value = match.group(name); return value == null ? "" : value; }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    public void Write(DataOutput output) throws IOException {
        output.writeLong(id); output.writeLong(nanos); cursor.Write(output);
        Codec.String(output, message); Codec.String(output, level); Codec.String(output, logger);
        Codec.String(output, timestamp); Codec.String(output, parsedMessage);
        output.writeInt(labels.size());
        for (Map.Entry<String, String> label : labels.entrySet()) { Codec.String(output, label.getKey()); Codec.String(output, label.getValue()); }
    }

    public static Event Read(DataInput input) throws IOException {
        Event event = new Event(); event.id = input.readLong(); event.nanos = input.readLong(); event.cursor = Cursor.Read(input);
        event.message = Codec.String(input); event.level = Codec.String(input); event.logger = Codec.String(input);
        event.timestamp = Codec.String(input); event.parsedMessage = Codec.String(input);
        int count = input.readInt();
        if (count < 0 || count > 16) { throw new IOException("Invalid label count"); }
        for (int index = 0; index < count; index++) { event.labels.put(Codec.String(input), Codec.String(input)); }
        return event;
    }
}
