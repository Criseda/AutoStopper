package me.criseda.autostopper.docker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ProcessCommandRunner implements CommandRunner {
    private static final int MAX_OUTPUT_LENGTH = 4096;
    private static final Duration KILL_GRACE_PERIOD = Duration.ofSeconds(2);

    private final Duration killGracePeriod;

    public ProcessCommandRunner() {
        this(KILL_GRACE_PERIOD);
    }

    public ProcessCommandRunner(Duration killGracePeriod) {
        if (killGracePeriod.isNegative() || killGracePeriod.isZero()) {
            throw new IllegalArgumentException("killGracePeriod must be positive");
        }
        this.killGracePeriod = killGracePeriod;
    }

    @Override
    public CommandOutput run(List<String> command, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            return new CommandOutput(CommandOutput.Outcome.SPAWN_FAILED, -1, "", e.getMessage());
        }

        StreamGobbler stdoutGobbler = new StreamGobbler(process.getInputStream());
        StreamGobbler stderrGobbler = new StreamGobbler(process.getErrorStream());
        stdoutGobbler.start();
        stderrGobbler.start();

        boolean exited;
        try {
            exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminate(process, stdoutGobbler, stderrGobbler);
            return new CommandOutput(CommandOutput.Outcome.TIMED_OUT, -1,
                    stdoutGobbler.getOutput(), stderrGobbler.getOutput());
        }

        if (!exited) {
            terminate(process, stdoutGobbler, stderrGobbler);
            return new CommandOutput(CommandOutput.Outcome.TIMED_OUT, -1,
                    stdoutGobbler.getOutput(), stderrGobbler.getOutput());
        }

        joinQuietly(stdoutGobbler);
        joinQuietly(stderrGobbler);
        return new CommandOutput(CommandOutput.Outcome.COMPLETED, process.exitValue(),
                stdoutGobbler.getOutput(), stderrGobbler.getOutput());
    }

    private void terminate(Process process, StreamGobbler... gobblers) {
        process.destroy();
        if (!joinQuietly(gobblers)) {
            process.destroyForcibly();
            joinQuietly(gobblers);
        }
    }

    private boolean joinQuietly(Thread... threads) {
        boolean allJoined = true;
        for (Thread thread : threads) {
            try {
                thread.join(killGracePeriod.toMillis());
                allJoined &= !thread.isAlive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                allJoined = false;
            }
        }
        return allJoined;
    }

    private static class StreamGobbler extends Thread {
        private final InputStream stream;
        private final StringBuilder output = new StringBuilder();

        StreamGobbler(InputStream stream) {
            this.stream = stream;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLine(line);
                }
            } catch (IOException e) {
                // Stream closed by process termination - output collected so far is retained.
            }
        }

        private void appendLine(String line) {
            if (output.length() >= MAX_OUTPUT_LENGTH) {
                return;
            }
            int remaining = MAX_OUTPUT_LENGTH - output.length();
            if (remaining <= 0) {
                output.append("[output truncated]");
                return;
            }
            if (line.length() > remaining) {
                output.append(line, 0, remaining).append("[output truncated]");
                return;
            }
            output.append(line).append(System.lineSeparator());
        }

        String getOutput() {
            return output.toString();
        }
    }
}
