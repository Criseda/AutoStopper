package me.criseda.autostopper.executor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class AutoStopperExecutorTest {

    @Test
    public void testSupplyCompletesWithValue() {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        try {
            CompletableFuture<String> future = executor.supply(() -> "hello");

            assertEquals("hello", future.join());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void testSupplyPropagatesFailure() {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        try {
            CompletableFuture<String> future = executor.supply(() -> {
                throw new IllegalStateException("boom");
            });

            assertCompletesWith(future, IllegalStateException.class, "boom");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void testBlockingTaskDoesNotHoldCallingThread() throws InterruptedException {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        try {
            CountDownLatch blocked = new CountDownLatch(1);
            // A fake "Docker call" that blocks on the executor.
            executor.supply(() -> {
                try {
                    blocked.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            });

            Instant start = Instant.now();
            CompletableFuture<String> second = executor.supply(() -> "queued");
            long waitMillis = Duration.between(start, Instant.now()).toMillis();

            // Submitting and returning must not block on the running task.
            assertTrue(waitMillis < 2000, "submit blocked for " + waitMillis + "ms");
            assertFalse(second.isDone(), "queued task must not run while a worker is blocked");

            blocked.countDown();
            assertEquals("queued", second.join());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void testParallelWorkers() throws InterruptedException {
        AutoStopperExecutor executor = new AutoStopperExecutor(2, 4);
        try {
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger maxConcurrent = new AtomicInteger();
            CountDownLatch allDone = new CountDownLatch(2);

            Supplier<String> task = () -> {
                try {
                    int now = concurrent.incrementAndGet();
                    maxConcurrent.accumulateAndGet(now, Math::max);
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    concurrent.decrementAndGet();
                    allDone.countDown();
                }
                return "ok";
            };

            CompletableFuture<String> a = executor.supply(task);
            CompletableFuture<String> b = executor.supply(task);

            a.join();
            b.join();
            assertTrue(allDone.await(2, TimeUnit.SECONDS));
            assertEquals(2, maxConcurrent.get(), "two workers should run concurrently");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void testSaturationFailsPredictably() {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        CountDownLatch blocked = new CountDownLatch(1);
        try {
            // Fill the single worker.
            executor.supply(() -> {
                try {
                    blocked.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "blocked";
            });
            // Fill the single queue slot.
            executor.supply(() -> "pending");

            // Third submission is rejected immediately with a typed failure.
            CompletableFuture<String> rejected = executor.supply(() -> "too many");
            assertTrue(rejected.isCompletedExceptionally());
            assertCompletesWith(rejected, AutoStopperExecutor.SaturationException.class, null);

            blocked.countDown();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void testShutdownTerminatesAndRejectsFurtherWork() {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<String> blocked = executor.supply(() -> {
            try {
                started.countDown();
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted while blocked");
            }
            return "never";
        });
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted waiting for worker to start");
        }
        CompletableFuture<String> queued = executor.supply(() -> "queued");

        boolean terminated = executor.shutdown();

        assertTrue(terminated, "executor should terminate cleanly within the grace period");
        assertCompletesWith(blocked, AutoStopperExecutor.ShutdownException.class, null);
        assertCompletesWith(queued, AutoStopperExecutor.ShutdownException.class, null);
        CompletableFuture<String> afterShutdown = executor.supply(() -> "after-shutdown");
        assertCompletesWith(afterShutdown, AutoStopperExecutor.ShutdownException.class, null);
    }

    @Test
    public void testCancellingQueuedTaskFreesQueueCapacity() throws InterruptedException {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        try {
            executor.supply(() -> {
                try {
                    releaseWorker.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "running";
            });

            CompletableFuture<String> cancelled = executor.supply(() -> "cancelled");
            assertTrue(cancelled.cancel(false));
            assertTrue(cancelled.isCancelled());

            CompletableFuture<String> replacement = executor.supply(() -> "replacement");
            assertFalse(replacement.isCompletedExceptionally(), "cancelled task should leave queue capacity");
            releaseWorker.countDown();
            assertEquals("replacement", replacement.join());
        } finally {
            releaseWorker.countDown();
            executor.shutdown();
        }
    }

    @Test
    public void testCancellingRunningTaskInterruptsWorker() throws InterruptedException {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            CompletableFuture<String> future = executor.supply(() -> {
                started.countDown();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return "done";
            });

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "running task should be interrupted");
            assertTrue(future.isCancelled());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void testInvalidConfigurationRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AutoStopperExecutor(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AutoStopperExecutor(1, 0));
    }

    private static <T> void assertCompletesWith(CompletableFuture<T> future,
            Class<? extends Throwable> expectedType, String expectedMessage) {
        try {
            future.join();
            fail("expected future to fail with " + expectedType.getSimpleName());
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            assertInstanceOf(expectedType, cause);
            if (expectedMessage != null) {
                assertTrue(cause.getMessage() != null && cause.getMessage().contains(expectedMessage),
                        "unexpected message: " + cause.getMessage());
            }
        }
    }
}
