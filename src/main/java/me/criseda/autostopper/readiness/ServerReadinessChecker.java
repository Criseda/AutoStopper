package me.criseda.autostopper.readiness;

import me.criseda.autostopper.config.ReadinessSettings;
import me.criseda.autostopper.config.ReadinessStrategy;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerHealth;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.docker.DockerManager;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class ServerReadinessChecker {
    private final Logger logger;
    private final DockerManager dockerManager;
    private final MinecraftStatusProbe statusProbe;
    private final LongSupplier nanoTime;

    public ServerReadinessChecker(Logger logger, DockerManager dockerManager, MinecraftStatusProbe statusProbe) {
        this(logger, dockerManager, statusProbe, System::nanoTime);
    }

    ServerReadinessChecker(Logger logger, DockerManager dockerManager, MinecraftStatusProbe statusProbe,
            LongSupplier nanoTime) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dockerManager = Objects.requireNonNull(dockerManager, "dockerManager");
        this.statusProbe = Objects.requireNonNull(statusProbe, "statusProbe");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Waits for the mapped server to pass its readiness check. Each attempt is a short, individually bounded task
     * handed to {@code scheduler}, and no thread is held between attempts. Cancelling the returned future cancels
     * the attempt in flight and schedules no further attempts.
     */
    public CompletableFuture<ReadinessResult> awaitReady(ServerMapping mapping, ReadinessSettings.Target target,
            AttemptScheduler scheduler) {
        Objects.requireNonNull(scheduler, "scheduler");
        ReadinessSettings settings = mapping.readiness();
        ReadinessStrategy strategy = settings.strategy();
        if (strategy.usesMinecraftStatus() && target == null) {
            return CompletableFuture.completedFuture(finish(mapping, strategy,
                    ReadinessResult.failure(ReadinessResult.Outcome.INVALID_TARGET, 0, null)));
        }

        logger.info("Waiting up to {}ms for server {} readiness using {}{}",
                settings.timeout().toMillis(),
                mapping.serverName(),
                strategy.configValue(),
                target == null ? "" : " at " + target.host() + ":" + target.port());

        long deadline = saturatedAdd(nanoTime.getAsLong(), settings.timeout().toNanos());
        ReadinessWait wait = new ReadinessWait(mapping, target, scheduler, deadline);
        wait.schedule(Duration.ZERO);
        return wait;
    }

    /**
     * Runs one readiness attempt after a delay. {@link AutoStopperExecutor#supplyAfter} is the production
     * implementation.
     */
    @FunctionalInterface
    public interface AttemptScheduler {
        CompletableFuture<ReadinessResult> schedule(Duration delay, Supplier<ReadinessResult> attempt);
    }

    private final class ReadinessWait extends CompletableFuture<ReadinessResult> {
        private final ServerMapping mapping;
        private final ReadinessSettings.Target target;
        private final ReadinessSettings settings;
        private final ReadinessStrategy strategy;
        private final AttemptScheduler scheduler;
        private final long deadline;
        private final Object lock = new Object();
        private CompletableFuture<ReadinessResult> inFlight;
        // Attempts run one at a time, and each hand-off between them goes through a future completion.
        private int attempts;
        private MinecraftStatusProbe.Outcome lastProbe;

        private ReadinessWait(ServerMapping mapping, ReadinessSettings.Target target, AttemptScheduler scheduler,
                long deadline) {
            this.mapping = mapping;
            this.target = target;
            this.settings = mapping.readiness();
            this.strategy = settings.strategy();
            this.scheduler = scheduler;
            this.deadline = deadline;
        }

        private void schedule(Duration delay) {
            CompletableFuture<ReadinessResult> next;
            try {
                next = scheduler.schedule(delay, this::attempt);
            } catch (RuntimeException error) {
                completeExceptionally(error);
                return;
            }
            synchronized (lock) {
                if (isDone()) {
                    next.cancel(true);
                    return;
                }
                inFlight = next;
            }
            next.whenComplete(this::attemptFinished);
        }

        private void attemptFinished(ReadinessResult result, Throwable error) {
            if (isDone()) {
                return;
            }
            if (error != null) {
                Throwable cause = unwrap(error);
                if (cause instanceof AutoStopperExecutor.SaturationException && attempts > 0) {
                    // Workers are busy with other servers; the next interval retries while the deadline allows.
                    logger.debug("Skipped a readiness attempt for server {} because AutoStopper is busy",
                            mapping.serverName());
                    scheduleNext();
                } else {
                    completeExceptionally(cause);
                }
                return;
            }
            if (result != null) {
                complete(finish(mapping, strategy, result));
                return;
            }
            scheduleNext();
        }

        private void scheduleNext() {
            long remaining = deadline - nanoTime.getAsLong();
            if (remaining <= 0) {
                complete(finish(mapping, strategy,
                        ReadinessResult.failure(ReadinessResult.Outcome.TIMED_OUT, attempts, lastProbe)));
                return;
            }
            schedule(Duration.ofNanos(Math.min(settings.probeInterval().toNanos(), remaining)));
        }

        /** Returns the final result, or {@code null} when the server is not ready yet. */
        private ReadinessResult attempt() {
            long remaining = deadline - nanoTime.getAsLong();
            if (remaining <= 0) {
                return ReadinessResult.failure(ReadinessResult.Outcome.TIMED_OUT, attempts, lastProbe);
            }
            if (Thread.currentThread().isInterrupted()) {
                return ReadinessResult.failure(ReadinessResult.Outcome.INTERRUPTED, attempts, lastProbe);
            }

            attempts++;
            if (strategy.usesDockerHealth()) {
                ContainerHealth health = dockerManager.getContainerHealth(
                        mapping.containerName(), positiveRemaining(remaining));
                ReadinessResult terminal = healthResult(health, strategy, attempts, lastProbe);
                if (terminal != null) {
                    return terminal;
                }
                if (health == ContainerHealth.HEALTHY) {
                    return ReadinessResult.ready(attempts);
                }
            }

            if (strategy.usesMinecraftStatus()) {
                remaining = deadline - nanoTime.getAsLong();
                if (remaining <= 0) {
                    return ReadinessResult.failure(ReadinessResult.Outcome.TIMED_OUT, attempts, lastProbe);
                }
                MinecraftStatusProbe.ProbeResult probe = statusProbe.probe(
                        target.host(),
                        target.port(),
                        settings.connectTimeout(),
                        settings.readTimeout(),
                        positiveRemaining(remaining));
                lastProbe = probe.outcome();
                if (probe.ready()) {
                    return ReadinessResult.ready(attempts);
                }

                if (!strategy.usesDockerHealth()) {
                    remaining = deadline - nanoTime.getAsLong();
                    if (remaining > 0) {
                        ContainerStatus status = dockerManager.getContainerStatus(
                                mapping.containerName(), positiveRemaining(remaining));
                        ReadinessResult terminal = statusResult(status, attempts, lastProbe);
                        if (terminal != null) {
                            return terminal;
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                CompletableFuture<ReadinessResult> attempt;
                synchronized (lock) {
                    attempt = inFlight;
                }
                if (attempt != null) {
                    attempt.cancel(true);
                }
            }
            return cancelled;
        }
    }

    private ReadinessResult healthResult(ContainerHealth health, ReadinessStrategy strategy, int attempts,
            MinecraftStatusProbe.Outcome lastProbe) {
        return switch (health) {
            case STOPPED -> ReadinessResult.failure(ReadinessResult.Outcome.CONTAINER_STOPPED, attempts, lastProbe);
            case MISSING -> ReadinessResult.failure(ReadinessResult.Outcome.CONTAINER_MISSING, attempts, lastProbe);
            case INACCESSIBLE -> strategy == ReadinessStrategy.DOCKER_HEALTH
                    ? ReadinessResult.failure(ReadinessResult.Outcome.DOCKER_INACCESSIBLE, attempts, lastProbe)
                    : null;
            case FAILED -> strategy == ReadinessStrategy.DOCKER_HEALTH
                    ? ReadinessResult.failure(ReadinessResult.Outcome.DOCKER_FAILED, attempts, lastProbe)
                    : null;
            case NO_HEALTHCHECK -> strategy == ReadinessStrategy.DOCKER_HEALTH
                    ? ReadinessResult.failure(ReadinessResult.Outcome.NO_HEALTHCHECK, attempts, lastProbe)
                    : null;
            case HEALTHY, STARTING, UNHEALTHY, TIMED_OUT -> null;
        };
    }

    private ReadinessResult statusResult(ContainerStatus status, int attempts,
            MinecraftStatusProbe.Outcome lastProbe) {
        return switch (status) {
            case STOPPED -> ReadinessResult.failure(ReadinessResult.Outcome.CONTAINER_STOPPED, attempts, lastProbe);
            case MISSING -> ReadinessResult.failure(ReadinessResult.Outcome.CONTAINER_MISSING, attempts, lastProbe);
            case RUNNING, INACCESSIBLE, TIMED_OUT, FAILED -> null;
        };
    }

    private ReadinessResult finish(ServerMapping mapping, ReadinessStrategy strategy, ReadinessResult result) {
        if (result.ready()) {
            logger.info("Server {} passed {} readiness after {} attempt(s)",
                    mapping.serverName(), strategy.configValue(), result.attempts());
        } else {
            logger.warn("Server {} failed {} readiness after {} attempt(s): {}",
                    mapping.serverName(), strategy.configValue(), result.attempts(), result.playerDetail());
        }
        return result;
    }

    private Duration positiveRemaining(long remainingNanos) {
        return Duration.ofNanos(Math.max(1, remainingNanos));
    }

    private long saturatedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
