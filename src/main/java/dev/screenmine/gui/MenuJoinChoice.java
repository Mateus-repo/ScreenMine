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

    /**
     * Public entry point, so the session update can refresh this screen live.
     * {@code rebuildWidgets} itself is protected in 26.3.
     */
    public void refresh() {
        rebuildWidgets();
    }

    @Override
    protected void init() {
        ScreenMineClient mod = ScreenMineClient.get();
        int cx = width / 2;
        int y = height / 2 - 40;

        addRenderableWidget(Button.builder(
                Component.literal(Lang.t("join.spawn")), b -> {
                    mod.spawnScreen(Cfg.DEFAULT_URL);
                    minecraft.gui.setScreen(null);
                }).bounds(cx - 110, y, 220, 20).build());
        y += 24;

        int hooked = 0;
        for (StreamInfo info : mod.state().streams.values()) {
            if (info.owner.equals(mod.state().you)) {
                continue;
            }
            addRenderableWidget(Button.builder(
                    Component.literal(Lang.t("join.connect", info.displayName())), b -> {
                        mod.connectTo(info.id);
                        minecraft.gui.setScreen(null);
                    }).bounds(cx - 110, y, 220, 20).build());
            y += 24;
            hooked++;
            if (hooked >= 5) {
                break;
            }
        }

        addRenderableWidget(Button.builder(Component.literal(Lang.t("invites.later")),
                b -> onClose()).bounds(cx - 60, height - 32, 120, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ctx.centeredText(font,
                Component.literal(Lang.t("join.in.session", host)).withStyle(ChatFormatting.BOLD),
                width / 2, height / 2 - 70, 0xFFFFFF);
        ctx.centeredText(font,
                Component.literal(Lang.t("join.hint"))
                        .withStyle(ChatFormatting.GRAY),
                width / 2, height / 2 - 58, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
