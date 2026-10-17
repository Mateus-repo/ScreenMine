package dev.screenmine.mp;

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

    /** Registers the three server-to-client receivers. */
    public static void register(ScreenWorld world) {
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

        // A shared screen lives exactly as long as its owner. The server removes
        // them when the owner leaves; this is the belt to that braces, for the
        // case where the connection drops before the last message arrives.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOG.info("left the server; remote screens are gone with it");
        });
    }
}