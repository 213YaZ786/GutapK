package io.gutapk.offline;

/*
 * A shared latch that can be acquired a limited number of times, after which every request waits in a FIFO queue until
 * a share is returned. The game's loopback listeners count their open clients with it so that a flood of connections
 * from any app on the phone cannot start threads without end. Ported from
 * https://github.com/apache/tomcat/blob/main/java/org/apache/tomcat/util/threads/LimitLatch.java#LimitLatch
 *
 * Not ported: log, sm (the trace and debug lines and the StringManager message bundle: the one message is written here)
 * Not ported: @Serial (java.io.Serial needs Java 14, the serialVersionUID stays)
 *
 * Copyright (c) The Apache Software Foundation, Apache License 2.0.
 */

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

public class LimitLatch {

    private class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 1L;

        Sync() {
        }

        @Override
        protected int tryAcquireShared(int ignored) {
            long newCount = count.incrementAndGet();
            if (!released && newCount > limit) {
                // Limit exceeded
                count.decrementAndGet();
                return -1;
            } else {
                return 1;
            }
        }

        @Override
        protected boolean tryReleaseShared(int arg) {
            // An argument of zero is used for releaseAll() which must not change the acquisition count.
            if (arg != 0) {
                count.decrementAndGet();
            }
            return true;
        }
    }

    private final Sync sync;
    private final AtomicLong count;
    private volatile long limit;
    private volatile boolean released = false;

    /**
     * Instantiates a LimitLatch object with an initial limit.
     *
     * @param limit - maximum number of concurrent acquisitions of this latch
     */
    public LimitLatch(long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("Bad limit: " + String.valueOf(limit));
        }
        this.limit = limit;
        this.count = new AtomicLong(0);
        this.sync = new Sync();
    }

    /**
     * Returns the current count for the latch
     *
     * @return the current count for latch
     */
    public long getCount() {
        return count.get();
    }

    /**
     * Obtain the current limit.
     *
     * @return the limit
     */
    public long getLimit() {
        return limit;
    }

    /**
     * Sets a new limit. If the limit is decreased there may be a period where more shares of the latch are acquired
     * than the limit. In this case no more shares of the latch will be issued until sufficient shares have been
     * returned to reduce the number of acquired shares of the latch to below the new limit. If the limit is increased,
     * threads currently in the queue may not be issued one of the newly available shares until the next request is made
     * for a latch.
     *
     * @param limit The new limit
     */
    public void setLimit(long limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("Bad limit: " + String.valueOf(limit));
        }
        this.limit = limit;
    }

    /**
     * Acquires a shared latch if one is available or waits for one if no shared latch is currently available.
     *
     * @throws InterruptedException If the current thread is interrupted
     */
    public void countUpOrAwait() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }

    /**
     * Releases a shared latch, making it available for another thread to use.
     *
     * @return the counter value after the decrement
     */
    public long countDown() {
        sync.releaseShared(1);
        long result = getCount();
        return result;
    }

    /**
     * Releases all waiting threads and causes the limit to be ignored until reset() is called. This method does not
     * change the acquisition counter. Threads that this method releases are counted as holding a share and each must
     * still call countDown() once it has finished, as with any other acquisition.
     *
     * @return true if release was done
     */
    public boolean releaseAll() {
        released = true;
        return sync.releaseShared(0);
    }

    /**
     * Resets the latch and initializes the shared acquisition counter to zero. Any shares currently held are not
     * accounted for. When the holders of those shares later call countDown(), the counter goes negative which
     * effectively allows that many additional acquisitions before the limit is reached again. Reset the latch only when
     * no shares are held, or accept that the limit is not reliably enforced until the counter has returned to zero.
     */
    public void reset() {
        this.count.set(0);
        released = false;
    }

    /**
     * Returns true if there is at least one thread waiting to acquire the shared lock, otherwise returns false.
     *
     * @return true if threads are waiting
     */
    public boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
     * Provide access to the list of threads waiting to acquire this limited shared latch.
     *
     * @return a collection of threads
     */
    public Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }
}
