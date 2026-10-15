package dev.screenmine.mixin;

import dev.screenmine.ScreenMineClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands keyboard input to the screen under the crosshair.
 *
 * <p>1.21.4 chained GLFW's window callbacks, so the mod could see a key before
 * Minecraft did and cancel it. 26.3 replaced GLFW with SDL3, so
 * {@code org.lwjgl.glfw} is gone and there is no public hook. These two targets
 * are where SDL events land instead, and they are the 26.3 equivalents named in
 * the TODO.
 *
 * <p>Signatures verified with javap on the 26.3 jar, not guessed:
 * {@code KeyboardHandler.keyPress(long, int, KeyEvent)} and
 * {@code charTyped(long, CharacterEvent)}.
 *
 * <p>Whether an event is taken or handed to the game is decided by the mod, not
 * here: {@code onWorldKey} only claims the key while control mode is on and the
 * page actually has a text field focused, which is what keeps WASD walking the
 * player around while they watch.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void screenmine$keyPress(long window, int action, KeyEvent event,
                                      CallbackInfo ci) {
        ScreenMineClient mod = ScreenMineClient.get();
        if (mod != null && mod.onWorldKey(action, event)) {
            ci.cancel();
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void screenmine$charTyped(long window, net.minecraft.client.input.CharacterEvent event,
                                     CallbackInfo ci) {
        ScreenMineClient mod = ScreenMineClient.get();
        if (mod != null && mod.onWorldChar(event.codepoint())) {
            ci.cancel();
        }
    }
}