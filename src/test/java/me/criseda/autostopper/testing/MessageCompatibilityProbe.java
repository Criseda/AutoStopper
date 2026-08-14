package me.criseda.autostopper.testing;

import me.criseda.autostopper.messages.AutoStopperMessages;
import me.criseda.autostopper.operational.OperationalFailure;
import me.criseda.autostopper.operational.OperationalServerStatus;
import me.criseda.autostopper.operational.OperationalState;
import net.kyori.adventure.text.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

/** Exercises every public message factory when loaded with a pinned Velocity runtime. */
public final class MessageCompatibilityProbe {
    private MessageCompatibilityProbe() {
    }

    public static int verifyAll() {
        Method[] factories = Arrays.stream(AutoStopperMessages.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getReturnType() == Component.class)
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
                if (!(message instanceof Component)) {
                    throw new IllegalStateException(factory + " did not return an Adventure component");
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
        if (type == int.class) {
            return 1;
        }
        if (type == Long.class) {
            return 1L;
        }
        if (type == Duration.class) {
            return Duration.ofSeconds(1);
        }
        if (type == Component.class) {
            return Component.text("compatibility-probe");
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
