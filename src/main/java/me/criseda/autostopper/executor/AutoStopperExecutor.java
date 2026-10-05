package me.criseda.autostopper.executor;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class AutoStopperExecutor implements AutoCloseable {
    public static final int DEFAULT_WORKER_COUNT = 2;
    public static final int DEFAULT_QUEUE_CAPACITY = 32;
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final ThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor timer;
    private final Set<ManagedTask<?>> outstandingTasks = ConcurrentHashMap.newKeySet();
    private final Set<DelayedTask<?>> delayedTasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();

    public AutoStopperExecutor() {
        this(DEFAULT_WORKER_COUNT, DEFAULT_QUEUE_CAPACITY);
    }

    public AutoStopperExecutor(int workerCount, int queueCapacity) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.executor = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory("autostopper-worker-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.prestartAllCoreThreads();
        this.timer = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("autostopper-timer-"));
        this.timer.setRemoveOnCancelPolicy(true);
    }

    public <T> CompletableFuture<T> supply(Supplier<T> task) {
        ManagedTask<T> managedTask = new ManagedTask<>(Objects.requireNonNull(task, "task"));
        outstandingTasks.add(managedTask);
        if (shutdownStarted.get()) {
            managedTask.fail(new ShutdownException("AutoStopper executor is shut down", null), false);
            return managedTask.future;
        }
        try {
            executor.execute(managedTask);
        } catch (RejectedExecutionException e) {
            Throwable failure = executor.isShutdown()
                    ? new ShutdownException("AutoStopper executor is shut down", e)
                    : new SaturationException("AutoStopper executor is saturated", e);
            managedTask.fail(failure, false);
        }
        return managedTask.future;
    }

    /**
     * Runs a task on a worker once the delay has elapsed, without holding a worker while waiting. The task then
     * competes for workers like any other submission, so it can still fail with {@link SaturationException}.
     */
    public <T> CompletableFuture<T> supplyAfter(Duration delay, Supplier<T> task) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(task, "task");
        if (delay.isNegative() || delay.isZero()) {
            return supply(task);
        }
        DelayedTask<T> delayedTask = new DelayedTask<>(task);
        delayedTasks.add(delayedTask);
        delayedTask.whenComplete((ignored, error) -> delayedTasks.remove(delayedTask));
        if (shutdownStarted.get()) {
            delayedTask.completeExceptionally(new ShutdownException("AutoStopper executor is shut down", null));
            return delayedTask;
        }
        try {
            delayedTask.timerFuture = timer.schedule(delayedTask::release, delay.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            delayedTask.completeExceptionally(new ShutdownException("AutoStopper executor is shut down", e));
        }
        return delayedTask;
    }

    public boolean shutdown() {
        return shutdown(DEFAULT_SHUTDOWN_TIMEOUT);
    }

    public boolean shutdown(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        shutdownStarted.set(true);
        timer.shutdownNow();
        executor.shutdownNow();
        failOutstandingTasks(new ShutdownException("AutoStopper executor was shut down", null));
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            boolean workersTerminated = executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
            boolean timerTerminated = timer.awaitTermination(
                    Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return workersTerminated && timerTerminated;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failOutstandingTasks(new ShutdownException("AutoStopper executor shutdown was interrupted", e));
            return false;
        }
    }

    private void failOutstandingTasks(ShutdownException failure) {
        for (DelayedTask<?> task : delayedTasks) {
            task.completeExceptionally(failure);
        }
        for (ManagedTask<?> task : outstandingTasks) {
            task.fail(failure, true);
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    /** Strips the {@link CompletionException} and {@link ExecutionException} wrappers from a future's failure. */
    public static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Maps a failed future's error to a caller outcome: {@code saturated} when the workers were busy,
     * {@code cancelled} when the work was cancelled or the executor shut down, and {@code failed} otherwise.
     */
    public static <T> T classify(Throwable error, T saturated, T cancelled, T failed) {
        Throwable cause = rootCause(error);
        if (cause instanceof SaturationException) {
            return saturated;
        }
        if (cause instanceof CancellationException || cause instanceof ShutdownException) {
            return cancelled;
        }
        return failed;
    }

    public static class SaturationException extends RuntimeException {
        public SaturationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ShutdownException extends RuntimeException {
        public ShutdownException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final class ManagedTask<T> implements Runnable {
        private final Supplier<T> task;
        private final ManagedFuture<T> future;
        private volatile Thread runner;

        private ManagedTask(Supplier<T> task) {
            this.task = task;
            this.future = new ManagedFuture<>(this);
        }

        @Override
        public void run() {
            if (future.isDone()) {
                outstandingTasks.remove(this);
                return;
            }

            runner = Thread.currentThread();
            try {
                if (!future.isDone()) {
                    complete(task.get());
                }
            } catch (Throwable t) {
                fail(t, false);
            } finally {
                runner = null;
                outstandingTasks.remove(this);
            }
        }

        private boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = future.cancelDirect(mayInterruptIfRunning);
            if (cancelled) {
                executor.remove(this);
                outstandingTasks.remove(this);
                Thread runningThread = runner;
                if (mayInterruptIfRunning && runningThread != null) {
                    runningThread.interrupt();
                }
            }
            return cancelled;
        }

        private synchronized void complete(T value) {
            if (shutdownStarted.get()) {
                future.completeExceptionally(new ShutdownException("AutoStopper executor was shut down", null));
            } else {
                future.complete(value);
            }
        }

        private synchronized void fail(Throwable failure, boolean interruptIfRunning) {
            Throwable completionFailure = shutdownStarted.get() && !(failure instanceof ShutdownException)
                    ? new ShutdownException("AutoStopper executor was shut down", failure)
                    : failure;
            if (future.completeExceptionally(completionFailure)) {
                executor.remove(this);
                outstandingTasks.remove(this);
                Thread runningThread = runner;
                if (interruptIfRunning && runningThread != null) {
                    runningThread.interrupt();
                }
            }
        }
    }

    private final class ManagedFuture<T> extends CompletableFuture<T> {
        private final ManagedTask<T> managedTask;

        private ManagedFuture(ManagedTask<T> managedTask) {
            this.managedTask = managedTask;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return managedTask.cancel(mayInterruptIfRunning);
        }

        private boolean cancelDirect(boolean mayInterruptIfRunning) {
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private final class DelayedTask<T> extends CompletableFuture<T> {
        private final Supplier<T> task;
        private volatile ScheduledFuture<?> timerFuture;
        private volatile CompletableFuture<T> work;

        private DelayedTask(Supplier<T> task) {
            this.task = task;
        }

        private void release() {
            if (isDone()) {
                return;
            }
            CompletableFuture<T> submitted = supply(task);
            work = submitted;
            if (isDone()) {
                // Cancelled or shut down while the task was being submitted.
                submitted.cancel(true);
                return;
            }
            submitted.whenComplete((value, error) -> {
                if (error == null) {
                    complete(value);
                } else {
                    completeExceptionally(error);
                }
            });
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                ScheduledFuture<?> pending = timerFuture;
                if (pending != null) {
                    pending.cancel(false);
                }
                CompletableFuture<T> running = work;
                if (running != null) {
                    running.cancel(mayInterruptIfRunning);
                }
            }
            return cancelled;
        }
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
