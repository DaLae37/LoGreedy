package com.dalae37.logreedy.runtime.jdk21;

import com.dalae37.logreedy.Execution;
import java.util.concurrent.*;

public final class VirtualExecution implements Execution {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    public VirtualExecution(int concurrency) { permits = new Semaphore(concurrency); }
    @Override public Future<?> Submit(Runnable task) {
        if (!permits.tryAcquire()) { throw new RejectedExecutionException("Concurrency limit reached"); }
        try { return executor.submit(() -> { try { task.run(); } finally { permits.release(); } }); }
        catch (RuntimeException exception) { permits.release(); throw exception; }
    }
    @Override public void close() {
        executor.shutdownNow();
        try { executor.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
