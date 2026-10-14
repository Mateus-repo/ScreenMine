package dev.screenmine.world;

import dev.screenmine.Cfg;
import dev.screenmine.browser.BrowserInstance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns every screen this client has placed in the world. */
public final class ScreenWorld {

    private final Map<String, PlacedScreen> placed = new LinkedHashMap<>();

    public Collection<PlacedScreen> all() {
        return placed.values();
    }

    public boolean has(String streamId) {
        return placed.containsKey(streamId);
    }

    public PlacedScreen get(String streamId) {
        return placed.get(streamId);
    }

    public int count() {
        return placed.size();
    }

    /** Places a stream in front of the player. Reuses the placement if it exists. */
    public PlacedScreen attach(String streamId, String url) {
        PlacedScreen existing = placed.get(streamId);
        if (existing != null) {
            if (url != null && !url.isBlank()
                    && !BrowserInstance.sameVideo(existing.browser.url(), url)) {
                existing.browser.navigate(url, true);
            }
            return existing;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.world == null) {
            return null;
        }
        Entity p = mc.player;
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Vec3 pos = eye.add(look.multiply(Cfg.SPAWN_DISTANCE));
        // Face the player: the screen normal is the opposite of the player's look.
        float yaw = p.getYaw() + 180.0f;

        BrowserInstance browser = new BrowserInstance(streamId, url);
        PlacedScreen screen = new PlacedScreen(
                streamId, browser, dimensionOf(mc), pos, yaw);
        placed.put(streamId, screen);
        return screen;
    }

    public void detach(String streamId) {
        PlacedScreen screen = placed.remove(streamId);
        if (screen != null) {
            screen.browser.close();
        }
    }

    public void clear() {
        for (PlacedScreen screen : placed.values()) {
            screen.browser.close();
        }
        placed.clear();
    }

    public static String dimensionOf(Minecraft mc) {
        if (mc.world == null) {
            return "";
        }
        return mc.world.dimension().getValue().toString();
    }

    /** Screens in the player's current dimension, nearest first. */
    public List<PlacedScreen> visible(Minecraft mc) {
        String dim = dimensionOf(mc);
        List<PlacedScreen> out = new ArrayList<>();
        for (PlacedScreen s : placed.values()) {
            if (s.dimension.equals(dim)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Result of pointing the crosshair at a screen. */
    public record Hit(PlacedScreen screen, double u, double v, double distance) {
        public int pixelX() {
            return (int) Math.round(u * Cfg.BROWSER_WIDTH);
        }

        public int pixelY() {
            return (int) Math.round(v * Cfg.BROWSER_HEIGHT);
        }
    }

    /** The screen the player is currently looking at, if any. */
    public Hit pick(Minecraft mc) {
        if (mc.player == null || mc.world == null) {
            return null;
        }
        Vec3 origin = mc.player.getEyePosition();
        Vec3 dir = mc.player.getLookAngle();
        String dim = dimensionOf(mc);

        Hit best = null;
        for (PlacedScreen s : placed.values()) {
            if (!s.dimension.equals(dim) || s.moving) {
                continue;
            }
            double[] r = s.raycast(origin, dir, Cfg.INTERACT_RANGE);
            if (r == null) {
                continue;
            }
            if (best == null || r[2] < best.distance()) {
                best = new Hit(s, r[0], r[1], r[2]);
            }
        }
        return best;
    }

    /** Keeps screens that are being dragged glued to the player's view. */
    public void tickMoving(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        for (PlacedScreen s : placed.values()) {
            if (!s.moving) {
                continue;
            }
            s.pos = eye.add(look.multiply(s.moveDistance));
            s.yaw = mc.player.getYaw() + 180.0f;
            s.dimension = dimensionOf(mc);
        }
    }
}
