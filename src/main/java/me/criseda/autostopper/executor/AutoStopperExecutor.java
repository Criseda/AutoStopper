package me.criseda.autostopper.executor;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class AutoStopperExecutor implements AutoCloseable {
    public static final int DEFAULT_WORKER_COUNT = 2;
    public static final int DEFAULT_QUEUE_CAPACITY = 32;
    private static final long SHUTDOWN_GRACE_SECONDS = 5;

    private final ThreadPoolExecutor executor;
    private final Set<ManagedTask<?>> outstandingTasks = ConcurrentHashMap.newKeySet();

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
                new NamedThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.prestartAllCoreThreads();
    }

    public <T> CompletableFuture<T> supply(Supplier<T> task) {
        ManagedTask<T> managedTask = new ManagedTask<>(Objects.requireNonNull(task, "task"));
        outstandingTasks.add(managedTask);
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

    public boolean shutdown() {
        executor.shutdown();
        try {
            if (executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                return true;
            }
            failOutstandingTasks(new ShutdownException("AutoStopper executor was shut down", null));
            executor.shutdownNow();
            return executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failOutstandingTasks(new ShutdownException("AutoStopper executor shutdown was interrupted", e));
            executor.shutdownNow();
            return false;
        }
    }

    private void failOutstandingTasks(ShutdownException failure) {
        for (ManagedTask<?> task : outstandingTasks) {
            task.fail(failure, true);
        }
    }

    @Override
    public void close() {
        shutdown();
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
                    future.complete(task.get());
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
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

        private void fail(Throwable failure, boolean interruptIfRunning) {
            if (future.completeExceptionally(failure)) {
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

    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "autostopper-worker-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
