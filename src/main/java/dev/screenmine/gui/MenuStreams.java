package dev.screenmine.gui;

import dev.screenmine.Lang;
import dev.screenmine.ScreenMineClient;
import dev.screenmine.state.StreamInfo;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;

/** Every screen running in the current session, and whether you are watching it. */
public final class MenuStreams extends Screen {

    private final Screen parent;
    private List<StreamInfo> shown = new ArrayList<>();

    public MenuStreams(Screen parent) {
        super(Component.literal(Lang.t("streams.title")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ScreenMineClient mod = ScreenMineClient.get();
        shown = new ArrayList<>(mod.state().streams.values());

        int cx = width / 2;
        int y = 56;
        for (StreamInfo info : shown) {
            boolean attached = mod.world().has(info.id);
            addRenderableWidget(Button.builder(
                    Component.literal(Lang.t(attached ? "streams.detach" : "streams.connect")), b -> {
                        if (attached) {
                            mod.disconnectFrom(info.id);
                        } else {
                            mod.connectTo(info.id);
                        }
                        rebuildWidgets();
                    }).bounds(cx + 10, y, 100, 20).build());

            boolean canDelete = info.owner.equals(mod.state().you) || mod.state().isHost();
            Button del = Button.builder(Component.literal(Lang.t("friends.remove")), b -> {
                mod.removeScreen(info.id);
                rebuildWidgets();
            }).bounds(cx + 114, y, 66, 20).build();
            del.active = canDelete;
            addRenderableWidget(del);
            y += 24;
            if (y > height - 70) {
                break;
            }
        }

        addRenderableWidget(Button.builder(Component.literal(Lang.t("menu.back")),
                b -> onClose()).bounds(cx - 60, height - 28, 120, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ScreenMineClient mod = ScreenMineClient.get();
        ctx.centeredText(font,
                Component.literal(Lang.t("streams.title")).withStyle(ChatFormatting.BOLD), width / 2, 24, 0xFFFFFF);

        int cx = width / 2;
        int y = 62;
        for (StreamInfo info : shown) {
            String state = Lang.t(mod.world().has(info.id) ? "streams.mine" : "streams.not.connected");
            ctx.text(font,
                    Component.literal(info.displayName()).withStyle(ChatFormatting.WHITE),
                    cx - 180, y - 4, 0xFFFFFF);
            ctx.text(font,
                    Component.literal(info.owner + " · " + state).withStyle(ChatFormatting.DARK_GRAY),
                    cx - 180, y + 6, 0xFFFFFF);
            y += 24;
            if (y > height - 70) {
                break;
            }
        }
        if (shown.isEmpty()) {
            ctx.centeredText(font,
                    Component.literal(Lang.t("streams.empty")).withStyle(ChatFormatting.DARK_GRAY),
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
