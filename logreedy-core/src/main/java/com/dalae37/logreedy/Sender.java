package com.dalae37.logreedy;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class Sender implements Runnable {
    private final AgentConfig config;
    private final DiskQueue queue;
    private final LokiClient client;
    private final Consumer<String> log;
    private final Faults faults;
    private volatile boolean stopping;
    private volatile long deadline;
    public volatile Throwable failure;
    public final AtomicLong sent = new AtomicLong(), retries = new AtomicLong(), rejected = new AtomicLong();
    public Sender(AgentConfig config, DiskQueue queue, Consumer<String> log) throws IOException {
        this(config, queue, log, Faults.NONE);
    }
    Sender(AgentConfig config, DiskQueue queue, Consumer<String> log, Faults faults) throws IOException {
        this.config = config; this.queue = queue; this.log = log; this.faults = faults;
        client = new LokiClient(config);
    }
    public void Stop(long deadline) { this.deadline = deadline; stopping = true; }
    public void Cancel() { client.Cancel(); }
    @Override public void run() {
        long delay = config.retryMs;
        try {
            while (!Thread.currentThread().isInterrupted() && (!stopping || (queue.Pending() > 0 && System.currentTimeMillis() < deadline))) {
                List<Event> batch = queue.Batch(config.batchEvents, config.batchBytes);
                if (batch.isEmpty()) { Thread.sleep(Math.min(config.pollMs, 100)); continue; }
                LokiClient.Result result;
                try { result = client.Send(batch); }
                catch (IOException exception) {
                    retries.incrementAndGet(); log.accept("Loki I/O failure; durable events retained");
                    Pause(delay); delay = Math.min(config.retryMaxMs, delay * 2); continue;
                }
                if (result.Success()) {
                    faults.At("loki.accepted"); queue.Ack(batch); sent.addAndGet(batch.size()); delay = config.retryMs;
                } else if (result.Retry()) {
                    retries.incrementAndGet(); log.accept("Loki HTTP " + result.code + "; retrying");
                    Pause(Math.max(delay, result.retryAfter)); delay = Math.min(config.retryMaxMs, delay * 2);
                } else {
                    // Isolate individual rejects rather than quarantining an entire mixed batch.
                    Event event = batch.get(0);
                    if (batch.size() > 1) {
                        try { result = client.Send(Collections.singletonList(event)); }
                        catch (IOException exception) { retries.incrementAndGet(); Pause(delay); delay = Math.min(config.retryMaxMs, delay * 2); continue; }
                    }
                    if (result.Success()) { faults.At("loki.accepted"); queue.Ack(Collections.singletonList(event)); sent.incrementAndGet(); }
                    else if (result.Retry()) { retries.incrementAndGet(); Pause(Math.max(delay, result.retryAfter)); delay = Math.min(config.retryMaxMs, delay * 2); }
                    else {
                        queue.Quarantine(event); rejected.incrementAndGet();
                        log.accept("Loki permanent HTTP " + result.code + "; quarantined event " + event.id + " (retained in WAL)");
                    }
                }
            }
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        catch (Throwable exception) { failure = exception; log.accept("Sender stopped on durable-state failure: " + exception.getClass().getSimpleName()); }
    }
    private void Pause(long delay) throws InterruptedException {
        long until = System.currentTimeMillis() + delay;
        while (System.currentTimeMillis() < until) {
            if (stopping && System.currentTimeMillis() >= deadline) { return; }
            Thread.sleep(Math.min(100, Math.max(1, until - System.currentTimeMillis())));
        }
    }
}
