package com.dalae37.logreedy;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CrashProbe {
    public static void main(String[] args) throws Exception {
        AgentConfig config = new AgentConfig(Paths.get(args[0])); String phase = args[1];
        AtomicBoolean armed = new AtomicBoolean();
        Faults faults = point -> { if (armed.get() && point.equals(phase)) { Runtime.getRuntime().halt(77); } };
        try (DiskQueue queue = new DiskQueue(config.spool, config.checkpoint, config.spoolBytes, config.maxFiles, faults)) {
            armed.set(true);
            Cursor cursor = new Cursor(); cursor.id = "file-1"; cursor.source = "app"; cursor.path = "test.log";
            cursor.key = ""; cursor.prefix = ""; cursor.anchor = ""; cursor.offset = 5;
            queue.Put(Event.Create(config, config.sources.get(0), cursor, "hello"));
            if (phase.equals("loki.accepted")) {
                Sender sender = new Sender(config, queue, message -> { }, faults); sender.run();
            } else if (phase.startsWith("compact") || phase.startsWith("ack")) { queue.Ack(queue.Batch(100, 100000)); }
        }
        throw new AssertionError("Crash point was not reached");
    }
}
