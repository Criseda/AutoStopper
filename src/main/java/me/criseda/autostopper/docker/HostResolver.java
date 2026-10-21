package me.criseda.autostopper.docker;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Resolves a host name within a deadline. Name lookups are blocking I/O and must stay bounded. */
@FunctionalInterface
interface HostResolver {
    InetAddress resolve(String host, Duration timeout)
            throws UnknownHostException, TimeoutException, InterruptedException;

    /**
     * The JVM resolver, run on a virtual thread so the caller can stop waiting at its deadline. A
     * lookup still in progress at the deadline is abandoned; it cannot hold the caller.
     */
    static HostResolver system() {
        return (host, timeout) -> {
            FutureTask<InetAddress> lookup = new FutureTask<>(() -> InetAddress.getByName(host));
            Thread.ofVirtual().name("autostopper-docker-lookup").start(lookup);
            try {
                return lookup.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof UnknownHostException unknown) {
                    throw unknown;
                }
                UnknownHostException failure = new UnknownHostException(host);
                failure.initCause(e.getCause());
                throw failure;
            } catch (TimeoutException | InterruptedException e) {
                lookup.cancel(true);
                throw e;
            }
        };
    }
}
