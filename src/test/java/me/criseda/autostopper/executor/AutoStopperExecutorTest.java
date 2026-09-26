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
            CountDownLatch workerStarted = new CountDownLatch(1);
            CountDownLatch blocked = new CountDownLatch(1);
            // A fake "Docker call" that blocks on the executor.
            executor.supply(() -> {
                workerStarted.countDown();
                try {
                    blocked.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            });
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));

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
    public void testSaturationFailsPredictably() throws InterruptedException {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        try {
            // Fill the single worker.
            executor.supply(() -> {
                workerStarted.countDown();
                try {
                    blocked.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "blocked";
            });
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
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
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        try {
            executor.supply(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "running";
            });
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));

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
    public void testSupplyAfterRunsTaskOnWorkerOnceDelayElapses() {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        try {
            long start = System.nanoTime();
            CompletableFuture<String> future = executor.supplyAfter(Duration.ofMillis(50),
                    () -> Thread.currentThread().getName());

            String thread = future.join();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(thread.startsWith("autostopper-worker-"), "delayed task ran on " + thread);
            assertTrue(elapsedMillis >= 40, "delayed task ran after only " + elapsedMillis + "ms");
            assertEquals("zero", executor.supplyAfter(Duration.ZERO, () -> "zero").join());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void testSupplyAfterHoldsNoWorkerWhileWaiting() {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        try {
            CompletableFuture<String> delayed = executor.supplyAfter(Duration.ofSeconds(60), () -> "late");

            CompletableFuture<String> immediate = executor.supply(() -> "immediate");

            assertEquals("immediate", immediate.orTimeout(2, TimeUnit.SECONDS).join());
            assertFalse(delayed.isDone());
            assertTrue(delayed.cancel(false));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void testCancellingDelayedTaskBeforeItsDelayPreventsItRunning() {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        try {
            AtomicInteger runs = new AtomicInteger();
            CompletableFuture<Integer> cancelled = executor.supplyAfter(Duration.ofMillis(50),
                    runs::incrementAndGet);

            assertTrue(cancelled.cancel(false));
            // The timer releases tasks in deadline order, so the later sentinel runs after the cancelled slot.
            executor.supplyAfter(Duration.ofMillis(150), () -> "sentinel").join();

            assertTrue(cancelled.isCancelled());
            assertEquals(0, runs.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void testCancellingReleasedDelayedTaskInterruptsWorker() throws InterruptedException {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            CompletableFuture<String> future = executor.supplyAfter(Duration.ofMillis(1), () -> {
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
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "released task should be interrupted");
            assertTrue(future.isCancelled());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void testSupplyAfterReportsSaturationWhenReleasedIntoFullExecutor() throws InterruptedException {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        try {
            executor.supply(() -> {
                workerStarted.countDown();
                try {
                    blocked.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "blocked";
            });
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
            executor.supply(() -> "pending");

            CompletableFuture<String> delayed = executor.supplyAfter(Duration.ofMillis(1), () -> "too many");

            assertCompletesWith(delayed, AutoStopperExecutor.SaturationException.class, null);
        } finally {
            blocked.countDown();
            executor.shutdown();
        }
    }

    @Test
    public void testShutdownFailsPendingDelayedTasksAndRejectsNewOnes() {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        CompletableFuture<String> pending = executor.supplyAfter(Duration.ofSeconds(60), () -> "never");

        assertTrue(executor.shutdown(), "a pending delay must not hold up shutdown");

        assertCompletesWith(pending, AutoStopperExecutor.ShutdownException.class, null);
        assertCompletesWith(executor.supplyAfter(Duration.ofMillis(1), () -> "after-shutdown"),
                AutoStopperExecutor.ShutdownException.class, null);
    }

    @Test
    public void testInvalidConfigurationRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AutoStopperExecutor(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AutoStopperExecutor(1, 0));
    }

    @Test
    public void testShutdownHonorsHardDeadlineWhenTaskIgnoresInterrupts() throws InterruptedException {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.supply(() -> {
            started.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Model a dependency which does not cooperate with interruption.
                }
            }
            return null;
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));

        long start = System.nanoTime();
        boolean terminated = executor.shutdown(Duration.ofMillis(50));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        release.countDown();

        assertFalse(terminated);
        assertTrue(elapsedMillis < 1000, "shutdown exceeded its hard deadline: " + elapsedMillis + "ms");
    }

    @Test
    public void testShutdownRejectsInvalidDeadline() {
        AutoStopperExecutor executor = new AutoStopperExecutor(1, 1);
        try {
            assertThrows(IllegalArgumentException.class, () -> executor.shutdown(Duration.ZERO));
        } finally {
            executor.shutdown();
        }
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
