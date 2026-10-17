package dev.screenmine.mp;

import dev.screenmine.Cfg;
import dev.screenmine.Lang;
import dev.screenmine.Settings;
import dev.screenmine.world.PlacedScreen;
import dev.screenmine.world.ScreenWorld;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's half: make, move and unmake shared screens.
 *
 * <p>Everything the server checks, this checks again, for one reason: a server
 * can be wrong, compromised, or simply a different build. The owner also needs
 * to be able to see their own screen somewhere, and nobody else's.
 *
 * <p>Off by default. {@code mp.acceptRemote} has to be turned on deliberately,
 * because "show pages other people chose on this computer" is not something a
 * client should start doing because a server asked nicely.
 */
public final class SharedScreensClient {

    private static final Logger LOG = LoggerFactory.getLogger("ScreenMine/mp");

    private SharedScreensClient() {
    }

    public static boolean acceptRemote() {
        return Settings.getBool("mp.acceptRemote", false);
    }

    /**
     * Asks the server for a shared screen at the place this player is looking.
     *
     * <p>Nothing is created locally. The screen appears when the server answers
     * with ScreenAdded, which it sends to the placer too - so the person who
     * placed it and everybody watching get the identical screen from the
     * identical code path, with the server's absolute position. Making one
     * locally and hoping the two agree is how a client ends up showing a screen
     * the server never agreed to.
     *
     * <p>That is also why the owner needs {@code mp.acceptRemote}: their own
     * screen arrives through the same receiver as everybody else's. The flag is
     * about what this client will render, not about who sent it.
     *
     * @return true when the request went out; false with a chat line saying why
     */
    public static boolean place(net.minecraft.client.Minecraft mc, String url) {
        if (!acceptRemote()) {
            say(mc, Lang.t("chat.share.off"));
            return false;
        }
        if (mc.player == null || mc.level == null) {
            return false;
        }
        // Where the player is looking, in blocks, so the screen lands where they
        // are pointing rather than at their feet.
        var eye = mc.player.getEyePosition();
        var look = mc.player.getLookAngle();
        var pos = eye.add(look.scale(Cfg.SPAWN_DISTANCE));
        float yaw = mc.player.getYRot() + 180.0f;
        String dimension = mc.level.dimension().identifier().toString();

        var payload = new Payloads.Place(url == null ? "" : url.trim(), dimension,
                pos.x, pos.y, pos.z, yaw, Cfg.DEFAULT_WIDTH, (float) (Cfg.DEFAULT_WIDTH * 9.0 / 16.0));
        ClientPlayNetworking.send(payload);
        LOG.info("asked to place a shared screen on {}", url);
        return true;
    }

    /**
     * Tells the server this player moved one of their shared screens.
     *
     * <p>Called from the local move path. Without it the owner's screen moves
     * for the owner and nobody else: two people in front of the same screen see
     * it in different places, and the person who placed it cannot tell whether
     * anybody else can still see it. The local move still happens immediately -
     * waiting for the server would make dragging feel broken - and the server
     * decides whether to believe it.
     */
    public static void moved(net.minecraft.client.Minecraft mc, java.util.UUID id,
                             double x, double y, double z, float yaw) {
        if (!acceptRemote() || !OWNED_BY_NAME.containsKey(id)) {
            return;
        }
        ClientPlayNetworking.send(new Payloads.Move(id, x, y, z, yaw));
    }

    /** Takes down every shared screen this player owns. */
    public static void removeMine(net.minecraft.client.Minecraft mc) {
        for (java.util.UUID id : mineOwned()) {
            ClientPlayNetworking.send(new Payloads.Remove(id));
            LOG.info("asked to remove shared screen {}", id);
        }
        if (mineOwned().isEmpty()) {
            say(mc, Lang.t("chat.share.none"));
        }
    }

    /** Whether this player owns any shared screen right now. */
    public static boolean hasMine(net.minecraft.client.Minecraft mc) {
        return !mineOwned().isEmpty();
    }

