package dev.screenmine.spike;

import com.mojang.blaze3d.platform.InputConstants;
import dev.screenmine.Cfg;
import dev.screenmine.browser.BrowserInstance;
import dev.screenmine.gui.BrowserFullScreen;
import dev.screenmine.world.PlacedScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPIKE — validates SDL3 keyboard/mouse input reaching the embedded Chromium.
 *
 * <p>There is no ScreenMineClient here on purpose. That class is the mod
 * entrypoint and the largest block of remaining errors, but stubbing it out to
 * reach a green build would also gut everything the screen depends on. This
 * entrypoint does the minimum instead: one PlacedScreen with a browser, one key
 * to open it.
 *
 * <p>What this is meant to prove, in game:
 * <ul>
 *   <li>keypresses, modifiers and character input reach CEF correctly;</li>
 *   <li>mouse click, drag and scroll reach the page;</li>
 *   <li>pasting text works;</li>
 *   <li>a YouTube video plays and the volume control applies.</li>
 * </ul>
 *
 * <p>Key: B.
 */
public final class SpikeBrowserClient implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("ScreenMine/spike-browser");

    private KeyMapping open;
    private PlacedScreen screen;

    @Override
    public void onInitializeClient() {
        // GLFW is gone in 26.3 (SDL3 backend). InputConstants.KEY_B is an SDL
        // scancode, which is what a KeyMapping expects here.
        open = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.screenmine.spike.openbrowser",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_B,
                KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOG.info("spike-browser ready — press B to open the fullscreen browser");
    }

    private void tick(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        while (open.consumeClick()) {
            if (screen == null) {
                spawn(mc);
                if (screen != null) {
                    mc.gui.setScreen(new BrowserFullScreen(screen));
                }
            } else {
                screen.browser.close();
                screen = null;
                mc.gui.setScreen(null);
            }
        }
    }

    private void spawn(Minecraft mc) {
        Entity p = mc.player;
        Vec3 eye = p.getEyePosition();
        Vec3 pos = eye.add(p.getLookAngle().scale(Cfg.SPAWN_DISTANCE));

        BrowserInstance browser = new BrowserInstance("spike", Cfg.DEFAULT_URL);
        screen = new PlacedScreen("spike", browser, "minecraft:overworld", pos,
                p.getYRot() + 180.0f);
        LOG.info("browser placed at {}", pos);
    }
}