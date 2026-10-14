package dev.screenmine.gui;

import dev.screenmine.ScreenMineClient;
import dev.screenmine.Lang;
import dev.screenmine.net.Packets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;

/** Incoming "watch together" invitations. */
public final class MenuInvites extends Screen {

    private final Screen parent;
    private List<String> shown = new ArrayList<>();

    public MenuInvites(Screen parent) {
        super(Component.literal(Lang.t("invites.title")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ScreenMineClient mod = ScreenMineClient.get();
        shown = new ArrayList<>(mod.state().invites);

        int cx = width / 2;
        int y = 56;
        for (String host : shown) {
            addDrawableChild(Button.builder(Component.literal(Lang.t("invites.join")), b -> {
                mod.net().send(Packets.inviteAccept(host));
                client.setScreen(new MenuJoinChoice(host, parent));
            }).dimensions(cx + 4, y, 96, 20).build());
            addDrawableChild(Button.builder(Component.literal(Lang.t("friends.decline")), b -> {
                mod.net().send(Packets.inviteDecline(host));
                clearAndInit();
            }).dimensions(cx + 104, y, 76, 20).build());
            y += 24;
        }

        addDrawableChild(Button.builder(Component.literal(Lang.t("menu.back")),
                b -> close()).dimensions(cx - 60, height - 28, 120, 20).build());
    }

    @Override
    public void render(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Component.literal(Lang.t("invites.title")).formatted(ChatFormatting.BOLD), width / 2, 24, 0xFFFFFF);

        ScreenMineClient mod = ScreenMineClient.get();
        int cx = width / 2;
        int y = 58;
        for (String host : shown) {
            ctx.drawTextWithShadow(textRenderer,
                    Component.literal(Lang.t("invites.calls", host)).formatted(ChatFormatting.GOLD),
                    cx - 180, y, 0xFFFFFF);
            Boolean same = mod.state().serverMatch(host);
            String note;
            ChatFormatting noteColor;
            if (same == null) {
                note = Lang.t("invites.unknown.server");
                noteColor = ChatFormatting.DARK_GRAY;
            } else if (same) {
                note = Lang.t("invites.same.server");
                noteColor = ChatFormatting.GREEN;
            } else {
                note = Lang.t("invites.other.server",
                        dev.screenmine.state.AppState.serverLabel(mod.state().serverOf.get(host)));
                noteColor = ChatFormatting.RED;
            }
            ctx.drawTextWithShadow(textRenderer,
                    Component.literal(note).formatted(noteColor), cx - 180, y + 10, 0xFFFFFF);
            y += 24;
        }
        if (shown.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Component.literal(Lang.t("invites.none")).formatted(ChatFormatting.DARK_GRAY),
                    cx, 62, 0xFFFFFF);
        }
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
