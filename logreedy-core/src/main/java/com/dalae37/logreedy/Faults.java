package com.dalae37.logreedy;

import java.io.IOException;

/** Dependency injection for deterministic I/O/crash tests; not a configuration option. */
interface Faults {
    Faults NONE = point -> { };
    void At(String point) throws IOException;
}
