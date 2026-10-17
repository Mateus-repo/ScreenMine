package dev.screenmine.gui;

import dev.screenmine.Cfg;
import dev.screenmine.Lang;
import dev.screenmine.ScreenMineClient;
import dev.screenmine.net.Packets;
import dev.screenmine.state.StreamInfo;
import dev.screenmine.world.PlacedScreen;
import dev.screenmine.world.ScreenWorld;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The mini menu — the mod's hub. Bound to M by default. */
public final class MenuMain extends Screen {

    private static final int W = 210;
    private static final int H = 20;
    private static final int GAP = 4;
    /** Screens listed per page in the main menu. */
    private static final int ROWS = 4;
    /** Longest label before it is cut, so a long title cannot spill the column. */
    private static final int MAX_LABEL = 38;

    private int screenPage;

    public MenuMain() {
        super(Component.literal("ScreenMine"));
    }

    /**
     * What a placed screen is called in the list.
     *
     * <p>Falls back through the stream title, the page title and the address,
     * because a freshly spawned screen has none of the first two and two
     * screens can easily be on the same page with the same title.
     */
    private static String labelOf(ScreenMineClient mod, PlacedScreen screen) {
        StreamInfo info = mod.state().streams.get(screen.streamId);
        String name = info != null ? info.displayName() : screen.browser.pageTitle();
        if (name == null || name.isBlank()) {
            name = shortUrl(screen.browser.url());
        }
        return name.isBlank() ? Lang.t("ctl.screen") : name;
    }

    private static String shortUrl(String url) {
        if (url == null) {
            return "";
        }
        String bare = url.replaceFirst("^https?://(www\\.)?", "");
        return bare;
    }

    private static String trim(String label) {
        return label.length() > MAX_LABEL ? label.substring(0, MAX_LABEL - 1) + "…" : label;
    }

