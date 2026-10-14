package dev.screenmine.gui;

import dev.screenmine.ScreenMineClient;
import dev.screenmine.Lang;
import dev.screenmine.net.Packets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;

/** Friends list, incoming requests and the "add friend" field. */
public final class MenuFriends extends Screen {

    private static final int ROWS = 7;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private EditBox nameField;
    private int page;

    public MenuFriends(Screen parent) {
        super(Component.literal(Lang.t("friends.title")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ScreenMineClient mod = ScreenMineClient.get();
        int cx = width / 2;

        nameField = new EditBox(font, cx - 130, 40, 190, 20,
                Component.literal(Lang.t("friends.name")));
        nameField.setMaxLength(32);
        nameField.setHint(Component.literal(Lang.t("friends.name")).withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(nameField);

        Button addBtn = Button.builder(Component.literal(Lang.t("friends.add")), b -> {
            String name = nameField.getValue().trim();
            if (!name.isEmpty()) {
                mod.net().send(Packets.friendAdd(name));
                nameField.setValue("");
            }
        }).bounds(cx + 66, 40, 64, 20).build();
        addBtn.active = mod.net().isOnline();
        addRenderableWidget(addBtn);

        // incoming requests first, then friends
        rows.clear();
        for (String from : mod.state().requestsIn) {
            rows.add(new Row(from, Row.Kind.REQUEST));
        }
        for (String f : mod.state().friends) {
            rows.add(new Row(f, Row.Kind.FRIEND));
        }

        int pages = Math.max(1, (rows.size() + ROWS - 1) / ROWS);
        page = Math.min(page, pages - 1);

        int y = 70;
        for (int i = page * ROWS; i < Math.min(rows.size(), (page + 1) * ROWS); i++) {
            Row row = rows.get(i);
            if (row.kind() == Row.Kind.REQUEST) {
                addRenderableWidget(Button.builder(Component.literal(Lang.t("friends.accept")),
                        b -> {
                            mod.net().send(Packets.friendAccept(row.name()));
                            refresh();
                        }).bounds(cx + 10, y, 58, 20).build());
                addRenderableWidget(Button.builder(Component.literal(Lang.t("friends.decline")),
                        b -> {
                            mod.net().send(Packets.friendDecline(row.name()));
                            refresh();
                        }).bounds(cx + 72, y, 58, 20).build());
            } else {
                boolean online = mod.state().isFriendOnline(row.name());
                Button invite = Button.builder(Component.literal(Lang.t("friends.invite")),
                        b -> {
                            mod.net().send(Packets.invite(row.name()));
                            ScreenMineClient.chat(Component.literal(Lang.t("friends.invite.sent", row.name())));
                        }).bounds(cx + 10, y, 78, 20).build();
                invite.active = online;
                addRenderableWidget(invite);
                addRenderableWidget(Button.builder(Component.literal(Lang.t("friends.remove")),
                        b -> {
                            mod.net().send(Packets.friendRemove(row.name()));
                            refresh();
                        }).bounds(cx + 92, y, 58, 20).build());
            }
            y += 24;
        }

        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page = Math.max(0, page - 1);
                refresh();
            }).bounds(cx - 130, height - 52, 24, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page = page + 1;
                refresh();
            }).bounds(cx + 106, height - 52, 24, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal(Lang.t("menu.back")),
                b -> onClose()).bounds(cx - 60, height - 28, 120, 20).build());
    }

    private void refresh() {
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ctx.centeredText(font,
                Component.literal(Lang.t("friends.title")).withStyle(ChatFormatting.BOLD), width / 2, 20, 0xFFFFFF);

        ScreenMineClient mod = ScreenMineClient.get();
        int cx = width / 2;
        int y = 76;
        for (int i = page * ROWS; i < Math.min(rows.size(), (page + 1) * ROWS); i++) {
            Row row = rows.get(i);
            ChatFormatting color;
            String suffix;
            if (row.kind() == Row.Kind.REQUEST) {
                color = ChatFormatting.YELLOW;
                suffix = Lang.t("friends.request");
            } else if (mod.state().isFriendOnline(row.name())) {
                Boolean same = mod.state().serverMatch(row.name());
                if (same == null) {
                    color = ChatFormatting.GREEN;
                    suffix = Lang.t("friends.online");
                } else if (same) {
                    color = ChatFormatting.GREEN;
                    suffix = Lang.t("friends.same.server");
                } else {
                    color = ChatFormatting.GOLD;
                    suffix = Lang.t("friends.other.server",
                            dev.screenmine.state.AppState.serverLabel(
                                    mod.state().serverOf.get(row.name())));
                }
            } else {
                color = ChatFormatting.GRAY;
                suffix = Lang.t("friends.offline");
            }
            ctx.text(font,
                    Component.literal(row.name() + suffix).withStyle(color), cx - 130, y, 0xFFFFFF);
            y += 24;
        }

        if (rows.isEmpty()) {
            ctx.centeredText(font,
                    Component.literal(Lang.t("friends.empty"))
                            .withStyle(ChatFormatting.DARK_GRAY),
                    cx, 90, 0xFFFFFF);
        }
        if (!mod.state().requestsOut.isEmpty()) {
            ctx.centeredText(font,
                    Component.literal(Lang.t("friends.sent", String.join(", ", mod.state().requestsOut)))
                            .withStyle(ChatFormatting.DARK_GRAY),
                    cx, height - 68, 0xFFFFFF);
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

    private record Row(String name, Kind kind) {
        enum Kind {REQUEST, FRIEND}
    }
}
