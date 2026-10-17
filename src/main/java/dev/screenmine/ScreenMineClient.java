package dev.screenmine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.screenmine.browser.BrowserInstance;
import dev.screenmine.diag.CodecProbe;
import dev.screenmine.gui.MenuMain;
import dev.screenmine.net.NetClient;
import dev.screenmine.net.Packets;
import dev.screenmine.render.Hud;
import dev.screenmine.render.WorldScreenRenderer;
import dev.screenmine.state.AppState;
import dev.screenmine.state.StreamInfo;
import dev.screenmine.world.PlacedScreen;
import dev.screenmine.world.ScreenWorld;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ServerData;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ScreenMineClient implements ClientModInitializer {

    public static final String MOD_ID = "screenmine_mp";
    public static final Logger LOG = LoggerFactory.getLogger("ScreenMine");

    private static ScreenMineClient instance;

    private final NetClient net = new NetClient();
    private final AppState state = new AppState();
    private final ScreenWorld world = new ScreenWorld();
    private final WorldScreenRenderer worldRenderer = new WorldScreenRenderer(world);
    private final Hud hud = new Hud();

    private KeyMapping keyMenu;
    private KeyMapping keyControl;
    private KeyMapping keyBrowser;
    private KeyMapping keyMove;
    private KeyMapping keyBigger;
    private KeyMapping keySmaller;
    private KeyMapping keyPlayPause;
    private KeyMapping keyRemove;
    private KeyMapping keyVolUp;
    private KeyMapping keyVolDown;
    private KeyMapping keyMute;

    private boolean controlMode;
    private ScreenWorld.Hit currentHit;
    private boolean lastLeftDown;
    private boolean lastRightDown;
    private int stateTicker;
    private int audioTicker;
    private int syncTicker;
    private String lastReportedServer = "";
    /** The server id we want the companion server to know about. */
    private String pendingServerId = "";
    private boolean loggedIn;

    /** Last modifier mask the key handler saw, forwarded to CEF's mouse events. */
    private int lastModifiers;

    /** Triple-click-then-grab gesture state. */
    private boolean lastWorldLeftDown;
    private long lastClickAt;
    private int clickStreak;

    public static ScreenMineClient get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;

        keyMenu = bind("menu", InputConstants.KEY_M);
        keyControl = bind("control", InputConstants.KEY_C);
        keyBrowser = bind("browser", InputConstants.KEY_B);
        keyMove = bind("move", InputConstants.KEY_G);
        keyBigger = bind("bigger", InputConstants.KEY_EQUALS);
        keySmaller = bind("smaller", InputConstants.KEY_MINUS);
        keyPlayPause = bind("playpause", InputConstants.KEY_K);
        keyRemove = bind("remove", InputConstants.KEY_DELETE);
        keyVolUp = bind("volup", InputConstants.KEY_RBRACKET);
        keyVolDown = bind("voldown", InputConstants.KEY_LBRACKET);
        keyMute = bind("mute", InputConstants.KEY_N);

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        // TODO(port) 26.3 has no equivalent of 1.21.4's WorldRenderEvents.LAST. END_MAIN is
        // the closest and draws before clouds and weather, so weather can paint over the
        // screen. Accepted regression, verified against the 26.3 event list, not a missing
        // feature. Consequence: on a snowy screen the picture is briefly veiled.
        LevelRenderEvents.END_MAIN.register(worldRenderer::render);
        // 26.3 replaced HudRenderCallback with a HUD element registry. addLast puts us
        // after the vanilla overlays so nothing covers our text.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"),
                (gfx, delta) -> hud.render(gfx));

        CodecProbe.loadSetting();
        BrowserInstance.installCrashLogging();
        // The client half of shared screens. Only makes screens when the player
        // has asked for it with mp.acceptRemote.
        dev.screenmine.mp.SharedScreensClient.register(world);
        // Payload types are NOT registered here. The main entrypoint runs on
        // both sides and registers them; doing it again from the client
        // entrypoint registered every type twice and crashed the game at
        // startup with "Packet type is already registered".

        LOG.info("ScreenMine ready — network {}", Cfg.networkStatus());
    }

    /**
     * 26.3 attaches a binding to a {@link KeyMapping.Category} object instead of a
     * translation key, and there is no String-taking constructor left. The label is
     * derived from the category id as {@code key.category.<namespace>.<path>}, which
     * for {@code screenmine:main} is {@code key.category.screenmine.main} - so the
     * lang files must carry that key and not the old
     * {@code key.categories.screenmine}.
     */
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

    private static KeyMapping bind(String name, int key) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + MOD_ID + "." + name, InputConstants.Type.KEYBOARD, key, CATEGORY));
    }

    // ------------------------------------------------------------- accessors //

    public NetClient net() {
        return net;
    }

    public AppState state() {
        return state;
    }

    public ScreenWorld world() {
        return world;
    }

    public boolean controlMode() {
        return controlMode;
    }

    /**
     * Turns the crosshair control mode on for the screen currently aimed at.
     * Exposed so the screen's own panel can do it too — a keybinding that ends up
     * unbound or fighting another mod should not be the only way in.
     */
    public boolean enterControlMode() {
        if (currentHit == null) {
            return false;
        }
        PlacedScreen aimed = currentHit.screen();
        // Somebody else's shared screen is not driveable. Two reasons, and the
        // second only became true when the playback sync landed.
        //
        // The first is the design: the owner is the one who drives. Moving is
        // already refused by the server, navigation is already held to the
        // allowlist, and driving was the gap.
        //
        // The second is that a viewer who drives it now fights the sync. They
        // seek, the owner's next report arrives two seconds later and drags them
        // back, and that repeats until they let go. Before the sync this was
        // merely impolite; after it it is a loop.
        //
        // isRemote() alone is not the test: the owner's own shared screen is
        // remote too, because it arrives over ScreenAdded like all the others.
        if (aimed.browser.isRemote()
                && !dev.screenmine.mp.SharedScreensClient.isMine(aimed.streamId)) {
            chat(Component.literal(dev.screenmine.Lang.t("chat.share.notyours")));
            return false;
        }
        controlMode = true;
        BrowserInstance b = aimed.browser;
        if (b.raw() != null) {
            b.raw().setFocus(true);
        }
        return true;
    }

    /** Flips control mode for the screen under the crosshair. */
    public void toggleControlMode() {
        if (controlMode) {
            controlMode = false;
            releaseBrowserFocus();
            return;
        }
        if (currentHit == null) {
            chat(Component.literal(Lang.t("chat.aim")).withStyle(ChatFormatting.GRAY));
            return;
        }
        enterControlMode();
    }

    public ScreenWorld.Hit currentHit() {
        return currentHit;
    }

    public String menuKeyName() {
        return keyMenu.getTranslatedKeyMessage().getString();
    }

    public String controlKeyName() {
        return keyControl.getTranslatedKeyMessage().getString();
    }

    public String browserKeyName() {
        return keyBrowser.getTranslatedKeyMessage().getString();
    }

    public String moveKeyName() {
        return keyMove.getTranslatedKeyMessage().getString();
    }

    public String resizeKeyName() {
        return keyBigger.getTranslatedKeyMessage().getString() + "/"
               + keySmaller.getTranslatedKeyMessage().getString();
    }

    public String volumeKeyName() {
        return keyVolDown.getTranslatedKeyMessage().getString() + "/"
               + keyVolUp.getTranslatedKeyMessage().getString();
    }

    /** How many clicks of the grab gesture have landed so far, 0..3. */
    public int clickStreak() {
        return clickStreak;
    }

    public boolean isDragging() {
        return movingScreen() != null;
    }

    // ------------------------------------------------------------------ tick //

    private void onTick(Minecraft mc) {
        if (mc.player == null) {
            if (loggedIn) {
                shutdownSession();
            }
            return;
        }

        if (!loggedIn) {
            loggedIn = true;
            net.start(mc.getUser().getName());
        }

        drainPackets(mc);
        reportServer(mc);
        drainBrowserNavigations();
        followServerClock();
        world.tickMoving(mc);
        // keep the last aimed-at screen while a GUI is open, so menus can act on it
        if (controlMode || mc.gui.screen() == null) {
            currentHit = world.pick(mc);
        }

        handleKeys(mc);
        if (controlMode) {
            tickControlMode(mc);
        } else {
            tickGrabGesture(mc);
        }
        tickAudio(mc);
        pumpAgents();
        reportOwnedStreams();
        // Tells the server where this player's shared videos are, twice a second.
        // Last, so it reports positions the browser updates have just refreshed.
        dev.screenmine.mp.SharedScreensClient.tickSync();
    }

    private void shutdownSession() {
        world.clear();
        state.reset();
        controlMode = false;
        loggedIn = false;
        net.stop();
    }

    // ------------------------------------------------------------------ keys //

    private void handleKeys(Minecraft mc) {
        while (keyMenu.consumeClick()) {
            controlMode = false;
            // Without this the browser keeps keyboard focus behind the menu.
            // Nothing escapes (controlledBrowser() returns null while a screen
            // is open) but it is only released later, in the overlay's onClose.
            releaseBrowserFocus();
            mc.gui.setScreen(new MenuMain());
        }
        // Normally consumed by the input hook, which always fires even when
        // another mod claims the key first; this is the fallback for when the
        // binding is not on a key at all.
        while (keyControl.consumeClick()) {
            toggleControlMode();
        }
        while (keyBrowser.consumeClick()) {
            if (currentHit != null) {
                controlMode = false;
                releaseBrowserFocus();
                mc.gui.setScreen(new dev.screenmine.gui.BrowserFullScreen(currentHit.screen()));
            } else {
                // Say it instead of doing nothing: B is aimed at a screen, so pressing
                // it while looking at grass looks exactly like a broken keybind.
                chat(Component.literal(Lang.t("chat.aim")).withStyle(ChatFormatting.GRAY));
            }
        }
        while (keyMove.consumeClick()) {
            PlacedScreen moving = movingScreen();
            if (moving != null) {
                moving.moving = false;
            } else if (currentHit != null) {
                currentHit.screen().moveDistance = currentHit.distance();
            // If it is a shared screen, tell the server. Otherwise the owner's
            // screen moves for the owner alone and nobody else sees where it
            // went.
            PlacedScreen moved = currentHit.screen();
            if (moved.browser.isRemote()) {
                dev.screenmine.mp.SharedScreensClient.moved(Minecraft.getInstance(),
                        java.util.UUID.fromString(moved.streamId),
                        moved.pos.x, moved.pos.y, moved.pos.z, moved.yaw);
            }
                currentHit.screen().moving = true;
            }
        }
        while (keyBigger.consumeClick()) {
            PlacedScreen target = targetScreen();
            if (target != null) {
                target.resizeBy(0.5f);
            }
        }
        while (keySmaller.consumeClick()) {
            PlacedScreen target = targetScreen();
            if (target != null) {
                target.resizeBy(-0.5f);
            }
        }
        while (keyPlayPause.consumeClick()) {
            PlacedScreen target = targetScreen();
            if (target != null) {
                togglePlayPause(target.streamId);
            }
        }
        while (keyRemove.consumeClick()) {
            PlacedScreen target = targetScreen();
            if (target != null) {
                removeScreen(target.streamId);
            }
        }
        while (keyVolUp.consumeClick()) {
            PlacedScreen target = targetScreen();
            if (target != null) {
                target.volumeBy(Cfg.VOLUME_STEP);
                announceVolume(target);
            }
        }
        while (keyVolDown.consumeClick()) {
            PlacedScreen target = targetScreen();
            if (target != null) {
                target.volumeBy(-Cfg.VOLUME_STEP);
                announceVolume(target);
            }
        }
        while (keyMute.consumeClick()) {
            PlacedScreen target = targetScreen();
            if (target != null) {
                target.muted = !target.muted;
                announceVolume(target);
            }
        }
    }

    private void announceVolume(PlacedScreen screen) {
        chat(Component.literal(screen.muted
                ? Lang.t("chat.muted")
                : Lang.t("chat.volume", screen.volume)).withStyle(ChatFormatting.GRAY));
    }

    private PlacedScreen movingScreen() {
        for (PlacedScreen s : world.all()) {
            if (s.moving) {
                return s;
            }
        }
        return null;
    }

    /** The screen a keybind should act on: the one being moved, else the one aimed at. */
    private PlacedScreen targetScreen() {
        PlacedScreen moving = movingScreen();
        if (moving != null) {
            return moving;
        }
        return currentHit == null ? null : currentHit.screen();
    }

    // ------------------------------------------------------- world input //

    /**
     * Intercepts the wheel and keyboard so the screen can be driven from inside
     * the world, without opening any GUI.
 *
 * <p>Called from the mixins in {@code dev.screenmine.mixin}, which is where SDL
 * delivers the events now. 1.21.4 chained GLFW's window callbacks to steal them;
 * 26.3 replaced GLFW with SDL3, so {@code org.lwjgl.glfw} is gone and there is
 * no public hook. The 26.3 equivalents are {@code KeyboardHandler.keyPress} and
 * {@code charTyped} and {@code MouseHandler.onScroll}, and their signatures
 * were verified with javap before being written down.
 *
     * <p>Each handler decides whether the event belongs to the browser; if not it
 * returns false and the mixin lets it through to the game. The wheel only goes
 * to the browser while control mode is on. The keyboard only does when the page
 * actually has a text field focused — which is what keeps WASD walking the
     * player around while they watch.
     *
     * @return true when the event was consumed by a browser
     */
    public boolean onWorldScroll(double horizontal, double vertical) {
        BrowserInstance b = controlledBrowser();
        if (b == null || b.raw() == null) {
            return false;
        }
        if (horizontal != 0.0) {
            b.raw().onMouseScrolled(currentHit.pixelX(), currentHit.pixelY(),
                    horizontal * Cfg.SCROLL_SPEED);
            return true;
        }
        if (vertical != 0.0) {
            b.raw().onMouseScrolled(currentHit.pixelX(), currentHit.pixelY(),
                    vertical * Cfg.SCROLL_SPEED);
            return true;
        }
        return false;
    }

    public boolean onWorldKey(int action, KeyEvent event) {
        BrowserInstance b = controlledBrowser();
        boolean typing = b != null && b.raw() != null && b.isEditing();
        // Logged because "nothing types in the world" has two causes that look
        // identical from the inside: the event never reached us, or it reached
        // us and we refused it because the page did not say it was editing. The
        // answer is which of these two lines appears.
        LOG.info("WORLD key {} mods={} action={} browser={} editing={}",
                keyName(event), event.modifiers(), action,
                b == null ? "none" : "yes", typing);

        // The control-mode toggle is handled here rather than through
        // KeyMapping.consumeClick(). Vanilla dispatch can silently never reach us
        // when another mod claims the same key first; this callback always
        // fires. Consuming the event keeps the KeyMapping from seeing it too,
        // so the mode cannot toggle twice on one press. While a text field on
        // the page has focus the key is left alone, so it can be typed.
        if (!typing && action == InputConstants.PRESS && event.key() != UNKNOWN_KEY
                && Minecraft.getInstance().gui.screen() == null
                && event.key() == boundKeyCode(keyControl)) {
            toggleControlMode();
            return true;
        }

        if (b == null || b.raw() == null) {
            return false;
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            if (action == InputConstants.PRESS) {
                controlMode = false;
                releaseBrowserFocus();
            }
            return true;   // never let Esc reach the game menu from control mode
        }
        if (!b.isEditing()) {
            // The page does not claim a text field, so this key belongs to the
            // game. That is what keeps WASD walking the player around, and it
            // is also why nothing types in the world until the page reports
            // editing - which is the whole bug A.
            return false;
        }
        lastModifiers = event.modifiers();
        if (b.handleShortcut(event)) {
            return true;
        }
        if (action == InputConstants.RELEASE) {
            b.raw().onKeyReleased(event);
        } else {
            // Same reason as the overlay: these keys never reach the page as
            // characters under SDL3. The key event is tried first, the
            // character is the fallback, and neither replaces the raw event.
            b.sendKeyAsKey(event);
            b.sendControlCharacter(event);
            b.raw().onKeyPressed(event);
        }
        return true;
    }

    public boolean onWorldChar(int codepoint) {
        BrowserInstance b = controlledBrowser();
        if (b == null || b.raw() == null || !b.isEditing()) {
            return false;
        }
        b.raw().onCharTyped(new CharacterEvent(codepoint));
        return true;
    }

    /** Stands for "this binding sits on nothing", which never equals a real key code. */
    private static final int UNKNOWN_KEY = -1;

    /** Readable name for a key, so the log says BACKSPACE instead of 271. */
    private static String keyName(KeyEvent event) {
        try {
            return InputConstants.getKey(event).getName();
        } catch (RuntimeException ex) {
            return "raw-" + event.key();
        }
    }

    /** The physical key a binding currently sits on, or {@link #UNKNOWN_KEY}. */
    private static int boundKeyCode(KeyMapping binding) {
        InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(binding);
        // Mouse buttons report 0..2, which are not valid keyboard codes, so a plain
        // value comparison is enough here.
        return bound == null ? UNKNOWN_KEY : bound.getValue();
    }

    /** The browser that should receive raw input right now, or null. */
    private BrowserInstance controlledBrowser() {
        if (!controlMode || currentHit == null) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) {
            return null;
        }
        return currentHit.screen().browser;
    }

    // ------------------------------------------------------- grab gesture //

    /**
     * Three clicks on a screen within one second arm the gesture; the fourth
     * click grabs it, and from then on the screen follows the crosshair until
     * the next click drops it.
     */
    private void tickGrabGesture(Minecraft mc) {
        if (mc.gui.screen() != null) {
            lastWorldLeftDown = false;
            return;
        }
        // 26.3 dropped GLFW, so the window handle is gone. MouseHandler already tracks the
        // button state for us, which is a cleaner source than polling the platform.
        boolean down = mc.mouseHandler.isLeftPressed();
        boolean rising = down && !lastWorldLeftDown;
        lastWorldLeftDown = down;

        PlacedScreen dragging = movingScreen();
        if (dragging != null) {
            // while carrying a screen the mouse belongs to us, not to mining
            mc.options.keyAttack.setDown(false);
            if (rising) {
                dragging.moving = false;
                clickStreak = 0;
            }
            return;
        }

        if (currentHit == null) {
            if (rising) {
                clickStreak = 0;
            }
            return;
        }
        // aiming at a screen: swallow the swing so clicking it does not punch
        mc.options.keyAttack.setDown(false);
        if (!rising) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastClickAt > Cfg.MULTICLICK_WINDOW_MS) {
            clickStreak = 0;
        }
        lastClickAt = now;
        clickStreak++;

        if (clickStreak >= Cfg.GRAB_CLICKS) {
            PlacedScreen screen = currentHit.screen();
            screen.moveDistance = currentHit.distance();
            screen.moving = true;
            clickStreak = 0;
        }
    }

    // ------------------------------------------------------------- audio //

    /**
     * Gives every placed screen a chance to install its page agent.
     *
     * <p>The overlay has always done this from {@code BrowserFullScreen.init()},
     * but that left the world with no agent at all when the overlay was closed:
     * {@code applyGain} writes {@code window.__smVol} into a page with no
     * interval reading it, so N reported "sound off" in chat and changed nothing.
     * Same for the editing shim and playback tracking. onLoadEnd normally covers
     * this, but it is a race, so the retry has to run wherever the browser lives.
     */
    private void pumpAgents() {
        for (PlacedScreen screen : world.all()) {
            screen.browser.pumpAgent();
        }
    }

    /**
     * Screens are quieter the further away you stand, and follow Minecraft's
     * master volume. Recomputed a few times a second; the browser only gets a
     * JS call when the value actually moved.
     */
    private void tickAudio(Minecraft mc) {
        if (++audioTicker < Cfg.AUDIO_TICKS) {
            return;
        }
        audioTicker = 0;
        if (mc.player == null) {
            return;
        }
        float master = mc.options.getFinalSoundSourceVolume(SoundSource.MASTER);
        Vec3 eye = mc.player.getEyePosition();
        String dim = ScreenWorld.dimensionOf(mc);

        for (PlacedScreen screen : world.all()) {
            double gain = 0.0;
            if (!screen.muted && screen.volume > 0 && screen.dimension.equals(dim)) {
                double d = screen.distanceTo(eye.x, eye.y, eye.z);
                double falloff = 1.0;
                if (d > Cfg.AUDIO_NEAR) {
                    falloff = 1.0 - (d - Cfg.AUDIO_NEAR) / (Cfg.AUDIO_FAR - Cfg.AUDIO_NEAR);
                    falloff = Math.max(0.0, Math.min(1.0, falloff));
                }
                gain = (screen.volume / 100.0) * falloff * master;
            }
            screen.browser.applyGain(gain);
        }
    }

    // ---------------------------------------------------------- control mode //

    private void tickControlMode(Minecraft mc) {
        if (currentHit == null || mc.gui.screen() != null) {
            return;
        }
        // best effort: keep vanilla from mining/placing while we drive the browser
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);

        BrowserInstance browser = currentHit.screen().browser;
        if (browser.raw() == null) {
            return;
        }
        int px = currentHit.pixelX();
        int py = currentHit.pixelY();
        browser.raw().onMouseMoved(px, py);

        boolean left = mc.mouseHandler.isLeftPressed();
        boolean right = mc.mouseHandler.isRightPressed();

        // mcef-modern takes vanilla input records, exactly like the overlay does, so
        // the same events reach CEF without the old raw sendMouse* API.
        if (left != lastLeftDown) {
            if (left) {
                browser.raw().onMouseClicked(leftClick(px, py), true);
                browser.raw().setFocus(true);
            } else {
                browser.raw().onMouseReleased(leftClick(px, py));
            }
            lastLeftDown = left;
        }
        if (right != lastRightDown) {
            if (right) {
                browser.raw().onMouseClicked(rightClick(px, py), true);
            } else {
                browser.raw().onMouseReleased(rightClick(px, py));
            }
            lastRightDown = right;
        }
    }

    /** A left-button mouse event at the given pixel, carrying the live modifiers. */
    private MouseButtonEvent leftClick(int x, int y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(0, lastModifiers));
    }

    /** A right-button mouse event at the given pixel, carrying the live modifiers. */
    private MouseButtonEvent rightClick(int x, int y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(1, lastModifiers));
    }

    private void releaseBrowserFocus() {
        lastLeftDown = false;
        lastRightDown = false;
        for (PlacedScreen s : world.all()) {
            if (s.browser.raw() != null) {
                s.browser.raw().setFocus(false);
            }
        }
    }

    // -------------------------------------------------------------- commands //

    /** Screens whose id starts with this live only on this client. */
    public static final String LOCAL_PREFIX = "local-";

    public static boolean isLocal(String streamId) {
        return streamId != null && streamId.startsWith(LOCAL_PREFIX);
    }

    /**
     * Spawns a screen. With the server up it becomes a shared stream everyone in
     * the session can see; without it you still get a perfectly good screen, it
     * is just yours alone.
     */
    public void spawnScreen(String url) {
        String target = url == null || url.isBlank() ? Cfg.DEFAULT_URL : url;
        if (net.isOnline()) {
            net.send(Packets.streamNew(target));
            return;
        }
        String id = LOCAL_PREFIX + Long.toHexString(System.nanoTime());
        state.streams.put(id, new StreamInfo(id, state.you, target));
        world.attach(id, target);
    }

    /** Shows someone else's stream on our own wall. */
    public void connectTo(String streamId) {
        StreamInfo info = state.streams.get(streamId);
        if (info == null) {
            return;
        }
        PlacedScreen screen = world.attach(streamId, info.url);
        if (screen != null) {
            if (info.url != null && !info.url.isBlank()
                    && !BrowserInstance.sameVideo(screen.browser.url(), info.url)) {
                screen.browser.navigate(info.url, true);
            }
            chat(Component.literal(Lang.t("chat.attached", info.displayName()))
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    /** Stops showing a stream locally, without touching the other viewers. */
    public void disconnectFrom(String streamId) {
        world.detach(streamId);
    }

    /** Removes the stream for everyone (only the owner or the host may). */
    public void removeScreen(String streamId) {
        StreamInfo info = state.streams.get(streamId);
        world.detach(streamId);
        if (info != null && (info.owner.equals(state.you) || state.isHost())) {
            net.send(Packets.streamRemove(streamId));
        }
    }

    public void togglePlayPause(String streamId) {
        PlacedScreen screen = world.get(streamId);
        if (screen == null) {
            return;
        }
        boolean nowPaused = screen.browser.playbackPaused();
        net.send(Packets.ctl(streamId, nowPaused ? "play" : "pause"));
        // apply locally right away for responsiveness; the echo is idempotent
        screen.browser.setPaused(!nowPaused);
    }

    public void seekBy(String streamId, double seconds) {
        net.send(Packets.ctl(streamId, "seek_by", seconds));
        PlacedScreen screen = world.get(streamId);
        if (screen != null) {
            screen.browser.seekBy(seconds);
        }
    }

    public void navigate(String streamId, String url) {
        net.send(Packets.ctl(streamId, "navigate", url));
        PlacedScreen screen = world.get(streamId);
        // A shared screen also goes out over the Minecraft channel. Without this
        // the owner changes the video and nobody else finds out: the legacy
        // packet above goes to a socket this build never opens, and the
        // multiplayer build has no working companion server to receive it.
        // Found by reading the code, not by a log - the owner navigating their
        // own shared screen produced no line anywhere.
        if (screen != null && screen.browser.isRemote()) {
            dev.screenmine.mp.SharedScreensClient.navigated(streamId, url);
        }
        if (screen != null) {
            screen.browser.navigate(url, true);
        }
    }

    /** Called when the page navigated on its own (link click, search, …). */
    public void onLocalNavigate(String streamId, String url) {
        StreamInfo info = state.streams.get(streamId);
        if (info == null || url == null || url.isBlank()
                || BrowserInstance.sameVideo(url, info.url)) {
            return;
        }
        info.url = url;
        net.send(Packets.ctl(streamId, "navigate", url));
    }

    /**
     * The server owns the playback clock; every client just follows it.
     *
     * <p>Runs a few times a second instead of only when a packet lands, so a
     * screen that drifts between broadcasts still gets pulled back.
     */
    private void followServerClock() {
        if (++syncTicker < Cfg.SYNC_TICKS) {
            return;
        }
        syncTicker = 0;
        for (PlacedScreen s : world.all()) {
            StreamInfo info = state.streams.get(s.streamId);
            if (info == null || !info.sync.known() || !s.browser.hasVideo()) {
                continue;
            }
            if (info.owner.equals(state.you)) {
                // We are the reference for this stream: our page drives the
                // server clock, so following it back would be a feedback loop.
                continue;
            }
            if (s.browser.isAd()) {
                continue;   // the page is showing an ad; its clock means nothing
            }
            boolean wantPaused = info.sync.paused();
            if (s.browser.playbackPaused() != wantPaused) {
                s.browser.setPaused(wantPaused);
            }
            double seekTo = info.sync.correction(s.browser.playbackTime());
            if (seekTo >= 0.0) {
                s.browser.seekTo(seekTo);
            }
        }
    }

    /** Server clock update for one stream. */
    private void onTimeline(JsonObject p) {
        StreamInfo info = state.streams.get(p.get("id").getAsString());
        if (info == null) {
            return;
        }
        double pos = p.has("pos") ? p.get("pos").getAsDouble() : 0.0;
        boolean paused = p.has("paused") && p.get("paused").getAsBoolean();
        info.time = pos;
        info.paused = paused;
        info.sync.onTimeline(pos, paused);
    }

    /** Applies navigations the pages reported on the CEF thread. */
    private void drainBrowserNavigations() {
        for (PlacedScreen s : world.all()) {
            String pending = s.browser.consumePendingNavigate();
            if (pending != null) {
                onLocalNavigate(s.streamId, pending);
            }
        }
    }

    private void reportOwnedStreams() {
        if (!net.isOnline()) {
            return;
        }
        if (++stateTicker < Cfg.STATE_REPORT_TICKS) {
            return;
        }
        stateTicker = 0;
        for (PlacedScreen s : world.all()) {
            StreamInfo info = state.streams.get(s.streamId);
            if (info == null) {
                continue;
            }
            String bUrl = s.browser.url();
            if (bUrl != null && !bUrl.isBlank()
                    && !BrowserInstance.sameVideo(bUrl, info.url)
                    && (bUrl.contains("/embed/") || bUrl.contains("watch?v="))) {
                onLocalNavigate(s.streamId, bUrl);
            }
            boolean isAd = s.browser.isAd();
            if (info.owner.equals(state.you)) {
                if (s.browser.hasVideo() || isAd) {
                    net.send(Packets.state(s.streamId, s.browser.playbackTime(),
                            s.browser.playbackPaused(), s.browser.pageTitle(), isAd, bUrl));
                }
            } else if (isAd) {
                net.send(Packets.state(s.streamId, 0, true, s.browser.pageTitle(), true, bUrl));
            }
        }
    }

    // --------------------------------------------------------------- packets //

    private void drainPackets(Minecraft mc) {
        JsonObject p;
        while ((p = net.poll()) != null) {
            try {
                handlePacket(p);
            } catch (Exception ex) {
                LOG.warn("failed to handle packet {}: {}", p, ex.toString());
            }
        }
    }

    private void handlePacket(JsonObject p) {
        String t = p.has("t") ? p.get("t").getAsString() : "";
        switch (t) {
            case "__disconnected" -> {
                lastReportedServer = "";
                state.serverOf.clear();
                // shared screens die with the connection; solo ones stay
                for (String id : new ArrayList<>(state.streams.keySet())) {
                    if (!isLocal(id)) {
                        state.streams.remove(id);
                        world.detach(id);
                    }
                }
                state.sessionHost = "";
                state.sessionMembers.clear();
                // control mode is purely local: losing the server must not kick
                // the player out of it
            }
            case "welcome" -> {
                state.you = p.get("you").getAsString();
                chat(Component.literal(Lang.t("chat.connected", state.you)).withStyle(ChatFormatting.GREEN));
            }
            case "friends" -> {
                copyServers(p);
                copy(p, "friends", state.friends);
                copy(p, "online", state.friendsOnline);
                copy(p, "pending_in", state.requestsIn);
                copy(p, "pending_out", state.requestsOut);
                copy(p, "invites", state.invites);
            }
            case "friend_request" -> chat(Component.literal(Lang.t("chat.friend.request",
                    p.get("from").getAsString(), menuKeyName())).withStyle(ChatFormatting.YELLOW));
            case "invite" -> {
                String from = p.get("from").getAsString();
                if (p.has("server")) {
                    state.serverOf.put(from, p.get("server").getAsString());
                }
                chat(Component.literal(Lang.t("chat.invite", from, menuKeyName()))
                        .withStyle(ChatFormatting.GOLD));
                if (p.has("same_server") && !p.get("same_server").getAsBoolean()) {
                    chat(Component.literal(Lang.t("chat.invite.servers"))
                            .withStyle(ChatFormatting.YELLOW));
                }
            }
            case "session" -> {
                copyServers(p);
                onSession(p);
            }
            case "stream_new" -> onStreamNew(p.getAsJsonObject("stream"));
            case "stream_remove" -> {
                String id = p.get("id").getAsString();
                state.streams.remove(id);
                world.detach(id);
            }
            case "timeline" -> onTimeline(p);
            case "stream_ctl" -> onStreamCtl(p);
            case "stream_state" -> onStreamState(p);
            case "info" -> chat(Component.literal(p.get("msg").getAsString()).withStyle(ChatFormatting.AQUA));
            case "error" -> chat(Component.literal(p.get("msg").getAsString()).withStyle(ChatFormatting.RED));
            default -> LOG.debug("unhandled packet {}", t);
        }
    }

    private void onSession(JsonObject p) {
        state.sessionHost = p.get("host").getAsString();
        copy(p, "members", state.sessionMembers);

        List<String> seen = new ArrayList<>();
        if (p.has("streams")) {
            for (JsonElement el : p.getAsJsonArray("streams")) {
                StreamInfo info = StreamInfo.fromJson(el.getAsJsonObject());
                state.streams.put(info.id, info);
                seen.add(info.id);
                if (!world.has(info.id)) {
                    world.attach(info.id, info.url);
                }
            }
        }
        state.streams.keySet().removeIf(id -> !seen.contains(id) && !isLocal(id));
        for (String id : new ArrayList<>(world.all().stream().map(s -> s.streamId).toList())) {
            if (!seen.contains(id) && !isLocal(id)) {
                world.detach(id);
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof dev.screenmine.gui.MenuJoinChoice menu) {
            menu.refresh();
        }
    }

    private void onStreamNew(JsonObject o) {
        StreamInfo info = StreamInfo.fromJson(o);
        state.streams.put(info.id, info);
        world.attach(info.id, info.url);
        if (!info.owner.equals(state.you)) {
            chat(Component.literal(Lang.t("chat.stream.new", info.owner)).withStyle(ChatFormatting.AQUA));
        }
    }

    private void onStreamCtl(JsonObject p) {
        String id = p.get("id").getAsString();
        String action = p.get("a").getAsString();
        StreamInfo info = state.streams.get(id);
        PlacedScreen screen = world.get(id);

        if (info != null) {
            if (p.has("paused")) {
                info.paused = p.get("paused").getAsBoolean();
            }
            if (p.has("time")) {
                info.time = p.get("time").getAsDouble();
            }
            if (p.has("time") && p.has("paused")) {
                info.sync.onTimeline(info.time, info.paused);
            }
            if ("navigate".equals(action) && p.has("v")) {
                info.url = p.get("v").getAsString();
            }
        }
        if (screen == null) {
            return;
        }
        boolean fromMe = p.has("by") && p.get("by").getAsString().equals(state.you);
        if (fromMe) {
            return;   // already applied locally
        }
        BrowserInstance b = screen.browser;
        switch (action) {
            case "play" -> b.play();
            case "pause" -> b.pause();
            case "seek" -> b.seekTo(p.get("v").getAsDouble());
            case "seek_by" -> b.seekBy(p.get("v").getAsDouble());
            case "navigate" -> {
                b.navigate(p.get("v").getAsString(), true);
                if (info != null) {
                    info.sync.reset();
                }
            }
            default -> {
                // nothing else is broadcast
            }
        }
        if (p.has("by")) {
            chat(Component.literal(p.get("by").getAsString() + ": " + humanAction(action))
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
    }

    private static String humanAction(String action) {
        return switch (action) {
            case "play" -> Lang.t("chat.action.play");
            case "pause" -> Lang.t("chat.action.pause");
            case "seek", "seek_by" -> Lang.t("chat.action.seek");
            case "navigate" -> Lang.t("chat.action.navigate");
            default -> action;
        };
    }

    private void onStreamState(JsonObject p) {
        String id = p.get("id").getAsString();
        StreamInfo info = state.streams.get(id);
        double time = p.get("time").getAsDouble();
        boolean paused = p.get("paused").getAsBoolean();
        if (info != null) {
            info.time = time;
            info.paused = paused;
            if (p.has("title")) {
                info.title = p.get("title").getAsString();
            }
            if (p.has("url")) {
                String remoteUrl = p.get("url").getAsString();
                if (remoteUrl != null && !remoteUrl.isBlank()) {
                    info.url = remoteUrl;
                }
            }
        }
        PlacedScreen screen = world.get(id);
        if (screen == null) {
            return;
        }
        if (info != null && info.url != null && !info.url.isBlank()
                && !BrowserInstance.sameVideo(screen.browser.url(), info.url)) {
            screen.browser.navigate(info.url, true);
        }
    }

    /** Where each online friend / session member is playing right now. */
    private void copyServers(JsonObject p) {
        if (!p.has("servers")) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : p.getAsJsonObject("servers").entrySet()) {
            state.serverOf.put(e.getKey(), e.getValue().getAsString());
        }
    }

    /**
     * The Minecraft server this client is on, used to tell friends whether joining
     * would actually put them in the same world. Single-player ids are made unique
     * per player, since two single-player worlds are never the same place.
     */
    public static String currentServerId(Minecraft mc) {
        ServerData info = mc.getCurrentServer();
        if (info != null && info.ip != null && !info.ip.isBlank()) {
            return info.ip.toLowerCase(java.util.Locale.ROOT);
        }
        return "single:" + mc.getUser().getName();
    }

    /**
     * Tells the server which Minecraft server we are on.
     *
     * <p>This used to mark the id as reported before the packet had anywhere to
     * go. {@link NetClient#send} drops anything sent while the status is not
     * ONLINE, and on joining a world the status is CONNECTING - so the very first
     * packet was discarded, {@code lastReportedServer} already said it had been
     * sent, and the server never learned where we were until the player moved to
     * a different server. The friends and invites screens then showed "unknown"
     * for good.
     *
     * <p>Two ids now: the one we want reported, and the one that actually was.
     * The first is kept across disconnects so a reconnect re-sends it; the
     * second is cleared when the connection drops.
     */
    private void reportServer(Minecraft mc) {
        String id = currentServerId(mc);
        if (!id.equals(pendingServerId)) {
            pendingServerId = id;
            state.myServer = id;
        }
        if (!net.isOnline() || id.equals(lastReportedServer)) {
            return;
        }
        lastReportedServer = id;
        net.send(Packets.where(id));
    }

    private static void copy(JsonObject p, String key, List<String> target) {
        if (!p.has(key)) {
            return;
        }
        target.clear();
        JsonArray arr = p.getAsJsonArray(key);
        for (JsonElement el : arr) {
            target.add(el.getAsString());
        }
    }

    // ----------------------------------------------------------------- utils //

    public static void chat(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null && mc.gui.hud != null) {
            mc.gui.hud.getChat().addClientSystemMessage(text);
        }
    }
}
