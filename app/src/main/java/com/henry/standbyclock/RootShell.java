package com.henry.standbyclock;

import java.io.IOException;
import java.io.InputStream;

final class RootShell {
    private RootShell() {}

    static int run(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        drain(process.getInputStream());
        return process.waitFor();
    }

    static int bringClockToFront(String packageName) {
        String component = packageName + "/.MainActivity";
        String command = "am start --user 0"
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.LAUNCHER"
                + " -f 0x34000000"
                + " -n " + component;
        try {
            return run(command);
        } catch (IOException exception) {
            return -1;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return -2;
        }
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[512];
        while (input.read(buffer) != -1) {
            // Root commands used by this app do not return user-facing output.
        }
        input.close();
    }
}
