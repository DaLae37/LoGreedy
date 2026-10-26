package com.dalae37.logreedy.runtime.jdk21;

import com.dalae37.logreedy.Cli;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.exit(Cli.Run(args, System.out, System.err, VirtualExecution::new));
    }
}
