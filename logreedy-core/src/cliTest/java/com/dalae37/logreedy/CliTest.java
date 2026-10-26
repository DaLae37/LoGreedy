package com.dalae37.logreedy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public final class CliTest {
    public static void main(String[] args) throws Exception {
        Check(new String[] {"--version"}, 0, "LoGreedy 0.1.0", false);
        Check(new String[] {"--help"}, 0, "--config", false);
        Check(new String[] {"--config"}, 2, "Unknown arguments", true);
        Check(new String[] {"--invalid"}, 2, "Unknown arguments", true);
        Check(new String[] {"--version", "--help"}, 2, "Unknown arguments", true);
        System.out.println("CliTest: 5 cases passed");
    }

    private static void Check(String[] args, int expectedStatus, String expectedText,
            boolean errorExpected) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = Cli.Run(args, new PrintStream(output, true, "UTF-8"),
                new PrintStream(error, true, "UTF-8"));
        String actual = (errorExpected ? error : output).toString("UTF-8");
        if (status != expectedStatus || !actual.contains(expectedText)
                || (errorExpected ? output : error).size() != 0) {
            throw new AssertionError("CLI status/output mismatch: " + status + ": " + actual);
        }
    }
}
