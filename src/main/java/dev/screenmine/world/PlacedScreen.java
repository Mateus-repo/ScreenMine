package dev.screenmine.world;

import dev.screenmine.Cfg;
import dev.screenmine.browser.BrowserInstance;
import net.minecraft.world.phys.Vec3;

/**
 * A local placement of a shared stream in the world.
 *
 * <p>Position, rotation and size are purely client-side — every viewer decides
 * where the screen hangs for them. Only the browser content is shared. That is
 * what makes watching together work even when the friends are on different
 * Minecraft servers.
 */
public final class PlacedScreen {

    public final String streamId;
    public final BrowserInstance browser;

    /** Dimension this placement belongs to, e.g. {@code minecraft:overworld}. */
    public String dimension;

    /** World position of the centre of the screen. */
    public Vec3 pos;

    /** Yaw in degrees; the screen's front normal points along this yaw. */
    public float yaw;

    /** Width in blocks. Height follows from the browser aspect ratio. */
    public float width = Cfg.DEFAULT_WIDTH;

    /** True while the player is dragging the screen with their view. */
    public boolean moving;

    /** Distance kept from the player while moving. */
    public double moveDistance = Cfg.SPAWN_DISTANCE;

    /** Per-viewer volume, 0..100. Not shared over the network. */
    public int volume = Cfg.DEFAULT_VOLUME;

    /** Local mute. Forces the applied gain to zero. */
    public boolean muted;

    public PlacedScreen(String streamId, BrowserInstance browser, String dimension,
                        Vec3 pos, float yaw) {
        this.streamId = streamId;
        this.browser = browser;
        this.dimension = dimension;
        this.pos = pos;
        this.yaw = yaw;
    }

    public float height() {
        return width * Cfg.BROWSER_HEIGHT / (float) Cfg.BROWSER_WIDTH;
    }

    /** Unit normal pointing out of the screen's face. */
    public Vec3 normal() {
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.sin(r), 0.0, Math.cos(r));
    }

    /** Unit vector along the screen's local +U (to the right, seen from the front). */
    public Vec3 right() {
        Vec3 n = normal();
        return new Vec3(n.z, 0.0, -n.x);
    }

    public void resizeBy(float delta) {
        width = Math.max(Cfg.MIN_WIDTH, Math.min(Cfg.MAX_WIDTH, width + delta));
    }

    public void volumeBy(int delta) {
        volume = Math.max(0, Math.min(100, volume + delta));
        if (volume > 0) {
            muted = false;
        }
    }

    /** Distance from a point to the centre of the screen, in blocks. */
    public double distanceTo(double x, double y, double z) {
        double dx = x - pos.x;
        double dy = y - pos.y;
        double dz = z - pos.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Intersects a ray with the screen quad.
     *
     * @return {@code {u, v, distance}} if it hits inside the quad, otherwise {@code null}
     */
    public double[] raycast(Vec3 origin, Vec3 dir, double maxDistance) {
        Vec3 n = normal();
        double denom = dir.dot(n);
        if (Math.abs(denom) < 1.0e-6) {
            return null;
        }
        double t = pos.subtract(origin).dot(n) / denom;
        if (t <= 0.0 || t > maxDistance) {
            return null;
        }
        Vec3 hit = origin.add(dir.scale(t));
        Vec3 local = hit.subtract(pos);

        double hw = width / 2.0;
        double hh = height() / 2.0;
        double u = local.dot(right()) / (2.0 * hw) + 0.5;
        double v = 0.5 - local.y / (2.0 * hh);
        if (u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0) {
            return null;
        }
        return new double[]{u, v, t};
    }
}
