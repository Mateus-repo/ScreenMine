package dev.screenmine.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.screenmine.Cfg;
import dev.screenmine.ScreenMineClient;
import dev.screenmine.browser.BrowserInstance;
import dev.screenmine.world.PlacedScreen;
import dev.screenmine.world.ScreenWorld;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws every placed screen as a textured quad in the world.
 *
 * <p>26.3 deleted {@code Tessellator}, {@code BufferBuilder} and
 * {@code BufferRenderer}, and render state moved off {@code RenderSystem} into
 * render pipelines, so the old immediate-mode code could not be ported line by
 * line. Geometry now goes through the level submit-node collector, the same
 * public path used by the render spike where this was proven in game.
 */
public final class WorldScreenRenderer {

    /**
     * Fully lit: block-light and sky-light both at 15. {@code setLight} unpacks this
     * into the two lightmap UVs.
     */
    private static final int FULL_BRIGHT = 0xF000F0;

    /**
     * A live texture registration: which id, and which browser instance it
     * points at.
     *
     * <p>The instance is part of the key on purpose. The map used to be keyed by
     * stream id alone, and releaseDetached only dropped an entry when the stream
 * was absent at the moment a frame rendered. Detach and re-attach of the same
 * id inside one tick - which onSession does - means it is never absent, the
 * stale entry survives, and it keeps pointing at a closed
 * {@link BrowserInstance}. The screen then renders black and never recovers
 * without a restart.
     */
    private record Registration(Identifier id, BrowserInstance browser) {
    }

    /** Stream id to the texture registration its browser is using. */
    private final Map<String, Registration> textureIds = new HashMap<>();

    /** Stream ids come from the network; cap them before they become a path. */
    private static final int MAX_ID_CHARS = 64;

    private final ScreenWorld world;

    public WorldScreenRenderer(ScreenWorld world) {
        this.world = world;
    }

    public void render(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = ctx.poseStack();
        if (mc.level == null || mc.player == null) {
            return;
        }
        String dim = ScreenWorld.dimensionOf(mc);
        Vec3 cam = ctx.levelState().cameraRenderState.pos;

        ScreenWorld.Hit hit = ScreenMineClient.get().currentHit();
        PlacedScreen highlighted = hit == null ? null : hit.screen();

        releaseDetached();

        // Remote screens, nearest first, so the cap takes the ones in front of
        // the player rather than whichever happened to be created first.
        int remoteBudget = Cfg.MAX_REMOTE_BROWSERS;
        var eye = mc.player.getEyePosition();

        for (PlacedScreen screen : world.all()) {
            if (!screen.dimension.equals(dim)) {
                continue;
            }
            if (screen.browser.isRemote()) {
                double distance = eye.distanceTo(screen.pos);
                if (distance > Cfg.REMOTE_RELEASE_DISTANCE) {
                    // Well out of reach. Give the Chromium back rather than
                    // holding one for a screen nobody can see.
                    screen.browser.logWhyNoFrame(false);
                    if (screen.browser.ready()) {
                        // release() logs, with the stream id; the renderer has no
                        // logger of its own and should not grow one for a line.
                        screen.browser.release();
                    }
                    continue;
                }
                if (distance > Cfg.REMOTE_SPAWN_DISTANCE) {
                    // Inside release range but outside spawn range: keep what
                    // exists, start nothing new.
                    if (screen.browser.ready() && remoteBudget > 0) {
                        remoteBudget--;
                    } else if (!screen.browser.ready()) {
                        screen.browser.logWhyNoFrame(true);
                        continue;
                    }
                } else if (!screen.browser.ready()) {
                    if (remoteBudget <= 0) {
                        // At the cap. Draw the plate rather than a page: a
                        // grey plate is honest, a fifth Chromium is not.
                        place(pose, cam, screen);
                        drawSolid(ctx, pose, screen.width, screen.height(), 16, 16, 20, 220, -1, 0, 0);
                        pose.popPose();
                        continue;
                    }
                    remoteBudget--;
                } else {
                    remoteBudget--;
                }
            }
            // The browser is created lazily; until it has a frame we show the plate.
            if (screen.browser.isRemote()) {
                // Name the reason rather than leaving a black plate to be
                // guessed at: out of range, Chromium not ready, or a refused
                // navigation are three different problems with three different
                // fixes.
                screen.browser.logWhyNoFrame(true);
            }
            if (!screen.browser.ensureCreated() || !screen.browser.ready()) {
                place(pose, cam, screen);
                drawSolid(ctx, pose, screen.width, screen.height(), 16, 16, 20, 220, -1, 0, 0);
                pose.popPose();
                continue;
            }
            drawScreen(ctx, pose, cam, screen, screen == highlighted || screen.moving);
        }
    }

