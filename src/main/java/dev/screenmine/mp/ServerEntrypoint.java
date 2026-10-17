package dev.screenmine.mp;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server half of the mod.
 *
 * <p>This is the first code the dedicated server runs from us, so it is the
 * first thing that can stop a server starting. It therefore does two things and
 * no more: register the payload types, and say so in the log.
 *
 * <p>What it deliberately does not touch, and why that matters. Nothing here
 * reaches {@code ScreenMineClient}, the browser, the renderer or the keyboard.
 * Every one of those is either a client-only class or an entry into CEF, and
 * loading either on a dedicated server takes it down. The 2026-10-17 01:43
 * dedicated server started with the mod in {@code environment: "*"} because
 * there was no entrypoint yet; this is the first one, and the same run has to
 * pass again after it lands.
 *
 * <p>The entrypoint key is {@code main}, not {@code server}: BlueMap, a mod
 * known to load on both, uses {@code main}, checked in its own
 * {@code fabric.mod.json} rather than from memory.
 */
public final class ServerEntrypoint implements ModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("ScreenMine/mp");

    @Override
    public void onInitialize() {
        Payloads.registerAll();
        ScreenNetwork.register();
        LOG.info("ScreenMine multiplayer: payload types registered on a {} side; "
                        + "shared screens on, hosts={}, max {} per player",
                net.fabricmc.api.EnvType.SERVER == net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getEnvironmentType() ? "server" : "client",
                ScreenNetwork.hosts(),
                ScreenRules.MAX_SCREENS_PER_PLAYER);
    }
}