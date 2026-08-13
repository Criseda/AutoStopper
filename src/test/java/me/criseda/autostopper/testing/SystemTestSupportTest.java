package me.criseda.autostopper.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemTestSupportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void computesSha256ForExactFileBytes() throws Exception {
        Path file = temporaryDirectory.resolve("candidate.jar");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                SystemTestSupport.sha256(file));
    }

    @Test
    void recursivelyDeletesOnlyTheRequestedTree() throws Exception {
        Path tree = temporaryDirectory.resolve("scenario");
        Files.createDirectories(tree.resolve("nested"));
        Files.writeString(tree.resolve("nested/evidence.txt"), "evidence", StandardCharsets.UTF_8);
        Path sibling = temporaryDirectory.resolve("keep.txt");
        Files.writeString(sibling, "keep", StandardCharsets.UTF_8);

        SystemTestSupport.deleteRecursively(tree);

        assertFalse(Files.exists(tree));
        assertTrue(Files.exists(sibling));
    }

    @Test
    void commandUsesRequestedDirectoryEnvironmentAndMergedOutput() throws Exception {
        SystemTestSupport.CommandResult result = SystemTestSupport.runCommand(
                Duration.ofSeconds(10),
                temporaryDirectory,
                Map.of("AUTOSTOPPER_TEST_VALUE", "candidate-value"),
                probeCommand("inspect"));

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("cwd=" + temporaryDirectory.toAbsolutePath().normalize()));
        assertTrue(result.output().contains("env=candidate-value"));
        assertTrue(result.output().contains("stderr=merged"));
    }

    @Test
    void commandPreservesNonZeroExitForTypedAssertion() throws Exception {
        SystemTestSupport.CommandResult result = SystemTestSupport.runCommand(
                Duration.ofSeconds(10), null, Map.of(), probeCommand("fail"));

        assertEquals(7, result.exitCode());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SystemTestSupport.requireSuccess(result, "probe failed"));
        assertTrue(error.getMessage().contains("probe failed (exit 7)"));
        assertTrue(error.getMessage().contains("deliberate failure"));
    }

    @Test
    void commandTimeoutIsHardAndIncludesCapturedOutput() {
        long startedAt = System.nanoTime();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SystemTestSupport.runCommand(
                        Duration.ofMillis(200), null, Map.of(), probeCommand("sleep")));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(15)) < 0,
                () -> "timeout exceeded its bounded termination grace: " + elapsed);
        assertTrue(error.getMessage().contains("Command timed out after PT0.2S"));
        assertTrue(error.getMessage().contains("sleeping"));
    }

    private List<String> probeCommand(String action) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Probe.class.getName());
        command.add(action);
        return command;
    }

    private Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("windows")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    public static final class Probe {
        private Probe() {
        }

        public static void main(String[] arguments) throws Exception {
            switch (arguments[0]) {
                case "inspect" -> {
                    System.out.println("cwd=" + Path.of("").toAbsolutePath().normalize());
                    System.out.println("env=" + System.getenv("AUTOSTOPPER_TEST_VALUE"));
                    System.err.println("stderr=merged");
                }
                case "fail" -> {
                    System.out.println("deliberate failure");
                    System.exit(7);
                }
                case "sleep" -> {
                    System.out.println("sleeping");
                    System.out.flush();
                    Thread.sleep(Duration.ofSeconds(30));
                }
                default -> throw new IllegalArgumentException("Unknown probe action: " + arguments[0]);
            }
        }
    }
}
