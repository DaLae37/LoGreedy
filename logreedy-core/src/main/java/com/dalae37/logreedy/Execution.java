package com.dalae37.logreedy;

import java.util.concurrent.*;

public interface Execution extends AutoCloseable {
    Future<?> Submit(Runnable task);
    @Override void close();
    interface Factory { Execution Create(int concurrency); }
}