    /**
     * Drops the texture registrations of screens that are no longer placed, so
     * spawning and removing screens does not accumulate entries forever. Release
     * is safe: {@link BrowserTexture#close()} does not free the browser's texture.
     */
    private void releaseDetached() {
        if (textureIds.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        textureIds.entrySet().removeIf(e -> {
            if (world.get(e.getKey()) != null) {
                return false;
            }
            mc.getTextureManager().release(e.getValue().id());
            return true;
        });
    }

    private void drawScreen(LevelRenderContext ctx, PoseStack pose, Vec3 cam,
                            PlacedScreen s, boolean highlight) {
        float hw = s.width / 2.0f;
        float hh = s.height() / 2.0f;

        place(pose, cam, s);

        // The bezel and the backing are drawn in the screen's own frame, so they
        // must not transform again on top of place().
        boolean bezel = Cfg.DRAW_FRAME || (highlight && Cfg.HIGHLIGHT_AIMED);
        if (Cfg.OPAQUE_BACKGROUND || bezel) {
            int r = highlight ? 90 : 20;
            int g = highlight ? 200 : 20;
            int b = highlight ? 255 : 24;
            if (bezel) {
                drawSolid(ctx, pose, s.width, s.height(), r, g, b, 255, 0, -1, Cfg.FRAME_THICKNESS);
            }
            if (Cfg.OPAQUE_BACKGROUND) {
                drawSolid(ctx, pose, s.width, s.height(), 0, 0, 0, 255, -0.002f, 0, 0);
            }
        }

        RenderType type = RenderTypes.text(textureIdFor(s));
        // RenderTypes.text is lightmapped, but every vertex is submitted with a
        // full-bright lightmap, so the picture keeps its own brightness instead of
        // dimming with the world. Whether that looks right next to a lit scene is a
        // visual call, but it is no longer a missing feature.
        ctx.submitNodeCollector().submitCustomGeometry(pose, type, (p, buf) -> {
            vertex(buf, p, -hw, -hh, 0f, 0f, 1f, 0xFFFFFFFF);
            vertex(buf, p, hw, -hh, 0f, 1f, 1f, 0xFFFFFFFF);
            vertex(buf, p, hw, hh, 0f, 1f, 0f, 0xFFFFFFFF);
            vertex(buf, p, -hw, hh, 0f, 0f, 0f, 0xFFFFFFFF);
        });

        pose.popPose();
    }

    /**
     * One solid-coloured rectangle in the screen's plane, used for the backing
     * plate, the bezel and the "browser still loading" placeholder.
     *
     * @param z    z offset inside the screen's plane
     * @param grow how far to inflate the rectangle on every side; negative shrinks
     */
    private void drawSolid(LevelRenderContext ctx, PoseStack pose,
                           float width, float height,
                           int r, int g, int b, int alpha, float z, float grow, float thick) {
        float hw = width / 2.0f + grow;
        float hh = height / 2.0f + grow;

        // RenderTypes.debugQuads is the vanilla render type for plain coloured quads and
        // needs no texture. Its vertex format is built lazily, so it could not be
        // confirmed with javap; vertex() below submits every attribute and lets the
        // builder drop the ones this format does not use, which makes the choice
        // format-agnostic.
        RenderType type = RenderTypes.debugQuads();

        final float zf = z;
        if (thick > 0f) {
            // Four bars around the edge.
            bar(ctx, pose, type, -hw - thick, hh, hw + thick, hh + thick, zf, r, g, b, alpha);
            bar(ctx, pose, type, -hw - thick, -hh - thick, hw + thick, -hh, zf, r, g, b, alpha);
            bar(ctx, pose, type, -hw - thick, -hh, -hw, hh, zf, r, g, b, alpha);
            bar(ctx, pose, type, hw, -hh, hw + thick, hh, zf, r, g, b, alpha);
        } else {
            bar(ctx, pose, type, -hw, -hh, hw, hh, zf, r, g, b, alpha);
        }
    }

    private void bar(LevelRenderContext ctx, PoseStack pose, RenderType type,
                     float x0, float y0, float x1, float y1, float z,
                     int r, int g, int b, int alpha) {
        final int col = (alpha << 24) | (r << 16) | (g << 8) | b;
        ctx.submitNodeCollector().submitCustomGeometry(pose, type, (p, buf) -> {
            vertex(buf, p, x0, y0, z, 0f, 0f, col);
            vertex(buf, p, x1, y0, z, 1f, 0f, col);
            vertex(buf, p, x1, y1, z, 1f, 1f, col);
            vertex(buf, p, x0, y1, z, 0f, 1f, col);
        });
    }

    /**
     * One fully specified vertex.
     *
     * <p>Every attribute is set, not just the ones a particular {@link RenderType}
     * happens to want. {@code BufferBuilder.beginElement} returns -1 for an element
     * that is not part of the active format and the matching setter then skips the
     * write, so this is correct for any format while a partial vertex is not:
     * leaving out an attribute the format does require throws
     * {@code IllegalStateException: Missing elements in vertex}, which is exactly
     * how the first in-game run died.
     */
    private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer buf,
                               PoseStack.Pose p, float x, float y, float z,
                               float u, float v, int colour) {
        buf.addVertex(p, x, y, z)
                .setColor(colour)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(0f, 1f, 0f);
    }

