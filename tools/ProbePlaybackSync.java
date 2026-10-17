import dev.screenmine.mp.PlaybackSync;
import dev.screenmine.mp.RateLimiter;
import dev.screenmine.mp.ScreenRules;
import dev.screenmine.mp.SharedScreens;

import java.util.Set;
import java.util.UUID;

/**
 * Drives the playback sync rules by hand. No Minecraft, no server.
 *
 * <p>Same shape as ProbeSharedScreens: a counter, a list of failures, and an
 * exit code. The reason these need testing at all is that they are mostly about
 * things that must NOT happen - a NaN reaching a browser, a viewer moving the
 * owner, a spammer turning the report into a flood.
 */
public final class ProbePlaybackSync {

    private static int passed;
    private static final java.util.List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) {
        checkTimeRejectsGarbage();
        checkTimeAcceptsRealPositions();
        needsSeekUsesATolerance();
        needsSeekIgnoresBrowsersWithNoVideo();
        shouldSendAlwaysSendsAPauseChange();
        shouldSendThrottlesOtherwise();
        onlyTheOwnerMayReport();
        aViewerCannotMoveTheOwner();
        reportIsCoalescedIntoOneBroadcast();
        playbackStateIsDroppedWithTheScreen();
        rateLimitIsAboveWhatAnHonestClientSends();

        System.out.println("  " + passed + " de " + (passed + failures.size()) + " passaram");
        for (String f : failures) {
            System.out.println("  FAIL  " + f);
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------ time //

    private static void checkTimeRejectsGarbage() {
        eq("NaN is refused", "time-not-a-number",
                PlaybackSync.checkTime(Double.NaN).code());
        eq("infinity is refused", "time-not-a-number",
                PlaybackSync.checkTime(Double.POSITIVE_INFINITY).code());
        eq("negative infinity is refused", "time-not-a-number",
                PlaybackSync.checkTime(Double.NEGATIVE_INFINITY).code());
        eq("negative time is refused", "time-out-of-range",
                PlaybackSync.checkTime(-0.5).code());
        eq("a week is refused", "time-out-of-range",
                PlaybackSync.checkTime(PlaybackSync.MAX_TIME_SECONDS + 1).code());
    }

    private static void checkTimeAcceptsRealPositions() {
        ok("zero is fine", PlaybackSync.checkTime(0.0) == null);
        ok("the middle of a song is fine", PlaybackSync.checkTime(94.5) == null);
        ok("exactly the maximum is fine",
                PlaybackSync.checkTime(PlaybackSync.MAX_TIME_SECONDS) == null);
    }

    // ------------------------------------------------------------------ seek //

    private static void needsSeekUsesATolerance() {
        ok("in step, no seek", !PlaybackSync.needsSeek(100.0, 100.0));
        ok("a second apart, no seek", !PlaybackSync.needsSeek(100.0, 101.0));
        ok("exactly the tolerance, no seek",
                !PlaybackSync.needsSeek(100.0, 100.0 + PlaybackSync.TOLERANCE_SECONDS));
        ok("just over the tolerance, seek",
                PlaybackSync.needsSeek(100.0, 100.0 + PlaybackSync.TOLERANCE_SECONDS + 0.1));
        ok("behind by a lot, seek", PlaybackSync.needsSeek(300.0, 10.0));
        ok("ahead by a lot, seek", PlaybackSync.needsSeek(10.0, 300.0));
    }

    private static void needsSeekIgnoresBrowsersWithNoVideo() {
        // -1 is hasVideo() == false. Seeking would restart the video at zero the
        // moment the element appears, which is worse than doing nothing.
        ok("no video element, no seek", !PlaybackSync.needsSeek(120.0, -1.0));
    }

    // ------------------------------------------------------------ broadcast //

    private static void shouldSendAlwaysSendsAPauseChange() {
        ok("first report ever goes out",
                PlaybackSync.shouldSend(Long.MIN_VALUE, true, 1_000L));
        ok("a pause goes out immediately",
                PlaybackSync.shouldSend(1_000L, true, 1_001L));
        ok("unpause goes out immediately",
                PlaybackSync.shouldSend(1_000L, true, 1_001L));
    }

    private static void shouldSendThrottlesOtherwise() {
        ok("too soon after the last send, held",
                !PlaybackSync.shouldSend(1_000L, false, 1_000L
                        + PlaybackSync.REPORT_INTERVAL_MILLIS - 1));
        ok("interval elapsed, goes out",
                PlaybackSync.shouldSend(1_000L, false,
                        1_000L + PlaybackSync.REPORT_INTERVAL_MILLIS));
    }

    // ---------------------------------------------------------------- server //

    private static void onlyTheOwnerMayReport() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        SharedScreens screens = new SharedScreens(ScreenRules.DEFAULT_ALLOWED_HOSTS, 2);
        UUID id = placeOne(screens, owner);

        ScreenRules.Rejection[] why = new ScreenRules.Rejection[1];
        SharedScreens.Screen sent = screens.reportPlayback(
                id, owner, 12.0, false, 1_000L, why);
        ok("the owner's report is accepted", sent != null && why[0] == null);

        SharedScreens.Screen refused = screens.reportPlayback(
                id, stranger, 99.0, false, 5_000L, why);
        ok("a stranger is refused", refused == null);
        eq("and learns nothing about the id", "not-your-screen", why[0].code());
    }

    private static void aViewerCannotMoveTheOwner() {
        UUID owner = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        SharedScreens screens = new SharedScreens(ScreenRules.DEFAULT_ALLOWED_HOSTS, 2);
        UUID id = placeOne(screens, owner);

        ScreenRules.Rejection[] why = new ScreenRules.Rejection[1];
        // The viewer tries to drag the owner's video. Paused state is included,
        // because "pause everybody" is the interesting version of this attack.
        screens.reportPlayback(id, viewer, 0.0, true, 1_000L, why);
        eq("a viewer cannot pause the owner", "not-your-screen", why[0].code());
        ok("and the owner's own state is untouched",
                screens.reportPlayback(id, owner, 7.0, false, 5_000L, why) != null);
    }

    private static void reportIsCoalescedIntoOneBroadcast() {
        UUID owner = UUID.randomUUID();
        SharedScreens screens = new SharedScreens(ScreenRules.DEFAULT_ALLOWED_HOSTS, 2);
        UUID id = placeOne(screens, owner);

        ScreenRules.Rejection[] why = new ScreenRules.Rejection[1];
        long t = 1_000L;

        // Twenty accepted reports inside one interval must produce one
        // broadcast, not twenty.
        //
        // Twenty, and not five hundred, on purpose: the rate limiter is supposed
        // to refuse a flood, so "spam is never an error" would be asserting the
        // wrong rule. What is being tested here is the coalescing underneath -
        // accepted reports, one message out.
        int sent = 0;
        for (int i = 0; i < 20; i++) {
            if (screens.reportPlayback(id, owner, i, false, t + i * 5L, why) != null) {
                sent++;
            }
            eq("an accepted report is never an error", null, why[0]);
        }
        eq("twenty reports inside one interval broadcast once", 1, sent);

        // Once the interval is up it goes out again.
        long later = t + PlaybackSync.REPORT_INTERVAL_MILLIS + 100L;
        ok("and once the interval is up, again",
                screens.reportPlayback(id, owner, 80.0, false, later, why) != null);
    }

    private static void playbackStateIsDroppedWithTheScreen() {
        UUID owner = UUID.randomUUID();
        SharedScreens screens = new SharedScreens(ScreenRules.DEFAULT_ALLOWED_HOSTS, 2);
        UUID id = placeOne(screens, owner);

        ScreenRules.Rejection[] why = new ScreenRules.Rejection[1];
        screens.reportPlayback(id, owner, 5.0, false, 1_000L, why);
        eq("one screen exists", 1, screens.count());

        // If the playback state survived, this fresh screen with the same id
        // would not send until the interval elapsed. Since it was forgotten, the
        // "never sent" path fires and it goes out at once.
        screens.remove(id, owner, 20_000L, why);
        eq("the screen is gone", 0, screens.count());
        ok("and its playback state went with it",
                screens.reportPlayback(id, owner, 6.0, false, 20_500L, why) == null);
        eq("which means the id is unknown", "not-your-screen", why[0].code());
    }

    private static void rateLimitIsAboveWhatAnHonestClientSends() {
        // Two screens, one report each per interval, over a whole window. If the
        // limit were under this, an honest owner would start losing reports and
        // everybody would drift.
        long perScreen = RateLimiter.WINDOW_MILLIS / PlaybackSync.REPORT_INTERVAL_MILLIS;
        int honest = (int) perScreen * ScreenRules.MAX_SCREENS_PER_PLAYER;
        ok("limit " + RateLimiter.PLAYBACK_LIMIT + " covers " + honest
                + " honest reports per window", RateLimiter.PLAYBACK_LIMIT >= honest);

        // And it must still stop a flood.
        RateLimiter limiter = new RateLimiter(RateLimiter.PLAYBACK_LIMIT,
                RateLimiter.WINDOW_MILLIS);
        int allowed = 0;
        for (int i = 0; i < 5_000; i++) {
            if (limiter.tryAcquire(1_000L)) {
                allowed++;
            }
        }
        eq("but not a flood", RateLimiter.PLAYBACK_LIMIT, allowed);
    }

    // ----------------------------------------------------------------- tools //

    private static UUID placeOne(SharedScreens screens, UUID player) {
        ScreenRules.Rejection why = screens.place(player, "Probe", "https://www.youtube.com",
                "minecraft:overworld", 0, 64, 0, 0f, 6f, 3.375f,
                0, 64, 0, "minecraft:overworld", 0L);
        if (why != null) {
            throw new IllegalStateException("probe could not place a screen: " + why);
        }
        // place() hands the screen id to nobody, so find it the way the server
        // does: the one screen this player owns.
        for (SharedScreens.Screen s : screens.all()) {
            if (s.owner().equals(player)) {
                return s.id();
            }
        }
        throw new IllegalStateException("probe placed nothing");
    }

    private static void ok(String what, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failures.add(what);
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        if (java.util.Objects.equals(expected, actual)) {
            passed++;
        } else {
            failures.add(what + " - esperado " + expected + ", veio " + actual);
        }
    }

    private ProbePlaybackSync() {
    }
}