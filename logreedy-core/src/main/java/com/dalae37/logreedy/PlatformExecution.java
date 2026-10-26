package com.dalae37.logreedy;

import java.util.concurrent.*;

public final class PlatformExecution implements Execution {
    private final ThreadPoolExecutor executor;
    public PlatformExecution(int concurrency) {
        executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(concurrency), new ThreadPoolExecutor.AbortPolicy());
    }
    @Override public Future<?> Submit(Runnable task) { return executor.submit(task); }
    @Override public void close() {
        executor.shutdownNow();
        try { executor.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