    @Override
    protected void init() {
        ScreenMineClient mod = ScreenMineClient.get();
        int x = (width - W) / 2;

        // Every screen you have placed, each with its own controls. A single
        // "Screen controls" button cannot work past one screen: it always acted
        // on whatever the crosshair happened to be on when the menu opened, so
        // with two screens there was no way to say which one you meant and no
        // way to tell them apart. The one under the crosshair is marked.
        List<PlacedScreen> mine = new ArrayList<>(mod.world().all());
        int pages = Math.max(1, (mine.size() + ROWS - 1) / ROWS);
        screenPage = Math.min(screenPage, pages - 1);
        int shown = Math.min(mine.size(), (screenPage + 1) * ROWS) - screenPage * ROWS;
        ScreenWorld.Hit hit = mod.currentHit();

        // Lay the column out from the real row count so it stays centred
        // instead of drifting off the bottom as rows are added.
        int rows = 1                       // spawn
                + shown                     // one per screen on this page
                + (pages > 1 ? 1 : 0)       // page arrows
                + 1 + 1 + 1                 // streams, friends, invites
                + 1                         // language
                + 1;                        // close
        int y = height / 2 - rows * (H + GAP) / 2;

        add(x, y, Lang.t("menu.spawn"), b -> {
            mod.spawnScreen(Cfg.DEFAULT_URL);
            onClose();
        });
        y += H + GAP;

        // A button, not just the address screenmine:test, because the whole
        // point of that page is to diagnose a keyboard that does not work.
        // Typing its name would need the thing being tested.
        add(x, y, Lang.t("menu.testpage"), b -> {
            mod.spawnScreen(dev.screenmine.browser.TestPage.URL);
            onClose();
        });
        y += H + GAP;

        // Shared screens. The owner sees their own screen the same way everyone
        // else does - through the server's ScreenAdded echo - rather than by
        // making one locally and hoping the two agree. One code path, so there
        // is nothing to keep in step.
        Button share = add(x, y, Lang.t("menu.share"), b ->
            minecraft.gui.setScreen(new UrlEntry("screenmine_mp", url -> {
                if (dev.screenmine.mp.SharedScreensClient.place(minecraft, url)) {
                    minecraft.gui.setScreen(null);
                }
            }, this)));
        y += H + GAP;

        Button unshare = add(x, y, Lang.t("menu.share.remove"), b -> {
            dev.screenmine.mp.SharedScreensClient.removeMine(minecraft);
        });
        unshare.active = dev.screenmine.mp.SharedScreensClient.hasMine(minecraft);
        y += H + GAP;

        if (mine.isEmpty()) {
            Button none = add(x, y, Lang.t("menu.screens.none"), b -> { });
            none.active = false;
            y += H + GAP;
        }
        for (int i = screenPage * ROWS; i < screenPage * ROWS + shown; i++) {
            PlacedScreen screen = mine.get(i);
            boolean aimed = hit != null && hit.screen() == screen;
            String label = labelOf(mod, screen) + (aimed ? Lang.t("menu.screens.aimed") : "");
            add(x, y, trim(label), b -> minecraft.gui.setScreen(new MenuScreenCtl(screen, this)));
            y += H + GAP;
        }
        if (pages > 1) {
            addSmall(x, y, "<", b -> {
                screenPage = Math.max(0, screenPage - 1);
                rebuildWidgets();
            });
            addSmall(x + W - 44, y, ">", b -> {
                screenPage = Math.min(pages - 1, screenPage + 1);
                rebuildWidgets();
            });
            addSmall(x + 48, y, Lang.t("menu.screens.page", screenPage + 1, pages), b -> { });
            y += H + GAP;
        }

        add(x, y, Lang.t("menu.streams", mod.state().streams.size()),
                b -> minecraft.gui.setScreen(new MenuStreams(this)));
        y += H + GAP;

        int pending = mod.state().requestsIn.size();
        Button friends = add(x, y, pending > 0
                        ? Lang.t("menu.friends.pending", mod.state().friends.size(), pending)
                        : Lang.t("menu.friends", mod.state().friends.size()),
                b -> minecraft.gui.setScreen(new MenuFriends(this)));
        friends.active = mod.net().isOnline();
        y += H + GAP;

        int inv = mod.state().invites.size();
        Button invites = add(x, y, inv > 0 ? Lang.t("menu.invites.count", inv) : Lang.t("menu.invites"),
                b -> minecraft.gui.setScreen(new MenuInvites(this)));
        invites.active = inv > 0 && mod.net().isOnline();
        y += H + GAP;

        if (mod.state().inSharedSession() && !mod.state().isHost()) {
            add(x, y, Lang.t("menu.leave", mod.state().sessionHost), b -> {
                mod.net().send(Packets.leave());
                onClose();
            });
            y += H + GAP;
        } else if (mod.state().inSharedSession()) {
            add(x, y, Lang.t("menu.close.session"), b -> {
                mod.net().send(Packets.leave());
                onClose();
            });
            y += H + GAP;
        }

        add(x, y, Lang.t("menu.language", Lang.current().label), b -> {
            Lang.toggle();
            rebuildWidgets();
        });
        y += H + GAP;

        add(x, y, Lang.t("menu.close"), b -> onClose());
    }

    private Button add(int x, int y, String label, Button.OnPress action) {
        Button w = Button.builder(Component.literal(label), action)
                .bounds(x, y, W, H).build();
        addRenderableWidget(w);
        return w;
    }

    /** A narrower button, for the page arrows and the page counter. */
    private Button addSmall(int x, int y, String label, Button.OnPress action) {
        Button w = Button.builder(Component.literal(label), action)
                .bounds(x, y, 40, H).build();
        addRenderableWidget(w);
        return w;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ScreenMineClient mod = ScreenMineClient.get();

        ctx.centeredText(font,
                Component.literal("ScreenMine").withStyle(ChatFormatting.BOLD),
                width / 2, height / 2 - 116, 0xFFFFFF);

        String hints = Lang.t("menu.keys", mod.controlKeyName(), mod.browserKeyName(),
                mod.moveKeyName(), mod.resizeKeyName());
        ctx.centeredText(font,
                Component.literal(hints).withStyle(ChatFormatting.DARK_GRAY),
                width / 2, height - 24, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}