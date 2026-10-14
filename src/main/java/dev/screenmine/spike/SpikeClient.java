package dev.screenmine.spike;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
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
 * SPIKE — draws a real Chromium page as a quad in the 3D world on 26.3.
 *
 * <p>Purpose: prove the viability of the mod before migrating the ~120 GUI
 * errors. What is already proven: the API chain compiles, and mcef-modern
 * creates a browser and uploads frames without blocking the render thread.
 * What is left: that a quad actually shows up, and how it looks.
 *
 * <p>Deliberately needs no keybind. On entering a world it drops one quad three
 * blocks in front of the player, at eye height, facing them, and leaves it there.
 *
 * <p>To check, in a flat creative world:
 * <ol>
 *   <li>does the page appear at all?</li>
 *   <li>is it mirrored or upside down?</li>
 *   <li>walk around it — is the back visible? (tells us if the RenderType culls)</li>
 *   <li>{@code /time set midnight} — is it still legible? (tells us if lightmap
 *       is being applied)</li>
 * </ol>
 *
 * <p>Results decide the final {@link RenderType}, chosen from the verified list:
 * {@code text} (lightmapped), {@code entityTranslucent} (unculled),
 * {@code entityTranslucentEmissive} (unculled + unlit), {@code itemTranslucent},
 * {@code beaconBeam}.
 */
public final class SpikeClient implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("ScreenMine/spike");

    private static final int W = 1920;
    private static final int H = 1080;
    private static final float QUAD_WIDTH = 4.0f;

    private MCEFBrowser browser;
    private Identifier textureId;
    private boolean loggedTexture;

    /** Fixed once, when the player first appears, so the quad does not follow them. */
    private boolean placed;
    private Vec3 quadPos;
    private float quadYaw;
    private boolean loggedDraw;

    @Override
    public void onInitializeClient() {
        LevelRenderEvents.END_MAIN.register(this::render);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOG.info("spike registered on LevelRenderEvents.END_MAIN");
    }

    private void tick(Minecraft mc) {
        if (browser == null) {
            // Never block: the first launch downloads Chromium (~200 MB).
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
        if (!loggedTexture && browser.getTextureView() != null) {
            loggedTexture = true;
            LOG.info("TEXTURE VIEW PRESENT — mcef-modern uploaded a frame");
        }
    }

    private void render(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || browser == null || textureId == null) {
            return;
        }

        if (!placed) {
            Vec3 eye = mc.player.getEyePosition();
            quadYaw = mc.player.getYRot() + 180.0f;
            double rad = Math.toRadians(quadYaw);
            // 3 blocks along the screen's normal, so it sits in front of the player.
            quadPos = eye.add(-Math.sin(rad) * 3.0, 0.0, Math.cos(rad) * 3.0);
            placed = true;
            LOG.info("QUAD PLACED at {} {} {} yaw={}", quadPos.x, quadPos.y, quadPos.z, quadYaw);
        }

        RenderType renderType = RenderTypes.text(textureId);
        if (!loggedDraw) {
            LOG.info("RENDER TYPE = {} (lightmapped, cull state unknown until you look at it)",
                    renderType);
        }

        Vec3 cam = ctx.levelState().cameraRenderState.pos;
        float hh = QUAD_WIDTH * H / (float) W / 2.0f;
        float hw = QUAD_WIDTH / 2.0f;

        PoseStack pose = ctx.poseStack();
        pose.pushPose();
        // The event's PoseStack is world space; go camera-relative, as Fabric's
        // own LevelRenderEvents testmod does.
        pose.translate(-cam.x, -cam.y, -cam.z);
        pose.translate((float) (quadPos.x - cam.x), (float) (quadPos.y - cam.y),
                (float) (quadPos.z - cam.z));
        pose.rotateDegrees(Axis.YP, -quadYaw);

        ctx.submitNodeCollector().submitCustomGeometry(pose, renderType, (p, buf) -> {
            // QUADS topology: four vertices per primitive.
            buf.addVertex(p, -hw, -hh, 0f).setUv(0f, 1f).setColor(0xFFFFFFFF);
            buf.addVertex(p, hw, -hh, 0f).setUv(1f, 1f).setColor(0xFFFFFFFF);
            buf.addVertex(p, hw, hh, 0f).setUv(1f, 0f).setColor(0xFFFFFFFF);
            buf.addVertex(p, -hw, hh, 0f).setUv(0f, 0f).setColor(0xFFFFFFFF);
            if (!loggedDraw) {
                loggedDraw = true;
                LOG.info("SPIKE DRAW OK — quad submitted, texture view {}",
                        browser.getTextureView() != null ? "present" : "NULL");
            }
        });

        pose.popPose();
    }
}