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

    /**
     * Where the "YouTube" button goes.
     *
     * <p>A specific video, and it used to be the home page. The 14:51 run found
     * why that is wrong the expensive way: an owner shared
     * {@code /embed/TzdAY7QJ-fY}, every client loaded the same video, and then
     * somebody pressed this button. Twenty-nine seconds of a working shared
     * screen undone by one line in our own menu, because the home page is the
     * one address where YouTube decides per session what to show.
     */
    private static final String YOUTUBE = Cfg.DEFAULT_SHARE_URL;

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

        addRenderableWidget(Button.builder(Component.literal(Lang.t("ctl.take")), b -> {
            minecraft.gui.setScreen(null);
            if (!ScreenMineClient.get().enterControlMode()) {
                ScreenMineClient.chat(Component.literal(Lang.t("ctl.aim.again")));
            }
        }).bounds(cx - 105, y, 210, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal("YouTube"), b -> {
            mod.navigate(screen.streamId, YOUTUBE);
            onClose();
        }).bounds(cx - 105, y, 210, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal(Lang.t("ctl.playpause")), b -> {
            mod.togglePlayPause(screen.streamId);
        }).bounds(cx - 105, y, 102, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("ctl.browser")), b -> {
            minecraft.gui.setScreen(new BrowserFullScreen(screen));
        }).bounds(cx + 3, y, 102, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal(Lang.t("ctl.back10")), b -> {
            mod.seekBy(screen.streamId, -10);
        }).bounds(cx - 105, y, 102, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("ctl.fwd10")), b -> {
            mod.seekBy(screen.streamId, 10);
        }).bounds(cx + 3, y, 102, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal(Lang.t("ctl.url")), b ->
                minecraft.gui.setScreen(new UrlEntry(screen.browser.url(), url -> {
                    mod.navigate(screen.streamId, url);
                    minecraft.gui.setScreen(null);
                }, this))).bounds(cx - 105, y, 210, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal(Lang.t("ctl.smaller")), b ->
                screen.resizeBy(-0.5f)).bounds(cx - 105, y, 68, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("ctl.move")), b -> {
            screen.moving = !screen.moving;
            minecraft.gui.setScreen(null);
        }).bounds(cx - 33, y, 66, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("ctl.bigger")), b ->
                screen.resizeBy(0.5f)).bounds(cx + 37, y, 68, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal(Lang.t("ctl.quieter")), b -> {
            screen.volumeBy(-Cfg.VOLUME_STEP);
            rebuildWidgets();
        }).bounds(cx - 105, y, 68, 20).build());
        addRenderableWidget(Button.builder(
                Component.literal(Lang.t(screen.muted ? "ctl.unmute" : "ctl.mute")), b -> {
                    screen.muted = !screen.muted;
                    rebuildWidgets();
                }).bounds(cx - 33, y, 66, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Lang.t("ctl.louder")), b -> {
            screen.volumeBy(Cfg.VOLUME_STEP);
            rebuildWidgets();
        }).bounds(cx + 37, y, 68, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal(Lang.t("ctl.detach")), b -> {
            mod.disconnectFrom(screen.streamId);
            minecraft.gui.setScreen(null);
        }).bounds(cx - 105, y, 102, 20).build());

        StreamInfo info = mod.state().streams.get(screen.streamId);
        boolean canDelete = info == null
                            || info.owner.equals(mod.state().you)
                            || mod.state().isHost();
        Button del = Button.builder(Component.literal(Lang.t("ctl.delete")), b -> {
            mod.removeScreen(screen.streamId);
            minecraft.gui.setScreen(null);
        }).bounds(cx + 3, y, 102, 20).build();
        del.active = canDelete;
        addRenderableWidget(del);
        y += 24;

        addRenderableWidget(Button.builder(Component.literal(Lang.t("menu.back")),
                b -> onClose()).bounds(cx - 60, y, 120, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ScreenMineClient mod = ScreenMineClient.get();
        StreamInfo info = mod.state().streams.get(screen.streamId);
        String title = info != null ? info.displayName() : screen.browser.pageTitle();

        ctx.centeredText(font,
                Component.literal(title.isEmpty() ? Lang.t("ctl.screen") : title).withStyle(ChatFormatting.BOLD),
                width / 2, height / 2 - 108, 0xFFFFFF);
        ctx.centeredText(font,
                Component.literal(shortUrl(screen.browser.url())).withStyle(ChatFormatting.DARK_GRAY),
                width / 2, height / 2 - 96, 0xFFFFFF);
        // Two screens can show the same page and therefore carry the same title
        // and address. The id is the only thing that tells them apart, and this
        // panel is where you decide which one you are about to change.
        ctx.centeredText(font,
                Component.literal(screen.streamId).withStyle(ChatFormatting.DARK_GRAY),
                width / 2, height / 2 - 84, 0xFFFFFF);

        String vol = screen.muted ? Lang.t("ctl.sound.off") : Lang.t("ctl.sound", screen.volume);
        String size = Lang.t("ctl.status", screen.width,
                Lang.t(screen.browser.playbackPaused() ? "ctl.paused" : "ctl.playing"),
                vol, Cfg.AUDIO_FAR);
        ctx.centeredText(font,
                Component.literal(size).withStyle(ChatFormatting.GRAY),
                width / 2, height - 30, 0xFFFFFF);
    }

    private static String shortUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.length() > 60 ? url.substring(0, 59) + "…" : url;
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
