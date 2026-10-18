package me.criseda.autostopper.testing;

import me.criseda.autostopper.messages.AutoStopperMessages;
import me.criseda.autostopper.messages.OperatorFailureGuide;
import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.operational.OperationalServerStatus;
import me.criseda.autostopper.operational.OperationalState;
import me.criseda.autostopper.telemetry.TelemetryOperationType;
import me.criseda.autostopper.telemetry.TelemetryOrigin;
import me.criseda.autostopper.telemetry.TelemetryOutcome;
import me.criseda.autostopper.telemetry.TelemetryRecord;
import net.kyori.adventure.text.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Exercises every public message factory when loaded with a pinned Velocity runtime. */
public final class MessageCompatibilityProbe {
    private MessageCompatibilityProbe() {
    }

    public static int verifyAll() {
        Method[] factories = Arrays.stream(AutoStopperMessages.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getReturnType() == Component.class
                        || method.getReturnType() == List.class)
                .sorted(Comparator.comparing(Method::toGenericString))
                .toArray(Method[]::new);
        if (factories.length == 0) {
            throw new IllegalStateException("AutoStopperMessages has no public message factories");
        }

        for (Method factory : factories) {
            Object[] arguments = Arrays.stream(factory.getParameterTypes())
                    .map(MessageCompatibilityProbe::sampleArgument)
                    .toArray();
            try {
                Object message = factory.invoke(null, arguments);
                boolean components = message instanceof Component
                        || (message instanceof List<?> lines && !lines.isEmpty()
                                && lines.stream().allMatch(Component.class::isInstance));
                if (!components) {
                    throw new IllegalStateException(factory + " did not return Adventure components");
                }
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("Cannot invoke public message factory " + factory, error);
            } catch (InvocationTargetException error) {
                throw new IllegalStateException("Message factory failed against the runtime API: " + factory,
                        error.getCause());
            }
        }
        return factories.length;
    }

    private static Object sampleArgument(Class<?> type) {
        if (type == String.class) {
            return "compatibility-probe";
        }
        if (type == boolean.class || type == Boolean.class) {
            return true;
        }
        if (type == int.class || type == Integer.class) {
            return 1;
        }
        if (type == Long.class || type == long.class) {
            return 1L;
        }
        if (type == List.class) {
            return List.of(new TelemetryRecord(TelemetryOperationType.MANUAL_RESTART, "compatibility-probe",
                    TelemetryOrigin.MANUAL_COMMAND, TelemetryOutcome.RESTARTED_AND_READY,
                    Duration.ofSeconds(12), 0, Instant.EPOCH));
        }
        if (type == Instant.class) {
            return Instant.EPOCH.plusSeconds(60);
        }
        if (type == Duration.class) {
            return Duration.ofSeconds(1);
        }
        if (type == Component.class) {
            return Component.text("compatibility-probe");
        }
        if (type == OperatorFailureGuide.class) {
            return OperatorFailureGuide.START_FAILED;
        }
        if (type == OperationalServerStatus.class) {
            OperationalFailure failure = new OperationalFailure(
                    Instant.EPOCH, "compatibility probe", "bounded detail", "retry");
            return new OperationalServerStatus(
                    OperationalState.RUNNING_UNVERIFIED, 1, Optional.of(failure));
        }
        throw new IllegalStateException("No compatibility-probe argument for " + type.getName());
    }
}
