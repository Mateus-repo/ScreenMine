package dev.screenmine.render;

import dev.screenmine.ScreenMineClient;
import dev.screenmine.Lang;
import dev.screenmine.state.StreamInfo;
import dev.screenmine.world.PlacedScreen;
import dev.screenmine.world.ScreenWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/** Small on-screen hints: connection state, what you are pointing at, invites. */
public final class Hud {

    public void render(GuiGraphicsExtractor ctx) {
        Minecraft mc = Minecraft.getInstance();
        // 26.3 dropped Options.hudHidden, and there is no replacement flag to read.
        // It is not needed: Fabric's HudElementRegistry only invokes this element while
        // the vanilla HUD is being extracted, so pressing F1 already hides these hints.
        if (mc.player == null || mc.gui.screen() != null) {
            return;
        }
        Font font = mc.font;
        ScreenMineClient mod = ScreenMineClient.get();
        int y = 4;

        if (mod.state().inSharedSession()) {
            String host = mod.state().sessionHost;
            ctx.text(font,
                    Component.literal(Lang.t("hud.session", host, mod.state().sessionMembers.size()))
                            .withStyle(ChatFormatting.AQUA),
                    4, y, 0xFFFFFF, true);
            y += 11;
        }

        if (!mod.state().invites.isEmpty()) {
            ctx.text(font,
                    Component.literal(Lang.t("hud.invite",
                                    String.join(", ", mod.state().invites), mod.menuKeyName()))
                            .withStyle(ChatFormatting.GOLD),
                    4, y, 0xFFFFFF, true);
            y += 11;
        }
        if (!mod.state().requestsIn.isEmpty()) {
            ctx.text(font,
                    Component.literal(Lang.t("hud.requests",
                                    mod.state().requestsIn.size(), mod.menuKeyName()))
                            .withStyle(ChatFormatting.YELLOW),
                    4, y, 0xFFFFFF, true);
        }

        // 26.3 has no drawCenteredTextWithShadow; centeredText always draws with a
        // shadow, so the old look is kept without the extra argument.
        if (mod.controlMode()) {
            ctx.centeredText(font,
                    Component.literal(Lang.t("hud.control")).withStyle(ChatFormatting.GREEN),
                    ctx.guiWidth() / 2, ctx.guiHeight() - 44, 0xFFFFFF);
        } else if (mod.isDragging()) {
            ctx.centeredText(font,
                    Component.literal(Lang.t("hud.carrying")).withStyle(ChatFormatting.GREEN),
                    ctx.guiWidth() / 2, ctx.guiHeight() - 44, 0xFFFFFF);
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

        int cx = ctx.guiWidth() / 2;
        int cy = ctx.guiHeight() - 60;
        ctx.centeredText(font,
                Component.literal(title).withStyle(ChatFormatting.WHITE), cx, cy, 0xFFFFFF);
        ctx.centeredText(font,
                Component.literal(hints).withStyle(ChatFormatting.GRAY), cx, cy + 11, 0xFFFFFF);
    }
}