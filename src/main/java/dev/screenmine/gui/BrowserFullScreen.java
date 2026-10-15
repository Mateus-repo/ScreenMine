package dev.screenmine.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.screenmine.Cfg;
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
 * Fullscreen browser overlay, instrumented.
 *
 * <p>The previous run proved the keybinding and the overlay work, and that the
 * browser loads a page, but nothing typed or clicked reached the page. Rather
 * than guess, every input handler now logs, so a retest shows exactly which
 * events arrive and with what values.
 */
public final class BrowserFullScreen extends Screen {

    private static final Logger LOG = LoggerFactory.getLogger("ScreenMine/input");

    private final PlacedScreen screen;
    private final Screen parent;
    private boolean sized;

    public BrowserFullScreen(PlacedScreen screen) {
        this(screen, null);
    }

    public BrowserFullScreen(PlacedScreen screen, Screen parent) {
        super(Component.literal("ScreenMine"));
        this.screen = screen;
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        // The SDL backend only emits character events while something holds text
        // input focus. Without this, key events arrive but charTyped never does,
        // so typing does nothing at all.
        minecraft.onTextInputFocusChange(this, true);
        // Keep the page agent alive: onLoadEnd can miss a fast page, so the
        // fallback retry is what actually gets the agent (and the editing shim) in.
        screen.browser.pumpAgent();
        screen.browser.ensureCreated();
        fitToWindow();
        MCEFBrowser b = browser();
        if (b != null) {
            b.setFocus(true);
        }
        LOG.info("overlay opened, browser ready={} texture={}",
                screen.browser.ready(), screen.browser.textureView() != null);
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
        LOG.info("fit to window {}x{}", w.getWidth(), w.getHeight());
    }

    /**
     * Minecraft hands out GUI-scaled coordinates; CEF wants pixels in the
     * browser viewport. Passing the event through unchanged sends every click to
     * half its position, which lands nowhere near what the user aimed at.
     */
    private static MouseButtonEvent toPixels(MouseButtonEvent e) {
        return new MouseButtonEvent(px(e.x()), px(e.y()), e.buttonInfo());
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
            // Say what is actually happening instead of showing an empty page.
            // ensureCreated() is idempotent, so this tells us whether the browser
            // object exists yet: no browser means Chromium itself is still booting,
            // a browser without a frame means it is loading.
            String msg = screen.browser.ensureCreated()
                    ? Lang.t("browser.starting")
                    : "Chromium still starting \u2014 this takes a minute the first time";
            gfx.centeredText(font,
                    Component.literal(msg).withStyle(ChatFormatting.GRAY),
                    width / 2, height / 2, 0xFFFFFF);
            return;
        }

        if (!sized) {
            fitToWindow();
            sized = true;
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
        if (screen.browser.ready()) {
            fitToWindow();
        }
    }

    // ----------------------------------------------------------------- input //

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        MCEFBrowser b = browser();
        if (b == null) {
            return super.mouseClicked(event, doubled);
        }
        LOG.info("IN mouseClicked btn={} doubled={} raw=({},{}) px=({},{}) scale={}",
                event.button(), doubled, event.x(), event.y(),
                px(event.x()), px(event.y()),
                Minecraft.getInstance().getWindow().getGuiScale());
        b.onMouseClicked(toPixels(event), doubled);
        b.setFocus(true);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        MCEFBrowser b = browser();
        if (b == null) {
            return super.mouseReleased(event);
        }
        LOG.info("IN mouseReleased btn={}", event.button());
        b.onMouseReleased(toPixels(event));
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        MCEFBrowser b = browser();
        if (b != null) {
            LOG.info("IN mouseMoved px=({},{})", px(mouseX), px(mouseY));
            b.onMouseMoved(px(mouseX), px(mouseY));
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        MCEFBrowser b = browser();
        if (b != null) {
            b.onMouseMoved(px(event.x()), px(event.y()));  // ja em pixels
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        MCEFBrowser b = browser();
        if (b == null) {
            return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }
        LOG.info("IN scroll raw=({},{}) vertical={} px=({},{})",
                mouseX, mouseY, vertical, px(mouseX), px(mouseY));
        // Scaled like the world path does, so one notch scrolls the same amount
        // in both. The overlay was passing the raw wheel delta and quietly
        // ignoring Cfg.SCROLL_SPEED, which made the two disagree by 3x.
        b.onMouseScrolled(px(mouseX), px(mouseY), vertical * Cfg.SCROLL_SPEED);
        return true;
    }

    /**
     * A readable name for the key, so the log says BACKSPACE instead of 271.
     *
     * <p>The bare integer was useless for the bug it was meant to settle: the
     * open question is whether a key press arrives without the matching
     * {@code charTyped}, and you cannot tell which key was involved if the log
     * only carries a number. {@code InputConstants.getKey} maps the name back to
     * a key for anything vanilla knows about.
     */
    private static String keyName(KeyEvent event) {
        try {
            return InputConstants.getKey(event).getName();
        } catch (RuntimeException ex) {
            return "raw-" + event.key();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            onClose();
            return true;
        }
        MCEFBrowser b = browser();
        if (b == null) {
            return super.keyPressed(event);
        }
        LOG.info("IN keyPressed {} mods={}", keyName(event), event.modifiers());
        b.onKeyPressed(event);
        b.setFocus(true);
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        MCEFBrowser b = browser();
        if (b == null) {
            return super.keyReleased(event);
        }
        LOG.info("IN keyReleased {}", keyName(event));
        b.onKeyReleased(event);
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (event.codepoint() == 0) {
            return false;
        }
        MCEFBrowser b = browser();
        if (b == null) {
            return super.charTyped(event);
        }
        LOG.info("IN charTyped cp={} '{}'", event.codepoint(), event.codepointAsString());
        b.onCharTyped(event);
        b.setFocus(true);
        return true;
    }

    @Override
    public void onClose() {
        minecraft.onTextInputFocusChange(this, false);
        MCEFBrowser b = browser();
        if (b != null) {
            b.setFocus(false);
        }
        screen.browser.resetSurface();
        LOG.info("overlay closed for stream {} at {}", screen.streamId, screen.browser.url());
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
