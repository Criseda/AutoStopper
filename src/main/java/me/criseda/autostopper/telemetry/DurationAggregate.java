package me.criseda.autostopper.telemetry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe bounded duration accumulator tracking count, sum, minimum, and maximum elapsed nanoseconds.
 */
public final class DurationAggregate {
    private final LongAdder count = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxNanos = new AtomicLong(0L);

    public DurationAggregate() {
    }

    DurationAggregate(long count, long totalNanos, long minNanos, long maxNanos) {
        this.count.add(count);
        this.totalNanos.add(totalNanos);
        this.minNanos.set(minNanos);
        this.maxNanos.set(maxNanos);
    }

    public void record(long nanos) {
        if (nanos < 0) {
            nanos = 0;
        }
        count.increment();
        totalNanos.add(nanos);
        final long n = nanos;
        minNanos.accumulateAndGet(n, Math::min);
        maxNanos.accumulateAndGet(n, Math::max);
    }

    public long count() {
        return count.sum();
    }

    public long totalNanos() {
        return totalNanos.sum();
    }

    public long minNanos() {
        long current = minNanos.get();
        return current == Long.MAX_VALUE ? 0L : current;
    }

    public long maxNanos() {
        return maxNanos.get();
    }

    public long averageNanos() {
        long c = count();
        return c == 0 ? 0L : totalNanos() / c;
    }

    public Duration totalDuration() {
        return Duration.ofNanos(totalNanos());
    }

    public Duration minDuration() {
        return Duration.ofNanos(minNanos());
    }

    public Duration maxDuration() {
        return Duration.ofNanos(maxNanos());
    }

    public Duration averageDuration() {
        return Duration.ofNanos(averageNanos());
    }

    public DurationAggregate snapshot() {
        return new DurationAggregate(count(), totalNanos(), minNanos.get(), maxNanos.get());
    }
}
