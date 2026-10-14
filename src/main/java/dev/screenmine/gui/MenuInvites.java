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
            addRenderableWidget(Button.builder(Component.literal(Lang.t("invites.join")), b -> {
                mod.net().send(Packets.inviteAccept(host));
                minecraft.gui.setScreen(new MenuJoinChoice(host, parent));
            }).bounds(cx + 4, y, 96, 20).build());
            addRenderableWidget(Button.builder(Component.literal(Lang.t("friends.decline")), b -> {
                mod.net().send(Packets.inviteDecline(host));
                rebuildWidgets();
            }).bounds(cx + 104, y, 76, 20).build());
            y += 24;
        }

        addRenderableWidget(Button.builder(Component.literal(Lang.t("menu.back")),
                b -> onClose()).bounds(cx - 60, height - 28, 120, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ctx.centeredText(font,
                Component.literal(Lang.t("invites.title")).withStyle(ChatFormatting.BOLD), width / 2, 24, 0xFFFFFF);

        ScreenMineClient mod = ScreenMineClient.get();
        int cx = width / 2;
        int y = 58;
        for (String host : shown) {
            ctx.text(font,
                    Component.literal(Lang.t("invites.calls", host)).withStyle(ChatFormatting.GOLD),
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
            ctx.text(font,
                    Component.literal(note).withStyle(noteColor), cx - 180, y + 10, 0xFFFFFF);
            y += 24;
        }
        if (shown.isEmpty()) {
            ctx.centeredText(font,
                    Component.literal(Lang.t("invites.none")).withStyle(ChatFormatting.DARK_GRAY),
                    cx, 62, 0xFFFFFF);
        }
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
