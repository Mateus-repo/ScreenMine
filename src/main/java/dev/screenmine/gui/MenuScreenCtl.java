package dev.screenmine.gui;

import dev.screenmine.Lang;
import dev.screenmine.Cfg;
import dev.screenmine.ScreenMineClient;
import dev.screenmine.state.StreamInfo;
import dev.screenmine.world.PlacedScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/** Control panel for one screen: YouTube, playback, size, removal. */
public final class MenuScreenCtl extends Screen {

    private static final String YOUTUBE = "https://www.youtube.com";

    private final PlacedScreen screen;
    private final Screen parent;

    public MenuScreenCtl(PlacedScreen screen, Screen parent) {
        super(Component.literal(Lang.t("ctl.screen")));
        this.screen = screen;
        this.parent = parent;
    }

    @Override
    protected void init() {
        ScreenMineClient mod = ScreenMineClient.get();
        int cx = width / 2;
        int y = height / 2 - 84;

        addDrawableChild(Button.builder(Component.literal(Lang.t("ctl.take")), b -> {
            client.setScreen(null);
            if (!ScreenMineClient.get().enterControlMode()) {
                ScreenMineClient.chat(Component.literal(Lang.t("ctl.aim.again")));
            }
        }).dimensions(cx - 105, y, 210, 20).build());
        y += 24;

        addDrawableChild(Button.builder(Component.literal("YouTube"), b -> {
            mod.navigate(screen.streamId, YOUTUBE);
            close();
        }).dimensions(cx - 105, y, 210, 20).build());
        y += 24;

        addDrawableChild(Button.builder(Component.literal(Lang.t("ctl.playpause")), b -> {
            mod.togglePlayPause(screen.streamId);
        }).dimensions(cx - 105, y, 102, 20).build());
        addDrawableChild(Button.builder(Component.literal(Lang.t("ctl.browser")), b -> {
            client.setScreen(new BrowserFullScreen(screen));
        }).dimensions(cx + 3, y, 102, 20).build());
        y += 24;

        addDrawableChild(Button.builder(Component.literal(Lang.t("ctl.back10")), b -> {
            mod.seekBy(screen.streamId, -10);
        }).dimensions(cx - 105, y, 102, 20).build());
        addDrawableChild(Button.builder(Component.literal(Lang.t("ctl.fwd10")), b -> {
            mod.seekBy(screen.streamId, 10);
        }).dimensions(cx + 3, y, 102, 20).build());
        y += 24;

        addDrawableChild(Button.builder(Component.literal(Lang.t("ctl.url")), b ->
                client.setScreen(new UrlEntry(screen.browser.url(), url -> {
                    mod.navigate(screen.streamId, url);
                    client.setScreen(null);
                }, this))).dimensions(cx - 105, y, 210, 20).build());
        y += 24;

        addDrawableChild(Button.builder(Component.literal(Lang.t("ctl.smaller")), b ->
                screen.resizeBy(-0.5f)).dimensions(cx - 105, y, 68, 20).build());
        addDrawableChild(Button.builder(Component.literal(Lang.t("ctl.move")), b -> {
            screen.moving = !screen.moving;
            client.setScreen(null);
        }).dimensions(cx - 33, y, 66, 20).build());
        addDrawableChild(Button.builder(Component.literal(Lang.t("ctl.bigger")), b ->
                screen.resizeBy(0.5f)).dimensions(cx + 37, y, 68, 20).build());
        y += 24;

        addDrawableChild(Button.builder(Component.literal(Lang.t("ctl.quieter")), b -> {
            screen.volumeBy(-Cfg.VOLUME_STEP);
            clearAndInit();
        }).dimensions(cx - 105, y, 68, 20).build());
        addDrawableChild(Button.builder(
                Component.literal(Lang.t(screen.muted ? "ctl.unmute" : "ctl.mute")), b -> {
                    screen.muted = !screen.muted;
                    clearAndInit();
                }).dimensions(cx - 33, y, 66, 20).build());
        addDrawableChild(Button.builder(Component.literal(Lang.t("ctl.louder")), b -> {
            screen.volumeBy(Cfg.VOLUME_STEP);
            clearAndInit();
        }).dimensions(cx + 37, y, 68, 20).build());
        y += 24;

        addDrawableChild(Button.builder(Component.literal(Lang.t("ctl.detach")), b -> {
            mod.disconnectFrom(screen.streamId);
            client.setScreen(null);
        }).dimensions(cx - 105, y, 102, 20).build());

        StreamInfo info = mod.state().streams.get(screen.streamId);
        boolean canDelete = info == null
                            || info.owner.equals(mod.state().you)
                            || mod.state().isHost();
        Button del = Button.builder(Component.literal(Lang.t("ctl.delete")), b -> {
            mod.removeScreen(screen.streamId);
            client.setScreen(null);
        }).dimensions(cx + 3, y, 102, 20).build();
        del.active = canDelete;
        addDrawableChild(del);
        y += 24;

        addDrawableChild(Button.builder(Component.literal(Lang.t("menu.back")),
                b -> close()).dimensions(cx - 60, y, 120, 20).build());
    }

    @Override
    public void render(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ScreenMineClient mod = ScreenMineClient.get();
        StreamInfo info = mod.state().streams.get(screen.streamId);
        String title = info != null ? info.displayName() : screen.browser.pageTitle();

        ctx.drawCenteredTextWithShadow(textRenderer,
                Component.literal(title.isEmpty() ? Lang.t("ctl.screen") : title).formatted(ChatFormatting.BOLD),
                width / 2, height / 2 - 108, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Component.literal(shortUrl(screen.browser.url())).formatted(ChatFormatting.DARK_GRAY),
                width / 2, height / 2 - 96, 0xFFFFFF);

        String vol = screen.muted ? Lang.t("ctl.sound.off") : Lang.t("ctl.sound", screen.volume);
        String size = Lang.t("ctl.status", screen.width,
                Lang.t(screen.browser.playbackPaused() ? "ctl.paused" : "ctl.playing"),
                vol, Cfg.AUDIO_FAR);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Component.literal(size).formatted(ChatFormatting.GRAY),
                width / 2, height - 30, 0xFFFFFF);
    }

    private static String shortUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.length() > 60 ? url.substring(0, 59) + "…" : url;
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
