package dev.screenmine.spike;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * SPIKE — bridges MCEF Modern's runtime {@code GpuTextureView} into Minecraft's
 * texture system, so that ordinary {@code RenderTypes.*} factories (which only
 * accept an {@code Identifier}) can bind it.
 *
 * <p>This is the piece that decides whether the mod is viable: the render world
 * space has no public API taking a {@code TextureSetup}, and
 * {@code RenderSetupBuilder#withTexture} only takes an {@code Identifier}.
 * Registering a custom {@code AbstractTexture} under an Identifier is the way
 * around that, using public API only — no mixin, no access widener.
 *
 * <p>Not part of the final mod yet; move to {@code dev.screenmine.render}.
 */
public final class BrowserTexture extends AbstractTexture {

    private final MCEFBrowserRef browser;
    private final GpuSampler sampler;

    public BrowserTexture(MCEFBrowserRef browser) {
        this.browser = browser;
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    /**
     * Asked for on every draw, not cached: MCEF Modern recreates its
     * {@code GpuTexture} whenever the browser is resized, so the view changes.
     */
    @Override
    public GpuTextureView getTextureView() {
        return browser.textureView();
    }

    @Override
    public GpuSampler getSampler() {
        return sampler;
    }

    /**
     * Deliberately does nothing. The default implementation closes the
     * underlying {@code GpuTexture}, which is owned by the browser — closing it
     * here would free the browser's texture out from under it.
     */
    @Override
    public void close() {
    }
}