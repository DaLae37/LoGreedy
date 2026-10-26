package com.dalae37.logreedy;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

public final class Agent {
    private final AgentConfig config;
    private final Execution.Factory factory;
    private volatile boolean stopping;
    private final CountDownLatch wake = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    public Agent(AgentConfig config, Execution.Factory factory) { this.config = config; this.factory = factory; }
    public void Stop() { stopping = true; wake.countDown(); }
    public void Await() throws InterruptedException { finished.await(config.shutdownMs + config.connectMs + config.readMs + 2000L, TimeUnit.MILLISECONDS); }

    public int Run() {
        AgentLog logger = null; Execution execution = null; DiskQueue queue = null; Sender sender = null; Future<?> worker = null;
        Thread hook = new Thread(() -> { Stop(); try { Await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } }, "LoGreedy-shutdown");
        boolean hookInstalled = false;
        int code = 0;
        try {
            queue = new DiskQueue(config.spool, config.checkpoint, config.spoolBytes, config.maxFiles);
            logger = new AgentLog(config.agentLog, config.Text("agent.log.level", "INFO"));
            Runtime.getRuntime().addShutdownHook(hook); hookInstalled = true;
            execution = factory.Create(config.concurrency);
            sender = new Sender(config, queue, logger::Warning);
            worker = execution.Submit(sender);
            Tailer tailer = new Tailer(config, queue, logger::Warning);
            logger.Info("LoGreedy 0.1.0 started; sources=" + config.sources.size());
            long statsAt = 0;
            while (!stopping) {
                if (sender.failure != null) { throw new IOException("Sender failed", sender.failure); }
                tailer.Poll();
                if (System.currentTimeMillis() >= statsAt) {
                    logger.Info("readBytes=" + tailer.readBytes + " queued=" + tailer.committedEvents + " sent=" + sender.sent.get()
                            + " pending=" + queue.Pending() + " quarantined=" + queue.Quarantined()
                            + " retries=" + sender.retries.get() + " backpressure=" + tailer.backpressure + " spoolBytes=" + queue.Bytes());
                    statsAt = System.currentTimeMillis() + 30000;
                }
                wake.await(config.pollMs, TimeUnit.MILLISECONDS);
            }
            tailer.Flush();
            sender.Stop(System.currentTimeMillis() + config.shutdownMs);
            worker.get(config.shutdownMs + config.connectMs + config.readMs + 1000L, TimeUnit.MILLISECONDS);
            if (sender.failure != null) { throw new IOException("Sender failed during shutdown", sender.failure); }
            logger.Info("Graceful shutdown; pending=" + queue.Pending() + " quarantined=" + queue.Quarantined());
        } catch (DiskQueue.LockedException exception) { System.err.println(exception.getMessage()); code = 4; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); code = 3; }
        catch (Exception exception) {
            if (logger != null) {
                try { logger.Severe("Agent failed: " + exception.getClass().getSimpleName() + "; preserve spool and inspect configuration/storage"); }
                catch (UncheckedIOException ignored) { /* Storage failure is also reported to stderr. */ }
            }
            System.err.println("LoGreedy failed: " + exception.getClass().getSimpleName()); code = 3;
        } finally {
            if (sender != null) { sender.Stop(0); sender.Cancel(); }
            if (worker != null && !worker.isDone()) { worker.cancel(true); }
            if (execution != null) { execution.close(); }
            if (queue != null) { try { queue.close(); } catch (IOException exception) { code = 3; } }
            finished.countDown();
            if (hookInstalled) { try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { /* JVM shutdown in progress. */ } }
        }
        return code;
    }
}
