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

    /** Stream id to the texture id its browser is registered under. */
    private final Map<String, Identifier> textureIds = new HashMap<>();

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

        for (PlacedScreen screen : world.all()) {
            if (!screen.dimension.equals(dim)) {
                continue;
            }
            // The browser is created lazily; until it has a frame we show the plate.
            if (!screen.browser.ensureCreated() || !screen.browser.ready()) {
                drawSolid(ctx, pose, cam, screen, 16, 16, 20, 220, -1, 0);
                continue;
            }
            drawScreen(ctx, pose, cam, screen, screen == highlighted || screen.moving);
        }
    }

    private void drawScreen(LevelRenderContext ctx, PoseStack pose, Vec3 cam,
                            PlacedScreen s, boolean highlight) {
        float hw = s.width / 2.0f;
        float hh = s.height() / 2.0f;

        pushAt(pose, cam, s);

        boolean bezel = Cfg.DRAW_FRAME || (highlight && Cfg.HIGHLIGHT_AIMED);
        if (Cfg.OPAQUE_BACKGROUND || bezel) {
            int r = highlight ? 90 : 20;
            int g = highlight ? 200 : 20;
            int b = highlight ? 255 : 24;
            if (bezel) {
                float o = Cfg.FRAME_THICKNESS;
                drawSolid(ctx, pose, cam, s, r, g, b, 255, 0, -1, o);
            }
            if (Cfg.OPAQUE_BACKGROUND) {
                drawSolid(ctx, pose, cam, s, 0, 0, 0, 255, -0.002f, 0);
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
    private void drawSolid(LevelRenderContext ctx, PoseStack pose, Vec3 cam, PlacedScreen s,
                           int r, int g, int b, int alpha, float z, float grow) {
        drawSolid(ctx, pose, cam, s, r, g, b, alpha, z, grow, 0f);
    }

    /** As above, but also outlining a frame of thickness {@code thick}. */
    private void drawSolid(LevelRenderContext ctx, PoseStack pose, Vec3 cam, PlacedScreen s,
                           int r, int g, int b, int alpha, float z, float grow, float thick) {
        float hw = s.width / 2.0f + grow;
        float hh = s.height() / 2.0f + grow;

        // RenderTypes.debugQuads is the vanilla render type for plain coloured quads and
        // needs no texture. Its vertex format is built lazily, so it could not be
        // confirmed with javap; vertex() below submits every attribute and lets the
        // builder drop the ones this format does not use, which makes the choice
        // format-agnostic.
        RenderType type = RenderTypes.debugQuads();

        pose.pushPose();
        pose.translate((float) (s.pos.x - cam.x), (float) (s.pos.y - cam.y), (float) (s.pos.z - cam.z));
        pose.rotateDegrees(Axis.YP, -s.yaw);

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

        pose.popPose();
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

    /** Moves to the screen's own frame: camera-relative, then yaw. */
    private void pushAt(PoseStack pose, Vec3 cam, PlacedScreen s) {
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        pose.translate((float) (s.pos.x - cam.x), (float) (s.pos.y - cam.y), (float) (s.pos.z - cam.z));
        pose.rotateDegrees(Axis.YP, -s.yaw);
    }

    /**
     * Registers the browser's live texture under a stable id, once per stream.
     * Re-registering every frame would churn the texture manager for nothing.
     */
    private Identifier textureIdFor(PlacedScreen s) {
        return textureIds.computeIfAbsent(s.streamId, id -> {
            // Stream ids come from the network, so sanitise before they become a path.
            String safe = id.replaceAll("[^A-Za-z0-9_.-]", "_");
            Identifier identifier =
                    Identifier.fromNamespaceAndPath(ScreenMineClient.MOD_ID, "browser/" + safe);
            Minecraft.getInstance().getTextureManager()
                    .register(identifier, new BrowserTexture(s.browser));
            return identifier;
        });
    }
}