    /**
     * Moves the pose into the screen's own frame: camera-relative, then yaw.
     *
     * <p>The {@link PoseStack} handed to the level render event is already relative to
     * the camera, like every other piece of level geometry, so the only translate
     * needed is the screen position minus the camera. Subtracting the camera a second
     * time - which the render spike did - leaves the quad at {@code pos - 2 * cam},
     * so it drifts twice as fast as the player walks. That is exactly how it looked:
     * screens that would not stay where they were placed.
     *
     * <p>Everything belonging to one screen - backing, bezel and picture - is drawn
     * inside a single call to this, so they cannot drift apart from each other.
     */
    private void place(PoseStack pose, Vec3 cam, PlacedScreen s) {
        pose.pushPose();
        pose.translate((float) (s.pos.x - cam.x), (float) (s.pos.y - cam.y), (float) (s.pos.z - cam.z));
        pose.rotateDegrees(Axis.YP, -s.yaw);
    }

    /**
     * Registers the browser's live texture under a stable id, once per stream.
     * Re-registering every frame would churn the texture manager for nothing.
     */
    private Identifier textureIdFor(PlacedScreen s) {
        Registration existing = textureIds.get(s.streamId);
        if (existing != null) {
            if (existing.browser() == s.browser) {
                return existing.id();
            }
            // Same stream id, different browser: the previous placement was
            // detached and re-attached without a frame in between. The old
            // registration points at a closed browser, so drop it now rather
            // than waiting for releaseDetached, which will never see it absent.
            Minecraft.getInstance().getTextureManager().release(existing.id());
        }
        // Stream ids come from the network, so sanitise before they become a path.
        String safe = s.streamId.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.length() > MAX_ID_CHARS) {
            safe = safe.substring(0, MAX_ID_CHARS);
        }
        Identifier identifier =
                Identifier.fromNamespaceAndPath(ScreenMineClient.MOD_ID, "browser/" + safe);
        Minecraft.getInstance().getTextureManager()
                .register(identifier, new BrowserTexture(s.browser));
        textureIds.put(s.streamId, new Registration(identifier, s.browser));
        return identifier;
    }
}