package me.criseda.autostopper.docker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class CommandRunnerTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    @Test
    public void testRun_SuccessCapturesStdout() {
        CommandOutput output = runner.run(shellCommand("echo hello"), Duration.ofSeconds(10));

        assertEquals(CommandOutput.Outcome.COMPLETED, output.outcome());
        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("hello"));
    }

    @Test
    public void testRun_NonZeroExitCode() {
        CommandOutput output = runner.run(shellCommand("exit 7"), Duration.ofSeconds(10));

        assertEquals(CommandOutput.Outcome.COMPLETED, output.outcome());
        assertEquals(7, output.exitCode());
    }

    @Test
    public void testRun_CapturesStderr() {
        CommandOutput output = runner.run(shellCommand("echo err 1>&2"), Duration.ofSeconds(10));

        assertEquals(CommandOutput.Outcome.COMPLETED, output.outcome());
        assertTrue(output.stderr().contains("err"));
    }

    @Test
    public void testRun_DecodesUtf8Output() {
        CommandOutput output = runner.run(utf8Command(), Duration.ofSeconds(10));

        assertEquals(CommandOutput.Outcome.COMPLETED, output.outcome());
        assertTrue(output.stdout().contains("h\u00e9llo w\u00f6rld"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRun_TimeoutTerminatesBlockingProcess() {
        long start = System.nanoTime();
        CommandOutput output = runner.run(sleepCommand(), Duration.ofMillis(500));

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(CommandOutput.Outcome.TIMED_OUT, output.outcome());
        assertTrue(elapsedMillis < 3000, "Timed-out call should return quickly, took " + elapsedMillis + "ms");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRun_InterruptionTerminatesOwnedProcess() throws InterruptedException {
        AtomicReference<CommandOutput> output = new AtomicReference<>();
        Thread caller = new Thread(() -> output.set(runner.run(sleepCommand(), Duration.ofSeconds(30))));
        caller.start();
        Thread.sleep(300);

        caller.interrupt();
        caller.join(3000);

        assertFalse(caller.isAlive(), "interrupted Docker command did not terminate promptly");
        assertNotNull(output.get());
        assertEquals(CommandOutput.Outcome.TIMED_OUT, output.get().outcome());
    }

    @Test
    public void testRun_SpawnFailure() {
        CommandOutput output = runner.run(
                List.of("definitely-not-a-real-executable-xyz", "arg"), Duration.ofSeconds(10));

        assertEquals(CommandOutput.Outcome.SPAWN_FAILED, output.outcome());
    }

    @Test
    public void testRun_RejectsZeroTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(shellCommand("echo x"), Duration.ZERO));
    }

    private static List<String> shellCommand(String command) {
        if (isWindows()) {
            return List.of("cmd", "/c", command);
        }
        return List.of("sh", "-c", command);
    }

    private static List<String> utf8Command() {
        if (isWindows()) {
            return List.of("powershell", "-NoProfile", "-Command",
                    "[Console]::OutputEncoding=[Text.Encoding]::UTF8; 'h\u00e9llo w\u00f6rld'");
        }
        return shellCommand("printf 'h\u00e9llo w\u00f6rld\\n'");
    }

    private static List<String> sleepCommand() {
        if (isWindows()) {
            return List.of("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 30");
        }
        return List.of("sleep", "30");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
