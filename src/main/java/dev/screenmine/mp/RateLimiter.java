package dev.screenmine.mp;

/**
 * How often one player may send each kind of message.
 *
 * <p>Pure, so the probe can drive a clock by hand instead of waiting.
 *
 * <p>Why per-player and per-kind: a player who places two screens and moves
 * them is normal, and a player sending a thousand navigates a second is not.
 * One global counter would either break the first or allow the second, so each
 * kind gets its own bucket and each player gets their own set of buckets.
 */
public final class RateLimiter {

    /** How many messages of one kind a player may send in the window. */
    public static final int PLACE_LIMIT = 2;
    public static final int NAVIGATE_LIMIT = 10;
    public static final int REMOVE_LIMIT = 10;

    /** The window, in milliseconds. */
    public static final long WINDOW_MILLIS = 10_000L;

    private final int limit;
    private final long windowMillis;
    private long windowStart = Long.MIN_VALUE;
    private int used;

    public RateLimiter(int limit, long windowMillis) {
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    /**
     * Counts one message, if there is room for it.
     *
     * <p>The clock is a parameter so a test can move time deliberately instead
     * of sleeping, and so a caller with a server tick count can pass that
     * instead of a wall clock. {@code nowMillis} is used, never a call to
     * {@code System.currentTimeMillis()} inside: that would make the rule
     * impossible to test and impossible to replay.
     *
     * @return true when the message is allowed
     */
    public synchronized boolean tryAcquire(long nowMillis) {
        // Long.MIN_VALUE means no window has started. The subtraction would
        // overflow, so start one explicitly rather than guarding the compare.
        if (windowStart == Long.MIN_VALUE || nowMillis - windowStart >= windowMillis) {
            windowStart = nowMillis;
            used = 0;
        }
        if (used >= limit) {
            return false;
        }
        used++;
        return true;
    }

    /** Whether a message would be allowed, without counting it. */
    public synchronized boolean wouldAllow() {
        return used < limit;
    }

    /** How many of this kind of message have been counted in the current window. */
    public synchronized int used() {
        return used;
    }
}