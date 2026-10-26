package com.dalae37.logreedy;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public final class Cli {
    private Cli() { }
    public static int Run(String[] args, PrintStream out, PrintStream err) {
        return Run(args, out, err, PlatformExecution::new);
    }
    public static int Run(String[] args, PrintStream out, PrintStream err, Execution.Factory factory) {
        if (args.length == 1 && "--version".equals(args[0])) { out.println("LoGreedy " + ReadVersion()); return 0; }
        if (args.length == 1 && "--help".equals(args[0])) {
            out.println("Usage: java -jar LoGreedy-jdk8.jar [--config LoGreedy.properties]");
            out.println("       java -jar LoGreedy-jdk21.jar [--config LoGreedy.properties]");
            out.println("Options: --help, --version, --status, --replay-quarantine [--config file]");
            return 0;
        }
        boolean replay = args.length > 0 && "--replay-quarantine".equals(args[0]);
        boolean status = replay || (args.length > 0 && "--status".equals(args[0]));
        int offset = status ? 1 : 0;
        Path path = Paths.get("LoGreedy.properties");
        if (args.length - offset == 2 && "--config".equals(args[offset])) { path = Paths.get(args[offset + 1]); }
        else if (args.length != offset) { err.println("Unknown arguments. Use --help or --version."); return 2; }
        AgentConfig config;
        try { config = new AgentConfig(path); }
        catch (IOException | IllegalArgumentException exception) { err.println("Invalid configuration: " + exception.getClass().getSimpleName()); return 2; }
        if (status) {
            try (DiskQueue queue = new DiskQueue(config.spool, config.checkpoint, config.spoolBytes, config.maxFiles)) {
                if (replay) { out.println("replayed=" + queue.ReplayQuarantine()); }
                out.println("pending=" + queue.Pending() + " quarantined=" + queue.Quarantined() + " cursors=" + queue.Cursors().size()); return 0;
            } catch (DiskQueue.LockedException exception) { err.println(exception.getMessage()); return 4; }
            catch (IOException exception) { err.println("Cannot inspect durable state: " + exception.getClass().getSimpleName()); return 3; }
        }
        return new Agent(config, factory).Run();
    }
    private static String ReadVersion() {
        Properties properties = new Properties();
        try (InputStream input = Cli.class.getResourceAsStream("/logreedy-version.properties")) {
            if (input == null) { throw new IllegalStateException("Missing version resource"); }
            properties.load(input); return properties.getProperty("version");
        } catch (IOException exception) { throw new IllegalStateException("Cannot read version resource", exception); }
    }
}
