package dev.screenmine.browser;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;

import java.util.concurrent.CompletableFuture;

/**
 * Sends a key to Chromium through the DevTools protocol, with the key code
 * stated outright.
 *
 * <h2>Why this is the last attempt</h2>
 *
 * <p>Two routes were tried before it, and both were measured, not guessed.
 *
 * <p><b>mcef-modern's own path.</b> {@code onKeyPressed} builds an AWT key
 * event and hands it to JCEF. Every keydown the page saw came back with
 * {@code keyCode=0} and {@code key="Unidentified"} - letters included.
 *
 * <p><b>Our own AWT event, by reflection.</b> Reaching the protected
 * {@code sendKeyEvent} needed reflection, because {@code CefBrowser} has no such
 * method and {@code CefBrowser_N} is package-private. The call succeeded - the
 * log says {@code AWT KEY sent} with the right Virtual-Key every time, and
 * nothing was logged as unavailable - and the page still did nothing. Same
 * native path, same result: the key code is lost after the AWT event.
 *
 * <p>So the code has to be stated somewhere the native layer cannot lose it.
 * The DevTools protocol takes it as data:
 *
 * <pre>
 * CefBrowser.getDevToolsClient().executeDevToolsMethod("Input.dispatchKeyEvent", json)
 * </pre>
 *
 * both on the API surface - no reflection, no package tricks - and with
 * {@code windowsVirtualKeyCode} and {@code code} given by us. That is the one
 * thing the two previous attempts did not control.
 *
 * <h2>What it costs</h2>
 *
 * <p>Two asynchronous round trips per key instead of one synchronous call, and a
 * {@code CefDevToolsClient} that may be null if DevTools is not available. Both
 * are handled rather than assumed, and both are logged, so a run that fails here
 * says why instead of going quiet.
 *
 * <p>Enter and Tab are not here. They work through the character path and
 * sending both would submit twice.
 */
final class CdpKeys {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("ScreenMine/input");

    /** One key: its DevTools name, its DOM code, and its Windows Virtual-Key. */
    private record Key(String key, String code, int vk) {
        /** The dispatch arguments. text and unmodifiedText are omitted on
         *  purpose: they belong to character events, and sending them on a raw
         *  key makes the page insert a character instead of acting. */
        String rawKeyDown() {
            return "{\"type\":\"rawKeyDown\",\"windowsVirtualKeyCode\":" + vk
                    + ",\"nativeVirtualKeyCode\":" + vk
                    + ",\"key\":\"" + key
                    + "\",\"code\":\"" + code + "\"}";
        }

        String keyUp() {
            return "{\"type\":\"keyUp\",\"windowsVirtualKeyCode\":" + vk
                    + ",\"nativeVirtualKeyCode\":" + vk
                    + ",\"key\":\"" + key
                    + "\",\"code\":\"" + code + "\"}";
        }
    }

    private static final Key BACKSPACE = new Key("Backspace", "Backspace", 8);
    private static final Key DELETE = new Key("Delete", "Delete", 46);
    private static final Key LEFT = new Key("ArrowLeft", "ArrowLeft", 37);
    private static final Key UP = new Key("ArrowUp", "ArrowUp", 38);
    private static final Key RIGHT = new Key("ArrowRight", "ArrowRight", 39);
    private static final Key DOWN = new Key("ArrowDown", "ArrowDown", 40);
    private static final Key HOME = new Key("Home", "Home", 36);
    private static final Key END = new Key("End", "End", 35);
    private static final Key PAGE_UP = new Key("PageUp", "PageUp", 33);
    private static final Key PAGE_DOWN = new Key("PageDown", "PageDown", 34);

    private CdpKeys() {
    }

    /**
     * The key a Minecraft key stands for, or null when it is not one of ours.
     *
     * <p>Only the keys a character cannot carry.
     */
    static Key key(int sdlScancode) {
        return switch (sdlScancode) {
            case com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE -> BACKSPACE;
            case com.mojang.blaze3d.platform.InputConstants.KEY_DELETE -> DELETE;
            case com.mojang.blaze3d.platform.InputConstants.KEY_LEFT -> LEFT;
            case com.mojang.blaze3d.platform.InputConstants.KEY_UP -> UP;
            case com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT -> RIGHT;
            case com.mojang.blaze3d.platform.InputConstants.KEY_DOWN -> DOWN;
            case com.mojang.blaze3d.platform.InputConstants.KEY_HOME -> HOME;
            case com.mojang.blaze3d.platform.InputConstants.KEY_END -> END;
            case com.mojang.blaze3d.platform.InputConstants.KEY_PAGEUP -> PAGE_UP;
            case com.mojang.blaze3d.platform.InputConstants.KEY_PAGEDOWN -> PAGE_DOWN;
            default -> null;
        };
    }

    /**
     * Sends the key down and up through DevTools.
     *
     * @return true when both events were dispatched
     */
    static boolean press(CefBrowser browser, Key k) {
        if (browser == null || k == null) {
            return false;
        }
        CefDevToolsClient dev = browser.getDevToolsClient();
        if (dev == null) {
            LOG.warn("CDP KEY {} not sent: getDevToolsClient() returned null", k.key());
            return false;
        }
        try {
            dispatch(dev, k.rawKeyDown(), k);
            dispatch(dev, k.keyUp(), k);
            LOG.info("CDP KEY sent {} vk={}", k.key(), k.vk());
            return true;
        } catch (Throwable ex) {
            // Throwable on purpose: this reaches JNI, and a failing native call
            // raises an Error, which would otherwise take the render thread.
            LOG.warn("CDP KEY {} failed: {}", k.key(), describe(ex));
            return false;
        }
    }

    private static void dispatch(CefDevToolsClient dev, String params, Key k) {
        CompletableFuture<String> result = dev.executeDevToolsMethod("Input.dispatchKeyEvent", params);
        if (result == null) {
            LOG.warn("CDP {} for {} returned no future", "dispatch", k.key());
            return;
        }
        // Not joined: the render thread must not wait on a native round trip.
        // The result is logged because a refused dispatch is the difference
        // between 'the key did nothing' and 'the key was refused', and only one
        // of those is worth chasing.
        result.thenAccept(json -> {
            if (json != null && !json.isBlank() && !"{}".equals(json.trim())) {
                LOG.warn("CDP {} for {} answered {}", "dispatch", k.key(), json);
            }
        }).exceptionally(error -> {
            LOG.warn("CDP {} for {} failed: {}", "dispatch", k.key(), describe(error));
            return null;
        });
    }

    private static String describe(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getName()
                + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}