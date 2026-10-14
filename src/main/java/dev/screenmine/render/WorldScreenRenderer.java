package dev.screenmine.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.screenmine.ScreenMineClient;
import dev.screenmine.world.PlacedScreen;
import dev.screenmine.world.ScreenWorld;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Draws every placed screen as a textured quad in the world. */
public final class WorldScreenRenderer {

    private final ScreenWorld world;

    public WorldScreenRenderer(ScreenWorld world) {
        this.world = world;
    }

    public void render(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        PoseStack matrices = ctx.matrixStack();
        if (matrices == null || mc.world == null) {
            return;
        }
        String dim = ScreenWorld.dimensionOf(mc);
        Vec3 cam = ctx.camera().getPos();

        ScreenWorld.Hit hit = ScreenMineClient.get().currentHit();
        PlacedScreen highlighted = hit == null ? null : hit.screen();

        for (PlacedScreen screen : world.all()) {
            if (!screen.dimension.equals(dim)) {
                continue;
            }
            // The browser is created lazily, on the render thread.
            if (!screen.browser.ensureCreated() || !screen.browser.ready()) {
                drawPlaceholder(matrices, cam, screen);
                continue;
            }
            drawScreen(matrices, cam, screen, screen == highlighted || screen.moving);
        }
    }

    private void drawScreen(PoseStack matrices, Vec3 cam, PlacedScreen s, boolean highlight) {
        float hw = s.width / 2.0f;
        float hh = s.height() / 2.0f;

        matrices.push();
        matrices.translate(s.pos.x - cam.x, s.pos.y - cam.y, s.pos.z - cam.z);
        matrices.multiply(Axis.POSITIVE_Y.rotationDegrees(-s.yaw));
        Matrix4f m = matrices.peek().getPositionMatrix();

        drawBacking(m, hw, hh, highlight);

        // Opaque, depth-writing draw. Without depthMask the clouds drawn later
        // pass the depth test and paint straight over the picture.
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShader(ShaderProgramKeys.POSITION_TEX_COLOR);
        RenderSystem.setShaderTexture(0, s.browser.textureId());

        BufferBuilder t = BufferBuilder.getInstance();
        BufferBuilder b = t.begin(VertexFormat.DrawMode.QUADS, DefaultVertexFormat.POSITION_TEXTURE_COLOR);
        b.vertex(m, -hw, -hh, 0f).texture(0f, 1f).color(255, 255, 255, 255);
        b.vertex(m, hw, -hh, 0f).texture(1f, 1f).color(255, 255, 255, 255);
        b.vertex(m, hw, hh, 0f).texture(1f, 0f).color(255, 255, 255, 255);
        b.vertex(m, -hw, hh, 0f).texture(0f, 0f).color(255, 255, 255, 255);
        BufferRenderer.drawWithGlobalProgram(b.end());

        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.enableCull();
        matrices.pop();
    }

    private void drawPlaceholder(PoseStack matrices, Vec3 cam, PlacedScreen s) {
        float hw = s.width / 2.0f;
        float hh = s.height() / 2.0f;
        matrices.push();
        matrices.translate(s.pos.x - cam.x, s.pos.y - cam.y, s.pos.z - cam.z);
        matrices.multiply(Axis.POSITIVE_Y.rotationDegrees(-s.yaw));
        Matrix4f m = matrices.peek().getPositionMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);

        BufferBuilder t = BufferBuilder.getInstance();
        BufferBuilder b = t.begin(VertexFormat.DrawMode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        quad(b, m, -hw, -hh, hw, hh, 0f, 16, 16, 20, 220);
        BufferRenderer.drawWithGlobalProgram(b.end());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        matrices.pop();
    }

    /** Opaque plate behind the picture, plus an optional bezel around it. */
    private void drawBacking(Matrix4f m, float hw, float hh, boolean highlight) {
        boolean bezel = dev.screenmine.Cfg.DRAW_FRAME
                        || (highlight && dev.screenmine.Cfg.HIGHLIGHT_AIMED);
        if (!dev.screenmine.Cfg.OPAQUE_BACKGROUND && !bezel) {
            return;
        }
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);

        BufferBuilder t = BufferBuilder.getInstance();
        BufferBuilder b = t.begin(VertexFormat.DrawMode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float o = dev.screenmine.Cfg.FRAME_THICKNESS;
        float z = -0.002f;

        if (bezel) {
            int r = highlight ? 90 : 20;
            int g = highlight ? 200 : 20;
            int bl = highlight ? 255 : 24;
            quad(b, m, -hw - o, hh, hw + o, hh + o, z, r, g, bl, 255);
            quad(b, m, -hw - o, -hh - o, hw + o, -hh, z, r, g, bl, 255);
            quad(b, m, -hw - o, -hh, -hw, hh, z, r, g, bl, 255);
            quad(b, m, hw, -hh, hw + o, hh, z, r, g, bl, 255);
        }
        if (dev.screenmine.Cfg.OPAQUE_BACKGROUND) {
            quad(b, m, -hw, -hh, hw, hh, z, 0, 0, 0, 255);
        }
        BufferRenderer.drawWithGlobalProgram(b.end());

        RenderSystem.enableCull();
    }

    private static void quad(BufferBuilder b, Matrix4f m,
                             float x0, float y0, float x1, float y1, float z,
                             int r, int g, int bl, int a) {
        b.vertex(m, x0, y0, z).color(r, g, bl, a);
        b.vertex(m, x1, y0, z).color(r, g, bl, a);
        b.vertex(m, x1, y1, z).color(r, g, bl, a);
        b.vertex(m, x0, y1, z).color(r, g, bl, a);
    }
}
