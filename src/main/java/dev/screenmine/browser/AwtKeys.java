package dev.screenmine.browser;

import org.cef.browser.CefBrowser;

import java.lang.reflect.Method;

/**
 * Sends a key to Chromium as a real key event, bypassing mcef-modern.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Everything that works today in the embedded browser arrives as a
 * <em>character</em>. Letters, digits and punctuation come through
 * {@code charTyped}. A key that stands for no printable character - Backspace,
 * Delete, the arrows, Home, End, Page Up, Page Down - never does, because SDL3's
 * text input only carries printable characters, where GLFW in 1.21.4 also
 * reported the control ones. Sending {@code \b} as a character does not delete
 * either: verified in game, the field kept its text.
 *
 * <p>So those keys have to travel as keys. mcef-modern is supposed to do that
 * through {@code onKeyPressed}, but the event it builds carries no usable
 * identity: {@code MCEFApiImpl.onKeyPressed} reads {@code KeyEvent.key()},
 * which in 26.3 is an SDL scancode, and its table maps most of them, but the
 * path has never been observed to work for anything.
 *
 * <h2>Why reflection</h2>
 *
 * <p>The obvious call, {@code getCefBrowser().sendKeyEvent(keyEvent)}, does not
 * compile. Verified with javap against jcef-api 146.0.10:
 *
 * <ul>
 *   <li>{@code org.cef.browser.CefBrowser} - the interface {@code getCefBrowser()}
 *       returns - has no {@code sendKeyEvent} at all.</li>
 *   <li>{@code sendKeyEvent(java.awt.event.KeyEvent)} exists only on
 *       {@code CefBrowser_N}, and it is {@code protected final}.</li>
 *   <li>{@code CefBrowser_N} is itself package-private:
 *       {@code abstract class org.cef.browser.CefBrowser_N}, no {@code public}.</li>
 * </ul>
 *
 * <p>Writing a helper in package {@code org.cef.browser} would reach it
 * legally, since {@code protected} includes package access - but that means
 * adding our jar to somebody else's package, and it only works if jcef-api
 * happens to be loaded by the same classloader. Reflection has neither problem
 * and, more importantly, <em>fails loudly</em> instead of silently not working.
 *
 * <h2>What it does when it cannot work</h2>
 *
 * <p>Logs exactly once, with the reason, and returns false. That is the point:
 * the run either shows {@code AWT KEY sent} for Backspace, or it shows why it
 * could not, and either way the answer is in the log rather than on a screen
 * nobody can read.
 */
final class AwtKeys {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("ScreenMine/input");

    /** The AWT id for a key going down, the same one mcef-modern uses. */
    private static final int KEY_PRESSED = 401;

    /** Windows Virtual-Key codes, which is what Chromium expects. */
    private static final int VK_BACK_SPACE = 8;
    private static final int VK_DELETE = 46;
    private static final int VK_LEFT = 37;
    private static final int VK_UP = 38;
    private static final int VK_RIGHT = 39;
    private static final int VK_DOWN = 40;
    private static final int VK_HOME = 36;
    private static final int VK_END = 35;
    private static final int VK_PAGE_UP = 33;
    private static final int VK_PAGE_DOWN = 34;

    private static Method sendKeyEvent;
    private static boolean resolved;
    private static String resolutionError;

    private AwtKeys() {
    }

    /**
     * The Windows key code for a Minecraft key, or 0 when it has none here.
     *
     * <p>Only the keys a character cannot carry. Enter and Tab are absent on
     * purpose: they work already, through the character path, and sending both
     * would submit twice.
     */
    static int virtualKey(int sdlScancode) {
        return switch (sdlScancode) {
            case com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE -> VK_BACK_SPACE;
            case com.mojang.blaze3d.platform.InputConstants.KEY_DELETE -> VK_DELETE;
            case com.mojang.blaze3d.platform.InputConstants.KEY_LEFT -> VK_LEFT;
            case com.mojang.blaze3d.platform.InputConstants.KEY_UP -> VK_UP;
            case com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT -> VK_RIGHT;
            case com.mojang.blaze3d.platform.InputConstants.KEY_DOWN -> VK_DOWN;
            case com.mojang.blaze3d.platform.InputConstants.KEY_HOME -> VK_HOME;
            case com.mojang.blaze3d.platform.InputConstants.KEY_END -> VK_END;
            case com.mojang.blaze3d.platform.InputConstants.KEY_PAGEUP -> VK_PAGE_UP;
            case com.mojang.blaze3d.platform.InputConstants.KEY_PAGEDOWN -> VK_PAGE_DOWN;
            default -> 0;
        };
    }

    /**
     * Sends the key down to Chromium.
     *
     * @param browser the browser to send to
     * @param vk      a Windows Virtual-Key code from {@link #virtualKey}
     * @param mods    the modifiers, already translated to AWT
     * @param name    the key's readable name, for the log
     * @return true when the event was handed to Chromium
     */
    static boolean press(CefBrowser browser, int vk, int mods, String name) {
        if (browser == null || vk == 0) {
            return false;
        }
        Method m = resolve();
        if (m == null) {
            LOG.warn("AWT KEY {} vk={} not sent: {}", name, vk, resolutionError);
            return false;
        }
        try {
            java.awt.event.KeyEvent event = new java.awt.event.KeyEvent(
                    browser.getUIComponent(), KEY_PRESSED, System.currentTimeMillis(),
                    mods, vk, java.awt.event.KeyEvent.CHAR_UNDEFINED,
                    java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN);
            m.invoke(browser, event);
            LOG.info("AWT KEY sent {} vk={}", name, vk);
            return true;
        } catch (Throwable ex) {
            // Throwable on purpose: the failure here is an IllegalAccessError or
            // a NoClassDefFoundError, not an Exception, and an Error would
            // otherwise take the render thread with it.
            LOG.warn("AWT KEY {} vk={} failed: {}", name, vk, describe(ex));
            return false;
        }
    }

    private static Method resolve() {
        if (resolved) {
            return sendKeyEvent;
        }
        resolved = true;
        try {
            Class<?> n = Class.forName("org.cef.browser.CefBrowser_N");
            Method m = n.getDeclaredMethod("sendKeyEvent", java.awt.event.KeyEvent.class);
            m.setAccessible(true);
            sendKeyEvent = m;
            LOG.info("AWT KEY path available: {}", m);
        } catch (Throwable ex) {
            resolutionError = describe(ex);
            LOG.warn("AWT KEY path unavailable: {}", resolutionError);
        }
        return sendKeyEvent;
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