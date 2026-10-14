package dev.screenmine.gui;

import dev.screenmine.Cfg;
import dev.screenmine.ScreenMineClient;
import dev.screenmine.Lang;
import dev.screenmine.net.Packets;
import dev.screenmine.world.ScreenWorld;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/** The mini menu — the mod's hub. Bound to M by default. */
public final class MenuMain extends Screen {

    private static final int W = 210;
    private static final int H = 20;
    private static final int GAP = 4;

    public MenuMain() {
        super(Component.literal("ScreenMine"));
    }

    @Override
    protected void init() {
        ScreenMineClient mod = ScreenMineClient.get();
        int x = (width - W) / 2;
        int y = height / 2 - 96;

        add(x, y, Lang.t("menu.spawn"), b -> {
            mod.spawnScreen(Cfg.DEFAULT_URL);
            close();
        });
        y += H + GAP;

        ScreenWorld.Hit hit = mod.currentHit();
        Button ctl = add(x, y, Lang.t("menu.control"), b -> {
            if (hit != null) {
                client.setScreen(new MenuScreenCtl(hit.screen(), this));
            }
        });
        ctl.active = hit != null;
        y += H + GAP;

        add(x, y, Lang.t("menu.streams", mod.state().streams.size()),
                b -> client.setScreen(new MenuStreams(this)));
        y += H + GAP;

        int pending = mod.state().requestsIn.size();
        Button friends = add(x, y, pending > 0
                        ? Lang.t("menu.friends.pending", mod.state().friends.size(), pending)
                        : Lang.t("menu.friends", mod.state().friends.size()),
                b -> client.setScreen(new MenuFriends(this)));
        friends.active = mod.net().isOnline();
        y += H + GAP;

        int inv = mod.state().invites.size();
        Button invites = add(x, y, inv > 0 ? Lang.t("menu.invites.count", inv) : Lang.t("menu.invites"),
                b -> client.setScreen(new MenuInvites(this)));
        invites.active = inv > 0 && mod.net().isOnline();
        y += H + GAP;

        if (mod.state().inSharedSession() && !mod.state().isHost()) {
            add(x, y, Lang.t("menu.leave", mod.state().sessionHost), b -> {
                mod.net().send(Packets.leave());
                close();
            });
            y += H + GAP;
        } else if (mod.state().inSharedSession()) {
            add(x, y, Lang.t("menu.close.session"), b -> {
                mod.net().send(Packets.leave());
                close();
            });
            y += H + GAP;
        }


        add(x, y, Lang.t("menu.language", Lang.current().label), b -> {
            Lang.toggle();
            clearAndInit();
        });
        y += H + GAP;

        add(x, y, Lang.t("menu.close"), b -> close());
    }

    private Button add(int x, int y, String label, Button.PressAction action) {
        Button w = Button.builder(Component.literal(label), action)
                .dimensions(x, y, W, H).build();
        addDrawableChild(w);
        return w;
    }

    @Override
    public void render(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ScreenMineClient mod = ScreenMineClient.get();

        ctx.drawCenteredTextWithShadow(textRenderer,
                Component.literal("ScreenMine").formatted(ChatFormatting.BOLD),
                width / 2, height / 2 - 116, 0xFFFFFF);

        String hints = Lang.t("menu.keys", mod.controlKeyName(), mod.browserKeyName(),
                mod.moveKeyName(), mod.resizeKeyName());
        ctx.drawCenteredTextWithShadow(textRenderer,
                Component.literal(hints).formatted(ChatFormatting.DARK_GRAY),
                width / 2, height - 24, 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
