package dev.screenmine.spike;

/** Indirection so {@link BrowserTexture} does not hard-depend on mcef-modern. */
public interface MCEFBrowserRef {
    com.mojang.renderpearl.api.textures.GpuTextureView textureView();
}