    private static java.util.List<java.util.UUID> mineOwned() {
        java.util.List<java.util.UUID> out = new java.util.ArrayList<>();
        String me = meName();
        if (me == null) {
            return out;
        }
        for (java.util.Map.Entry<java.util.UUID, String> e : OWNED_BY_NAME.entrySet()) {
            if (me.equals(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private static String meName() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc.getConnection() == null ? null : mc.getConnection().getLocalGameProfile().name();
    }

    /** Screen id to owner name, for "remove all of mine". */
    private static final java.util.Map<java.util.UUID, String> OWNED_BY_NAME =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void say(net.minecraft.client.Minecraft mc, String message) {
        dev.screenmine.ScreenMineClient.chat(
                net.minecraft.network.chat.Component.literal(message));
    }

/**
     * Ticks for the owner of a shared screen: reports where its video is.
     *
     * <p>Called once per client tick from the mod's tick. Every screen this
     * player owns and that actually has a video is reported every
     * {@code PlaybackSync.REPORT_INTERVAL_MILLIS}; pages without one are skipped,
     * because a page with no video element has no position to agree on - a
     * YouTube home feed is exactly that case, and there is no honest answer to
     * "where are you" about a feed that decides for itself.
     *
     * <p>Unconditional, paused or not. A paused owner keeps reporting so that
     * somebody who joins five minutes later lands at the right frame instead of
     * at zero.
     */
    public static void tickSync() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || !acceptRemote()) {
            return;
        }
        if (++syncTicks < SYNC_TICKS) {
            return;
        }
        syncTicks = 0;
        for (java.util.UUID id : mineOwned()) {
            PlacedScreen screen = WORLD == null ? null : WORLD.get(id.toString());
            if (screen == null || !screen.browser.hasVideo()) {
                continue;
            }
            ClientPlayNetworking.send(new Payloads.Playback(
                    id, screen.browser.playbackTime(), screen.browser.playbackPaused()));
        }
    }

    /**
     * The world, set by {@link #register}. Kept so the tick can reach the
     * screens without being handed one on every tick.
     */
    private static ScreenWorld WORLD;

    /**
     * Ticks between reports; 40 ticks is two seconds.
     *
     * <p>A tick counter and not a clock, on purpose: no wall time is needed and
     * it lines up with the browser updates, which also arrive on the tick.
     */
    private static final int SYNC_TICKS =
            (int) (PlaybackSync.REPORT_INTERVAL_MILLIS / 50L);

    private static int syncTicks;
    /** Registers the three server-to-client receivers. */
    public static void register(ScreenWorld world) {
        WORLD = world;
        ClientPlayNetworking.registerGlobalReceiver(Payloads.ScreenAdded.TYPE, (added, context) -> {
            if (!acceptRemote()) {
                LOG.info("remote screen {} ignored: mp.acceptRemote is off",
                        added.screenId());
                return;
            }
            // The server already vetted this. Checking again costs nothing and
            // is the only thing standing between a compromised server and this
            // machine opening whatever it likes.
            ScreenRules.Rejection why = ScreenRules.checkUrl(
                    added.url(), ScreenRules.DEFAULT_ALLOWED_HOSTS);
            if (why != null) {
                LOG.warn("remote screen {} refused: {}", added.screenId(), why);
                return;
            }
            OWNED_BY_NAME.put(added.screenId(), added.owner());
            PlacedScreen screen = world.attachRemote(
                    added.screenId().toString(), added.url(), added.dimension(),
                    added.x(), added.y(), added.z(), added.yaw(), added.width());
            if (screen != null) {
                LOG.info("remote screen {} from {} on {} at {},{},{} {}x{}",
                        added.screenId(), added.owner(), added.dimension(),
                        added.x(), added.y(), added.z(),
                        added.width(), added.height());
            } else {
                LOG.info("remote screen {} from {} is in another dimension ({})",
                        added.screenId(), added.owner(), added.dimension());
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(Payloads.ScreenRemoved.TYPE, (removed, context) -> {
            world.detach(removed.screenId().toString());
            OWNED_BY_NAME.remove(removed.screenId());
            LOG.info("remote screen {} removed", removed.screenId());
        });

        ClientPlayNetworking.registerGlobalReceiver(Payloads.ScreenNavigate.TYPE, (nav, context) -> {
            if (!acceptRemote()) {
                return;
            }
            ScreenRules.Rejection why = ScreenRules.checkUrl(
                    nav.url(), ScreenRules.DEFAULT_ALLOWED_HOSTS);
            if (why != null) {
                LOG.warn("remote navigate to {} refused: {}", nav.url(), why);
                return;
            }
            PlacedScreen screen = world.get(nav.screenId().toString());
            if (screen != null) {
                screen.browser.navigate(nav.url(), true);
                LOG.info("remote screen {} now {}", nav.screenId(), nav.url());
            }
        });

        // Move. Goes through the same path as arrival, because attachRemote
        // already knows what to do with a screen that is in the right place and
        // has been told to be somewhere else.
        ClientPlayNetworking.registerGlobalReceiver(Payloads.ScreenMove.TYPE, (mv, context) -> {
            if (!acceptRemote()) {
                return;
            }
            PlacedScreen screen = world.get(mv.screenId().toString());
            if (screen == null) {
                // Not built here, so nothing to move. attachRemote will place it
                // correctly when this player walks over there.
                return;
            }
            world.attachRemote(mv.screenId().toString(), screen.browser.url(),
                    screen.dimension, mv.x(), mv.y(), mv.z(), mv.yaw(), screen.width);
            LOG.info("remote screen {} moved to {},{},{}", mv.screenId(),
                    mv.x(), mv.y(), mv.z());
        });

        // Playback from the owner. Pulled towards them, never the other way round:
        // this is a viewer being told where the video is, and a viewer sending
        // one is refused by the server as not-your-screen.
        ClientPlayNetworking.registerGlobalReceiver(Payloads.ScreenPlayback.TYPE, (pb, context) -> {
            if (!acceptRemote()) {
                return;
            }
            // The owner is the source of these numbers. Applying one back to the
            // browser that produced it would seek to a value that is now stale by
            // the time the message lands - a loop with no fixed point.
            if (mineOwned().contains(pb.screenId())) {
                return;
            }
            // Checked again, for the same reason the URL is: a server can be
            // wrong. seekTo() writes the number straight into a script.
            if (PlaybackSync.checkTime(pb.time()) != null) {
                LOG.warn("playback for {} refused: time={}", pb.screenId(), pb.time());
                return;
            }
            PlacedScreen screen = world.get(pb.screenId().toString());
            if (screen == null) {
                return;
            }
            var browser = screen.browser;
            // No video element yet means nothing to seek or to pause. Seeking
            // anyway would restart the video at zero the moment it appears.
            if (!browser.hasVideo()) {
                return;
            }
            boolean moved = false;
            if (browser.playbackPaused() != pb.paused()) {
                browser.setPaused(pb.paused());
                moved = true;
            }
            if (PlaybackSync.needsSeek(pb.time(), browser.playbackTime())) {
                // seekTo is a one-shot: while the seek is in flight the browser
                // still reports the old position, so a second report arrives
                // before the first landed and would seek again. The next report
                // two seconds later finds them together and does nothing.
                browser.seekTo(pb.time());
                moved = true;
            }
            if (moved) {
                LOG.info("screen {} follows the owner to {} ({})",
                        pb.screenId(), pb.time(), pb.paused() ? "paused" : "playing");
            }
        });

        // A shared screen lives exactly as long as its owner. The server removes
        // them when the owner leaves; this is the belt to that braces, for the
        // case where the connection drops before the last message arrives.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOG.info("left the server; remote screens are gone with it");
        });
    }
}