package dev.screenmine.gui;

import dev.screenmine.Cfg;
import dev.screenmine.ScreenMineClient;
import dev.screenmine.Lang;
import dev.screenmine.state.StreamInfo;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * Shown right after accepting an invite: spawn your own screen in the shared
 * session, or hook up to a screen that is already running.
 */
public final class MenuJoinChoice extends Screen {

    private final String host;
    private final Screen parent;

    public MenuJoinChoice(String host, Screen parent) {
        super(Component.literal(Lang.t("join.in.session", host)));
        this.host = host;
        this.parent = parent;
    }

    /** Widened so the session update can refresh this screen live. */
    @Override
    public void clearAndInit() {
        super.clearAndInit();
    }

    @Override
    protected void init() {
        ScreenMineClient mod = ScreenMineClient.get();
        int cx = width / 2;
        int y = height / 2 - 40;

        addDrawableChild(Button.builder(
                Component.literal(Lang.t("join.spawn")), b -> {
                    mod.spawnScreen(Cfg.DEFAULT_URL);
                    client.setScreen(null);
                }).dimensions(cx - 110, y, 220, 20).build());
        y += 24;

        int hooked = 0;
        for (StreamInfo info : mod.state().streams.values()) {
            if (info.owner.equals(mod.state().you)) {
                continue;
            }
            addDrawableChild(Button.builder(
                    Component.literal(Lang.t("join.connect", info.displayName())), b -> {
                        mod.connectTo(info.id);
                        client.setScreen(null);
                    }).dimensions(cx - 110, y, 220, 20).build());
            y += 24;
            hooked++;
            if (hooked >= 5) {
                break;
            }
        }

        addDrawableChild(Button.builder(Component.literal(Lang.t("invites.later")),
                b -> close()).dimensions(cx - 60, height - 32, 120, 20).build());
    }

    @Override
    public void render(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Component.literal(Lang.t("join.in.session", host)).formatted(ChatFormatting.BOLD),
                width / 2, height / 2 - 70, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Component.literal(Lang.t("join.hint"))
                        .formatted(ChatFormatting.GRAY),
                width / 2, height / 2 - 58, 0xFFFFFF);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
