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
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPIKE — validates SDL3 keyboard/mouse input reaching the embedded Chromium.
 *
 * <p>Key: B. The binding lives under its own ScreenMine tab in
 * Options → Controls, so it can be rebound without hunting.
 */
public final class SpikeBrowserClient implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("ScreenMine/spike-browser");

    /** Own tab in Options → Controls instead of being buried in Misc. */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("screenmine", "main"));

    /**
     * A data URL on purpose. YouTube is a heavy React app that reshapes keys and
     * focus in ways a plain page does not, so it is the wrong place to decide
     * whether Enter works. This page reports input, keypress, click and scroll
     * into visible counters, so one run answers everything.
     *
     * <p>Everything is single-quoted so it needs no escaping in the Java literal.
     */
    private static final String TEST_URL =
            "data:text/html,<body style='background:%23111;color:%23eee;font-family:sans-serif;padding:20px'>"
            + "<div id=w style='font-size:20px;color:%2388f'>window ?</div>"
            + "<input id=i style='font-size:44px;width:95%25' placeholder='click here then type'>"
            + "<div style='font-size:26px;margin-top:30px'>"
            + "<div id=a style='color:%230f0'>keydown: -</div>"
            + "<div id=b style='color:%230ff'>input : -</div>"
            + "<div id=c style='color:%23ff0'>click : -</div>"
            + "<div id=d style='color:%23f0f'>scroll: -</div>"
            + "<div id=e style='color:%23ff0ff'>press : -</div>"
            + "</div>"
            + "<div id=z style='height:3000px;background:linear-gradient(%23111,%23008)'>"
            + "<span style='color:%23fff;font-size:40px'>scroll down here</span></div>"
            + "<script>"
            + "var i=document.getElementById('i'),z=document.getElementById('z'),"
            + "w=document.getElementById('w');"
            + "function s(){w.textContent='window '+innerWidth+'x'+innerHeight;}s();addEventListener('resize',s);"
            + "i.addEventListener('keydown',function(e){"
            + "document.getElementById('a').textContent='keydown: '+e.key+' ctrl='+e.ctrlKey+' shift='+e.shiftKey;});"
            + "i.addEventListener('input',function(){"
            + "document.getElementById('b').textContent='input : '+i.value;});"
            + "i.addEventListener('keypress',function(e){"
            + "document.getElementById('e').textContent='press : '+e.key;});"
            + "i.addEventListener('click',function(){"
            + "document.getElementById('c').textContent='click : ok';});"
            + "z.addEventListener('wheel',function(e){"
            + "document.getElementById('d').textContent='scroll: dy='+Math.round(e.deltaY)+' y='+Math.round(scrollY);});"
            + "</script></body>";

    private KeyMapping open;
    private PlacedScreen screen;
    /** Separate from {@link #screen}: the screen outlives the overlay. */
    private boolean overlayOpen;
    private MCEFApi.Initialization init;
    private String lastStage;

    @Override
    public void onInitializeClient() {
        open = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.screenmine.spike.openbrowser",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_B,
                CATEGORY));

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
            // `screen` existing says nothing about whether the overlay is up:
            // the screen outlives it. Without this flag, B after Esc took the
            // close branch and the overlay could never be reopened.
            if (overlayOpen) {
                overlayOpen = false;
                screen.browser.pause();
                mc.gui.setScreen(null);
                LOG.info("overlay closed, browser kept alive");
            } else {
                if (screen == null) {
                    spawn(mc);
                }
                if (screen != null) {
                    overlayOpen = true;
                    mc.gui.setScreen(new BrowserFullScreen(screen));
                }
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