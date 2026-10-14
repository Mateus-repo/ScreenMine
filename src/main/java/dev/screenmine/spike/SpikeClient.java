package dev.screenmine.spike;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPIKE — proves that a real Chromium page can be drawn as a quad in the 3D
 * world on Minecraft 26.3.
 *
 * <p>This is the viability question for the whole mod. Everything else (menus,
 * input) is mechanical migration; if this does not work, the mod has no main
 * feature.
 *
 * <p>What this proves:
 * <ul>
 *   <li>the API chain compiles against 26.3 (RegisterPipeline, RenderTypes.text,
 *       submitCustomGeometry, AbstractTexture registration);</li>
 *   <li>the render pipeline builds and the draw executes without throwing;</li>
 *   <li>the browser's runtime texture view is non-null and reaches the renderer.</li>
 * </ul>
 *
 * <p>What this cannot prove without a human watching: that the quad looks right
 * (orientation, culling, mirroring, lightmap). A one-shot log confirms the draw
 * happened, not that it looks correct.
 */
public final class SpikeClient implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("ScreenMine/spike");

    private static final int W = 1920;
    private static final int H = 1080;

    private MCEFBrowser browser;
    private Identifier textureId;
    private boolean loggedDraw;
    private boolean loggedTexture;

    @Override
    public void onInitializeClient() {
        LevelRenderEvents.END_MAIN.register(this::render);
        // Also tick on the main menu: the world-quad path needs a world, but the
        // browser half (Chromium download, texture view) must be provable without one.
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOG.info("spike registered on LevelRenderEvents.END_MAIN");
    }

    private void tick(Minecraft mc) {
        if (browser == null) {
            if (!MCEFApi.initialize().isDone()) {
                return;
            }
            browser = MCEFApi.getInstance().createBrowser("https://example.com", false);
            browser.resize(W, H);
            textureId = Identifier.fromNamespaceAndPath("screenmine", "spike/browser");
            mc.getTextureManager().register(textureId,
                    new BrowserTexture(() -> browser.getTextureView()));
            LOG.info("BROWSER CREATED, texture registered as {}", textureId);
            return;
        }
        if (!loggedTexture) {
            var view = browser.getTextureView();
            if (view != null) {
                loggedTexture = true;
                LOG.info("TEXTURE VIEW PRESENT — mcef-modern uploaded a frame");
            }
        }
    }

    private void render(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        if (browser == null) {
            // Never block the render thread: the first launch downloads Chromium.
            if (!MCEFApi.initialize().isDone()) {
                return;
            }
            browser = MCEFApi.getInstance().createBrowser("https://example.com", false);
            browser.resize(W, H);
            textureId = Identifier.fromNamespaceAndPath("screenmine", "spike/browser");
            mc.getTextureManager().register(textureId,
                    new BrowserTexture(() -> browser.getTextureView()));
            LOG.info("browser created, texture registered as {}", textureId);
        }

        RenderType renderType = RenderTypes.text(textureId);
        Vec3 cam = ctx.levelState().cameraRenderState.pos;
        Vec3 eye = mc.player.getEyePosition();

        PoseStack pose = ctx.poseStack();
        pose.pushPose();
        // The pose from the event is world space; go camera-relative like
        // Fabric's own LevelRenderEvents testmod does.
        pose.translate(-cam.x, -cam.y, -cam.z);

        float dist = 4.0f;
        float yaw = (float) Math.toRadians(mc.player.getYRot() + 180.0);
        pose.translate(
                (float) (eye.x - cam.x) + (float) (-Math.sin(yaw) * dist),
                (float) (eye.y - cam.y),
                (float) (eye.z - cam.z) + (float) (Math.cos(yaw) * dist));
        pose.rotateDegrees(com.mojang.math.Axis.YP, -(mc.player.getYRot() + 180.0f));

        final float hw = 3.0f;
        final float hh = 3.0f * H / (float) W;

        ctx.submitNodeCollector().submitCustomGeometry(pose, renderType, (p, buf) -> {
            // QUADS topology: four vertices per primitive, not two triangles.
            buf.addVertex(p, -hw, -hh, 0f).setUv(0f, 1f).setColor(0xFFFFFFFF);
            buf.addVertex(p, hw, -hh, 0f).setUv(1f, 1f).setColor(0xFFFFFFFF);
            buf.addVertex(p, hw, hh, 0f).setUv(1f, 0f).setColor(0xFFFFFFFF);
            buf.addVertex(p, -hw, hh, 0f).setUv(0f, 0f).setColor(0xFFFFFFFF);
            if (!loggedDraw) {
                loggedDraw = true;
                LOG.info("SPIKE DRAW OK — texture view was {}",
                        browser.getTextureView() != null ? "present" : "NULL");
            }
        });

        pose.popPose();
    }
}