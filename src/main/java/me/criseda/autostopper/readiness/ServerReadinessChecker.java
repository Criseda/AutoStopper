package me.criseda.autostopper.readiness;

import me.criseda.autostopper.config.ReadinessSettings;
import me.criseda.autostopper.config.ReadinessStrategy;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.docker.ContainerHealth;
import me.criseda.autostopper.docker.ContainerStatus;
import me.criseda.autostopper.docker.DockerManager;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class ServerReadinessChecker {
    private final Logger logger;
    private final DockerManager dockerManager;
    private final MinecraftStatusProbe statusProbe;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    public ServerReadinessChecker(Logger logger, DockerManager dockerManager, MinecraftStatusProbe statusProbe) {
        this(logger, dockerManager, statusProbe, System::nanoTime,
                nanos -> java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos));
    }

    ServerReadinessChecker(Logger logger, DockerManager dockerManager, MinecraftStatusProbe statusProbe,
            LongSupplier nanoTime, Sleeper sleeper) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dockerManager = Objects.requireNonNull(dockerManager, "dockerManager");
        this.statusProbe = Objects.requireNonNull(statusProbe, "statusProbe");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    public ReadinessResult awaitReady(ServerMapping mapping, ReadinessSettings.Target target) {
        ReadinessSettings settings = mapping.readiness();
        ReadinessStrategy strategy = settings.strategy();
        if (strategy.usesMinecraftStatus() && target == null) {
            return finish(mapping, strategy,
                    ReadinessResult.failure(ReadinessResult.Outcome.INVALID_TARGET, 0, null));
        }

        logger.info("Waiting up to {}ms for server {} readiness using {}{}",
                settings.timeout().toMillis(),
                mapping.serverName(),
                strategy.configValue(),
                target == null ? "" : " at " + target.host() + ":" + target.port());

        long deadline = saturatedAdd(nanoTime.getAsLong(), settings.timeout().toNanos());
        MinecraftStatusProbe.Outcome lastProbe = null;
        int attempts = 0;
        while (true) {
            long remaining = deadline - nanoTime.getAsLong();
            if (remaining <= 0) {
                return finish(mapping, strategy,
                        ReadinessResult.failure(ReadinessResult.Outcome.TIMED_OUT, attempts, lastProbe));
            }
            if (Thread.currentThread().isInterrupted()) {
                return finish(mapping, strategy,
                        ReadinessResult.failure(ReadinessResult.Outcome.INTERRUPTED, attempts, lastProbe));
            }

            attempts++;
            ContainerHealth health = null;
            if (strategy.usesDockerHealth()) {
                health = dockerManager.getContainerHealth(
                        mapping.containerName(), positiveRemaining(remaining));
                ReadinessResult terminal = healthResult(health, strategy, attempts, lastProbe);
                if (terminal != null) {
                    return finish(mapping, strategy, terminal);
                }
                if (health == ContainerHealth.HEALTHY) {
                    return finish(mapping, strategy, ReadinessResult.ready(attempts));
                }
            }

            if (strategy.usesMinecraftStatus()) {
                remaining = deadline - nanoTime.getAsLong();
                if (remaining <= 0) {
                    return finish(mapping, strategy,
                            ReadinessResult.failure(ReadinessResult.Outcome.TIMED_OUT, attempts, lastProbe));
                }
                MinecraftStatusProbe.ProbeResult probe = statusProbe.probe(
                        target.host(),
                        target.port(),
                        settings.connectTimeout(),
                        settings.readTimeout(),
                        positiveRemaining(remaining));
                lastProbe = probe.outcome();
                if (probe.ready()) {
                    return finish(mapping, strategy, ReadinessResult.ready(attempts));
                }

                if (!strategy.usesDockerHealth()) {
                    remaining = deadline - nanoTime.getAsLong();
                    if (remaining > 0) {
                        ContainerStatus status = dockerManager.getContainerStatus(
                                mapping.containerName(), positiveRemaining(remaining));
                        ReadinessResult terminal = statusResult(status, attempts, lastProbe);
                        if (terminal != null) {
                            return finish(mapping, strategy, terminal);
                        }
                    }
                }
            }

            remaining = deadline - nanoTime.getAsLong();
            if (remaining <= 0) {
                return finish(mapping, strategy,
                        ReadinessResult.failure(ReadinessResult.Outcome.TIMED_OUT, attempts, lastProbe));
            }
            long sleepNanos = Math.min(settings.probeInterval().toNanos(), remaining);
            try {
                sleeper.sleep(sleepNanos);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return finish(mapping, strategy,
                        ReadinessResult.failure(ReadinessResult.Outcome.INTERRUPTED, attempts, lastProbe));
            }
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

    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }
}
