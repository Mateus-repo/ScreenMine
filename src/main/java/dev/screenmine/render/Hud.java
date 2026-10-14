package dev.screenmine.render;

import dev.screenmine.ScreenMineClient;
import dev.screenmine.Lang;
import dev.screenmine.state.StreamInfo;
import dev.screenmine.world.PlacedScreen;
import dev.screenmine.world.ScreenWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/** Small on-screen hints: connection state, what you are pointing at, invites. */
public final class Hud {

    public void render(GuiGraphicsExtractor ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null) {
            return;
        }
        ScreenMineClient mod = ScreenMineClient.get();
        int y = 4;

        if (mod.state().inSharedSession()) {
            String host = mod.state().sessionHost;
            ctx.drawTextWithShadow(mc.textRenderer,
                    Component.literal(Lang.t("hud.session", host, mod.state().sessionMembers.size()))
                            .formatted(ChatFormatting.AQUA),
                    4, y, 0xFFFFFF);
            y += 11;
        }

        if (!mod.state().invites.isEmpty()) {
            ctx.drawTextWithShadow(mc.textRenderer,
                    Component.literal(Lang.t("hud.invite",
                                    String.join(", ", mod.state().invites), mod.menuKeyName()))
                            .formatted(ChatFormatting.GOLD),
                    4, y, 0xFFFFFF);
            y += 11;
        }
        if (!mod.state().requestsIn.isEmpty()) {
            ctx.drawTextWithShadow(mc.textRenderer,
                    Component.literal(Lang.t("hud.requests",
                                    mod.state().requestsIn.size(), mod.menuKeyName()))
                            .formatted(ChatFormatting.YELLOW),
                    4, y, 0xFFFFFF);
        }

        if (mod.controlMode()) {
            ctx.drawCenteredTextWithShadow(mc.textRenderer,
                    Component.literal(Lang.t("hud.control")).formatted(ChatFormatting.GREEN),
                    ctx.getScaledWindowWidth() / 2, ctx.getScaledWindowHeight() - 44, 0xFFFFFF);
        } else if (mod.isDragging()) {
            ctx.drawCenteredTextWithShadow(mc.textRenderer,
                    Component.literal(Lang.t("hud.carrying")).formatted(ChatFormatting.GREEN),
                    ctx.getScaledWindowWidth() / 2, ctx.getScaledWindowHeight() - 44, 0xFFFFFF);
        }

        ScreenWorld.Hit hit = mod.currentHit();
        if (hit == null) {
            return;
        }
        PlacedScreen screen = hit.screen();
        StreamInfo info = mod.state().streams.get(screen.streamId);
        String title = info != null ? info.displayName() : screen.browser.pageTitle();
        if (title == null || title.isBlank()) {
            title = Lang.t("ctl.screen");
        }
        String volume = screen.muted ? Lang.t("ctl.sound.off") : Lang.t("ctl.sound", screen.volume);
        String hints = mod.controlMode()
                ? Lang.t("hud.hints.control")
                : Lang.t("hud.hints", mod.controlKeyName(), mod.browserKeyName(),
                        mod.resizeKeyName(), mod.volumeKeyName(), volume);

        int cx = ctx.getScaledWindowWidth() / 2;
        int cy = ctx.getScaledWindowHeight() - 60;
        ctx.drawCenteredTextWithShadow(mc.textRenderer,
                Component.literal(title).formatted(ChatFormatting.WHITE), cx, cy, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(mc.textRenderer,
                Component.literal(hints).formatted(ChatFormatting.GRAY), cx, cy + 11, 0xFFFFFF);
    }
}
