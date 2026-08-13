package me.criseda.autostopper.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class SystemTestSupport {
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);

    private SystemTestSupport() {
    }

    static CommandResult runCommand(Duration timeout, String... command) throws Exception {
        return runCommand(timeout, null, Map.of(), List.of(command));
    }

    static CommandResult runCommand(
            Duration timeout, Path workingDirectory, Map<String, String> environment, List<String> command)
            throws Exception {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.environment().putAll(environment);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream input = process.getInputStream()) {
                input.transferTo(output);
            } catch (IOException error) {
                readFailure.set(error);
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            terminateProcessTree(process);
            Thread.currentThread().interrupt();
            throw error;
        }
        if (!finished) {
            terminateProcessTree(process);
        }
        awaitOutputDrain(process, reader, command);
        if (readFailure.get() != null) {
            throw new UncheckedIOException(readFailure.get());
        }

        String text = output.toString(StandardCharsets.UTF_8);
        if (!finished) {
            throw new IllegalStateException("Command timed out after " + timeout + ": "
                    + String.join(" ", command) + System.lineSeparator() + text);
        }
        return new CommandResult(process.exitValue(), text);
    }

    static void requireSuccess(CommandResult result, String message) {
        if (result.exitCode() != 0) {
            throw new IllegalStateException(message + " (exit " + result.exitCode() + ")"
                    + System.lineSeparator() + result.output());
        }
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            });
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }
    }

    private static void terminateProcessTree(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static void awaitOutputDrain(Process process, Thread reader, List<String> command)
            throws IOException, InterruptedException {
        reader.join(TERMINATION_GRACE.toMillis());
        if (reader.isAlive()) {
            process.getInputStream().close();
            reader.join(TERMINATION_GRACE.toMillis());
        }
        if (reader.isAlive()) {
            reader.interrupt();
            throw new IllegalStateException("Command output did not close after process exit: "
                    + String.join(" ", command));
        }
    }

    record CommandResult(int exitCode, String output) {
    }
}
