package dev.screenmine.sync;

import dev.screenmine.Cfg;

/**
 * Follows a server-authoritative playback clock.
 *
 * <p>The server owns the position: it sends "at the moment this packet left me,
 * the video stood at P, and it is (not) paused". Nobody's browser is the truth —
 * that is what used to make playback fight itself, because the owner's reported
 * position was always a few hundred milliseconds stale by the time it arrived.
 *
 * <p>The target is projected forward from the moment the packet was received
 * locally, so the two machines never need their wall clocks to agree.
 *
 * <p>Corrections are deliberately lopsided. Being a little behind is normal and
 * self-correcting, so the tolerance there is generous; being ahead means we ran
 * past everyone and can only be fixed by jumping back, so that tolerance is
 * tighter. A forward catch-up seeks slightly past the target, because the seek
 * itself costs time and would otherwise land us behind again. After any seek a
 * cooldown blocks the next one, or a cold join turns into an endless stutter.
 */
public final class PlaybackSync {

    /** Position the server reported, in seconds. */
    private double anchorPos;
    /** Local nanoTime when that report arrived. */
    private long anchorNanos;
    private boolean paused;
    private boolean known;

    /** nanoTime of the last corrective seek; 0 means "never seeked yet". */
    private long lastSeekNanos;

    /** Accepts a fresh timeline from the server. */
    public void onTimeline(double positionSeconds, boolean paused) {
        this.anchorPos = Math.max(0.0, positionSeconds);
        this.anchorNanos = System.nanoTime();
        this.paused = paused;
        this.known = true;
    }

    public boolean known() {
        return known;
    }

    public boolean paused() {
        return paused;
    }

    /** Where the video should stand right now, in seconds. */
    public double target() {
        if (!known) {
            return -1.0;
        }
        if (paused) {
            return anchorPos;
        }
        return anchorPos + (System.nanoTime() - anchorNanos) / 1_000_000_000.0;
    }

    /** Forgets everything; used when the screen loads a different video. */
    public void reset() {
        known = false;
        lastSeekNanos = 0L;
    }

    /**
     * Decides where to seek, or returns a negative number to leave playback alone.
     *
     * @param localSeconds the position the page actually reports
     */
    public double correction(double localSeconds) {
        if (!known || localSeconds < 0.0) {
            return -1.0;
        }
        double target = target();
        double drift = target - localSeconds;   // > 0: we are behind

        boolean firstEver = lastSeekNanos == 0L;
        if (!firstEver) {
            long sinceMs = (System.nanoTime() - lastSeekNanos) / 1_000_000L;
            if (sinceMs < Cfg.SYNC_SEEK_COOLDOWN_MS) {
                return -1.0;
            }
        }

        boolean needsSeek = drift > Cfg.SYNC_BEHIND_TOLERANCE
                            || -drift > Cfg.SYNC_AHEAD_TOLERANCE;
        if (!needsSeek) {
            return -1.0;
        }

        // catching up forward: overshoot by the cost of the seek itself
        double seekTo = (drift > 0.0 && !paused) ? target + Cfg.SYNC_SEEK_LEAD : target;
        lastSeekNanos = System.nanoTime();
        return Math.max(0.0, seekTo);
    }
}
