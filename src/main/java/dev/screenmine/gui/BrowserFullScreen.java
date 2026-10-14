package dev.screenmine.gui;

import dev.screenmine.Lang;
import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.screenmine.ScreenMineClient;
import dev.screenmine.world.PlacedScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * Full-screen view of a screen's browser, with real keyboard and mouse.
 *
 * <p>Mirrors the 1.21.11 build: the off-screen surface is resized to the window
 * while this is open, so the picture fills the screen and mouse coordinates map
 * one to one with no letterbox maths. On close the surface goes back to the
 * fixed resolution the in-world quad uses. Closing does <em>not</em> close the
 * browser — it stays alive on the wall.
 */
public final class BrowserFullScreen extends Screen {

    private final PlacedScreen screen;

    public BrowserFullScreen(PlacedScreen screen) {
        super(Component.literal(Lang.t("browser.title")));
        this.screen = screen;
    }

    @Override
    protected void init() {
        super.init();
        screen.browser.ensureCreated();
        fitToWindow();
        if (screen.browser.raw() != null) {
            screen.browser.raw().setFocus(true);
        }
    }

    /**
     * Sizes the off-screen surface to the window's real pixels.
     *
     * <p>{@code width}/{@code height} on a Screen are GUI-scaled, so at GUI
     * scale 3 they are a third of the window. Handing those to the browser makes
     * it lay the page out for a tiny viewport, which then gets stretched over
     * the whole window — the page comes out looking zoomed in. The framebuffer
     * size is the honest number.
     */
    private void fitToWindow() {
        Minecraft mc = Minecraft.getInstance();
        screen.browser.resize(mc.getWindow().getFramebufferWidth(),
                mc.getWindow().getFramebufferHeight());
    }

    /** GUI-scaled coordinate to a real pixel, for the browser's mouse input. */
    private static int px(double scaled) {
        return (int) Math.round(scaled * Minecraft.getInstance().getWindow().getScaleFactor());
    }

    private MCEFBrowser browser() {
        return screen.browser.raw();
    }

    @Override
    public void render(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // Background and widgets first — Screen.render() paints the background.
        super.render(ctx, mouseX, mouseY, delta);

        if (!screen.browser.ensureCreated() || !screen.browser.ready()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Component.literal(Lang.t("browser.starting")).formatted(ChatFormatting.GRAY),
                    width / 2, height / 2, 0xFFFFFF);
            return;
        }

        // GuiGraphicsExtractor batches its geometry and flushes at the end of the frame,
        // while the quad below is drawn immediately. Without this flush the
        // batched background lands on top of the browser and the screen is black.
        ctx.draw();

        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShader(ShaderProgramKeys.POSITION_TEX_COLOR);
        RenderSystem.setShaderTexture(0, screen.browser.textureId());

        BufferBuilder t = BufferBuilder.getInstance();
        BufferBuilder b = t.begin(VertexFormat.DrawMode.QUADS, DefaultVertexFormat.POSITION_TEXTURE_COLOR);
        b.vertex(0f, height, 0f).texture(0f, 1f).color(255, 255, 255, 255);
        b.vertex(width, height, 0f).texture(1f, 1f).color(255, 255, 255, 255);
        b.vertex(width, 0f, 0f).texture(1f, 0f).color(255, 255, 255, 255);
        b.vertex(0f, 0f, 0f).texture(0f, 0f).color(255, 255, 255, 255);
        BufferRenderer.drawWithGlobalProgram(b.end());

        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.enableDepthTest();

        ctx.drawCenteredTextWithShadow(textRenderer,
                Component.literal(Lang.t("browser.close.hint"))
                        .formatted(ChatFormatting.DARK_GRAY),
                width / 2, height - 12, 0xFFFFFF);
    }

    @Override
    public void resize(Minecraft mc, int w, int h) {
        super.resize(mc, w, h);
        fitToWindow();
    }

    // ----------------------------------------------------------------- input //

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (browser() != null) {
            browser().sendMousePress(px(mouseX), px(mouseY), button);
            browser().setFocus(true);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (browser() != null) {
            browser().sendMouseRelease(px(mouseX), px(mouseY), button);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (browser() != null) {
            browser().sendMouseMove(px(mouseX), px(mouseY));
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (browser() != null) {
            browser().sendMouseMove(px(mouseX), px(mouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (browser() != null) {
            browser().sendMouseWheel(px(mouseX), px(mouseY), vertical, 0);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {   // Esc always closes the overlay
            close();
            return true;
        }
        if (browser() != null) {
            browser().sendKeyPress(keyCode, scanCode, modifiers);
            browser().setFocus(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (browser() != null) {
            browser().sendKeyRelease(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (codePoint == 0) {
            return false;
        }
        if (browser() != null) {
            browser().sendKeyTyped(codePoint, modifiers);
            browser().setFocus(true);
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void close() {
        if (browser() != null) {
            browser().setFocus(false);
        }
        screen.browser.resetSurface();
        ScreenMineClient.get().onLocalNavigate(screen.streamId, screen.browser.url());
        client.setScreen(null);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
