package dev.screenmine.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.screenmine.browser.BrowserInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * Bridges mcef-modern's runtime {@code GpuTextureView} into Minecraft's texture
 * system, so the ordinary {@code RenderTypes.*} factories — which only accept an
 * {@link net.minecraft.resources.Identifier} — can bind it.
 *
 * <p>This is the piece that makes world rendering possible at all: the world-space
 * render path has no public API taking a {@code TextureSetup}, and the submit-node
 * collector only binds textures by id. Registering a custom {@link AbstractTexture}
 * under an id is the way around that, using public API only — no mixin, no access
 * widener. Approach taken from the render spike, where it was proven in game.
 */
public final class BrowserTexture extends AbstractTexture {

    private final BrowserInstance browser;
    private final GpuSampler sampler;

    public BrowserTexture(BrowserInstance browser) {
        this.browser = browser;
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    /**
     * Asked for on every draw, never cached: mcef-modern recreates its underlying
     * {@code GpuTexture} whenever the browser is resized, so the view changes.
     */
    @Override
    public GpuTextureView getTextureView() {
        return browser.textureView();
    }

    /**
     * The sampler follows the view, and is deliberately null whenever the view is.
     *
     * <p>The render pipeline binds the pair together and throws
     * {@code IllegalArgumentException: textureView and sampler must both or neither
     * be null} if only one of them is set. Geometry submitted while the browser was
     * alive is drawn a little later, and by then the browser can have been closed or
     * resized, which drops the view. Returning null with it keeps the pair
     * consistent instead of crashing the render thread.
     */
    @Override
    public GpuSampler getSampler() {
        return browser.textureView() == null ? null : sampler;
    }

    /**
     * Deliberately does nothing. The default implementation closes the underlying
     * {@code GpuTexture}, which is owned by the browser — closing it here would free
     * the browser's texture out from under it and crash the render thread.
     */
    @Override
    public void close() {
    }
}