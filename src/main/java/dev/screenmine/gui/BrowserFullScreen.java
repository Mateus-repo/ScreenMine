package dev.screenmine.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.screenmine.Lang;
import dev.screenmine.world.PlacedScreen;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fullscreen browser overlay.
 *
 * <p>Ported to 26.3: the immediate-mode draw is gone (no {@code Tessellator}, no
 * {@code RenderSystem.setShaderTexture}), so the frame is pushed into
 * {@code guiRenderState} as a {@link BlitRenderState}, exactly as mcef-modern's
 * own testmod does. The texture is a {@code GpuTextureView} straight from the
 * browser, wrapped in a {@link TextureSetup}.
 */
public final class BrowserFullScreen extends Screen {

    private static final Logger LOG = LoggerFactory.getLogger("ScreenMine");

    private final PlacedScreen screen;
    private final Screen parent;

    public BrowserFullScreen(PlacedScreen screen) {
        this(screen, null);
    }

    public BrowserFullScreen(PlacedScreen screen, Screen parent) {
        super(Component.literal("ScreenMine"));
        this.screen = screen;
        this.parent = parent;
        fitToWindow();
    }

    /**
     * {@code width}/{@code height} on a Screen are GUI-scaled, so at GUI scale 3
     * they are a third of the window. Handing those to the browser makes it lay
     * the page out for a tiny viewport, which then gets stretched over the whole
     * window — the page comes out looking zoomed in. The framebuffer size is the
     * honest number.
     */
    private void fitToWindow() {
        Window w = Minecraft.getInstance().getWindow();
        screen.browser.resize(w.getWidth(), w.getHeight());
    }

    /** GUI-scaled coordinate to a real pixel, for the browser's mouse input. */
    private static int px(double scaled) {
        return (int) Math.round(scaled * Minecraft.getInstance().getWindow().getGuiScale());
    }

    private MCEFBrowser browser() {
        return screen.browser.raw();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(gfx, mouseX, mouseY, delta);

        if (!screen.browser.ensureCreated() || !screen.browser.ready()) {
            gfx.centeredText(font,
                    Component.literal(Lang.t("browser.starting")).withStyle(ChatFormatting.GRAY),
                    width / 2, height / 2, 0xFFFFFF);
            return;
        }

        GpuTextureView view = screen.browser.textureView();
        if (view != null) {
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            gfx.guiRenderState.addGuiElement(new BlitRenderState(
                    RenderPipelines.GUI_TEXTURED,
                    TextureSetup.singleTexture(view, sampler),
                    new Matrix3x2f(gfx.pose()),
                    0, 0, width, height,
                    0.0F, 1.0F, 0.0F, 1.0F,
                    0xFFFFFFFF,
                    gfx.scissorStack.peek()));
        }

        // Let the page pick the OS cursor shape; it no longer steals the grab.
        MCEFBrowser b = browser();
        if (b != null && b.getCursorType() != null) {
            gfx.requestCursor(b.getCursorType());
        }

        gfx.centeredText(font,
                Component.literal(Lang.t("browser.close.hint")).withStyle(ChatFormatting.DARK_GRAY),
                width / 2, height - 12, 0xFFFFFF);
    }

    @Override
    public void resize(int w, int h) {
        super.resize(w, h);
        fitToWindow();
    }

    // ----------------------------------------------------------------- input //

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        MCEFBrowser b = browser();
        if (b != null) {
            b.onMouseClicked(event, doubled);
            b.setFocus(true);
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        MCEFBrowser b = browser();
        if (b != null) {
            b.onMouseReleased(event);
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        MCEFBrowser b = browser();
        if (b != null) {
            b.onMouseMoved(px(mouseX), px(mouseY));
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        MCEFBrowser b = browser();
        if (b != null) {
            b.onMouseMoved(px(event.x()), px(event.y()));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        MCEFBrowser b = browser();
        if (b != null) {
            b.onMouseScrolled(px(mouseX), px(mouseY), vertical);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Esc always closes the overlay. Uses the vanilla helper rather than the
        // old GLFW 256: the 26.3 backend is SDL.
        if (event.isEscape()) {
            onClose();
            return true;
        }
        MCEFBrowser b = browser();
        if (b != null) {
            b.onKeyPressed(event);
            b.setFocus(true);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        MCEFBrowser b = browser();
        if (b != null) {
            b.onKeyReleased(event);
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        // Separate from keyPressed on purpose: text needs the character events,
        // not the key events, or accented input and IME composition break.
        if (event.codepoint() == 0) {
            return false;
        }
        MCEFBrowser b = browser();
        if (b != null) {
            b.onCharTyped(event);
            b.setFocus(true);
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        MCEFBrowser b = browser();
        if (b != null) {
            b.setFocus(false);
        }
        screen.browser.resetSurface();
        // Was ScreenMineClient#get().onLocalNavigate(...) in the full port. The
        // sync layer does not exist on this spike branch, so just record it.
        LOG.info("closed overlay for stream {} at {}", screen.streamId, screen.browser.url());
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}