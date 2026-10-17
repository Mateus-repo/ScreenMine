package dev.screenmine.mp;

/**
 * When two browsers showing the same page should be pulled back together.
 *
 * <p>One browser per client, which is the architecture the Windows and Linux
 * servers both work under: the server runs no Chromium and only relays small
 * messages. The price is that each browser is its own session - its own cookies,
 * its own history, its own idea of what to play next - so two people watching
 * "together" drift apart. YouTube is the worst case because it personalises.
 *
 * <p>This class is the rule for undoing that drift, and nothing else. No
 * Minecraft types, no networking, so {@code tools/ProbePlaybackSync.java} can
 * drive it by hand.
 *
 * <h2>Why a tolerance and not exact agreement</h2>
 *
 * <p>The two browsers cannot be in step, and pretending otherwise produces a
 * stutter: the receiver is never exactly where the sender was, so it seeks, the
 * next report arrives, the gap is over the line again, and the video jumps every
 * two seconds. {@link #TOLERANCE_SECONDS} is the point where the difference is
 * no longer "a moment apart" and becomes "we are watching different things".
 */
public final class PlaybackSync {

    /**
     * How far apart two players may be, in seconds, before one is dragged to the
     * other.
     *
     * <p>Two seconds is about what a person notices on a video and about what
     * ordinary network lag produces, so a gap under it is never worth a seek and
     * a gap over it is always worth one.
     */
    public static final double TOLERANCE_SECONDS = 2.0;

    /**
     * How often the owner reports, in milliseconds.
     *
     * <p>Also the throttle on the server's broadcast, which is the number that
     * actually matters: every report is one message to every viewer.
     */
    public static final long REPORT_INTERVAL_MILLIS = 2_000L;

    /** A longer video than this is a mistake, and it bounds the number. */
    public static final double MAX_TIME_SECONDS = 86_400.0;

    /**
     * Whether a reported position is usable at all.
     *
     * <p>The NaN case is not pedantry. {@code time} arrives as a raw double in a
     * packet and is handed to {@code v.currentTime = ...} in the page, so a NaN
     * from a modified client becomes the literal text "NaN" in a script that
     * runs on every frame. Refusing it here means it never reaches a browser.
     */
    public static ScreenRules.Rejection checkTime(double time) {
        if (Double.isNaN(time) || Double.isInfinite(time)) {
            return new ScreenRules.Rejection("time-not-a-number", null);
        }
        if (time < 0.0 || time > MAX_TIME_SECONDS) {
            return new ScreenRules.Rejection("time-out-of-range",
                    "0 to " + (long) MAX_TIME_SECONDS + " seconds");
        }
        return null;
    }

    /**
     * Whether this viewer should be dragged to {@code theirs}.
     *
     * <p>{@code ours} below zero means "this page has no video element yet", and
     * a browser with no video has nothing to seek - asking anyway would restart
     * it at zero the moment the element appears.
     *
     * @param theirs the owner's position, already validated by {@link #checkTime}
     * @param ours   where this viewer's browser says it is, or -1 if unknown
     */
    public static boolean needsSeek(double theirs, double ours) {
        if (ours < 0.0) {
            return false;
        }
        return Math.abs(theirs - ours) > TOLERANCE_SECONDS;
    }

    /**
     * Whether the server should send this report on now.
     *
     * <p>A paused change always goes out - it is the one event a person is
     * waiting for. Otherwise the report waits for the interval, so a player who
     * spams the payload cannot turn it into a message flood: {@code lastSentAt}
     * only moves when something is actually sent.
     *
     * @param lastSentAt when this screen was last broadcast, or
     *                   {@link Long#MIN_VALUE} for never
     */
    public static boolean shouldSend(long lastSentAt, boolean pausedChanged, long nowMillis) {
        return pausedChanged || lastSentAt == Long.MIN_VALUE
                || nowMillis - lastSentAt >= REPORT_INTERVAL_MILLIS;
    }

    private PlaybackSync() {
    }
}