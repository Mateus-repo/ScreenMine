package dev.screenmine.mixin;

import dev.screenmine.ScreenMineClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the mouse wheel to the screen under the crosshair.
 *
 * <p>Without this, control mode could click but not scroll. 1.21.4 stole the
 * wheel through GLFW; 26.3 has no GLFW, and {@code MouseHandler.onScroll} is
 * where the SDL scroll event arrives instead. Signature verified with javap:
 * {@code onScroll(long, double, double)} - two axes, so the mod gets both and
 * the horizontal one is not silently dropped.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void screenmine$onScroll(long window, double horizontal, double vertical,
                                     CallbackInfo ci) {
        ScreenMineClient mod = ScreenMineClient.get();
        if (mod != null && mod.onWorldScroll(horizontal, vertical)) {
            ci.cancel();
        }
    }
}