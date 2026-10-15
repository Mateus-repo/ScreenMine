package dev.screenmine.spike;

import com.mojang.blaze3d.platform.InputConstants;
import dev.screenmine.Cfg;
import dev.screenmine.browser.BrowserInstance;
import dev.screenmine.gui.BrowserFullScreen;
import dev.screenmine.world.PlacedScreen;
import net.dimaskama.mcef.api.MCEFApi;
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
 * <p>Key: B.
 */
public final class SpikeBrowserClient implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("ScreenMine/spike-browser");

    /**
     * A data URL on purpose. YouTube is a heavy React app and swallows or
     * reshapes keys in ways a plain test page does not, so it is the wrong place
     * to decide whether Enter works. This page reports every keydown and click
     * into a visible div, so one run answers keydown, Enter, click and typing at
     * once.
     *
     * <p>Everything is single-quoted so it needs no escaping in the Java literal.
     */
    private static final String TEST_URL =
            "data:text/html,<body style='background:%23111;color:%23eee;font-family:sans-serif'>"
            + "<input id=i style='font-size:48px;width:90%25' placeholder='type here'>"
            + "<div id=o style='font-size:32px;margin-top:40px;color:%230f0'>waiting...</div>"
            + "<script>"
            + "var i=document.getElementById('i'),o=document.getElementById('o');"
            + "i.addEventListener('keydown',function(e){"
            + "o.style.color='#0f0';o.textContent='KEYDOWN '+e.key+' value='+i.value;});"
            + "i.addEventListener('click',function(){"
            + "o.style.color='#ff0';o.textContent='CLICK OK';});"
            + "</script></body>";

    private KeyMapping open;
    private PlacedScreen screen;
    private MCEFApi.Initialization init;
    private String lastStage;

    @Override
    public void onInitializeClient() {
        // GLFW is gone in 26.3 (SDL3 backend). InputConstants.KEY_B is an SDL
        // scancode, which is what a KeyMapping expects here.
        open = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.screenmine.spike.openbrowser",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_B,
                KeyMapping.Category.MISC));

        // Start Chromium now, not when the overlay opens. The first launch
        // downloads ~200 MB and takes tens of seconds.
        init = MCEFApi.initialize();
        LOG.info("MCEF initialisation started, stage={}", init.getStage());

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOG.info("spike-browser ready — press B to open the fullscreen browser");
    }

    private void tick(Minecraft mc) {
        reportProgress();

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
                // Deliberately NOT closing the browser. In the real mod the world
                // screen outlives the overlay, so reopening must not reload the
                // page — closing it here made every reopen start from scratch.
                screen.browser.pause();
                mc.gui.setScreen(null);
                LOG.info("overlay closed, browser kept alive");
            }
        }
    }

    private void reportProgress() {
        if (init == null || init.isDone()) {
            return;
        }
        String stage = init.getStage() + " " + Math.round(init.getPercentage()) + "%";
        if (!stage.equals(lastStage)) {
            lastStage = stage;
            LOG.info("MCEF progress: {}", stage);
        }
    }

    /** True once Chromium is up and a browser can actually be created. */
    public static boolean browserReady() {
        return MCEFApi.initialize().isDone();
    }

    private void spawn(Minecraft mc) {
        Entity p = mc.player;
        Vec3 eye = p.getEyePosition();
        Vec3 pos = eye.add(p.getLookAngle().scale(Cfg.SPAWN_DISTANCE));

        BrowserInstance browser = new BrowserInstance("spike", TEST_URL);
        screen = new PlacedScreen("spike", browser, "minecraft:overworld", pos,
                p.getYRot() + 180.0f);
        LOG.info("browser placed at {} (MCEF ready={})", pos, browserReady());
    }
}