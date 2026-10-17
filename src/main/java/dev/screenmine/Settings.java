package dev.screenmine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Everything a player can change without recompiling, kept in
 * {@code config/screenmine_mp.properties}.
 *
 * <p>The file is created with a commented template on first launch, so the
 * available keys are discoverable without reading the source. All access goes
 * through here rather than through several classes touching the same file,
 * which is how one of them ends up erasing the other's keys on save.
 *
 * <p>Known keys:
 * <ul>
 *   <li>{@code language} — {@code en} or {@code ru}; written by the menu</li>
 *   <li>{@code network.enabled} — {@code 1} allows the mod to open a socket to
 *       the companion server; {@code 0} (the default) forbids it. Both this
 *       and an address are needed, so an address left in the file from an
 *       earlier session does not quietly bring the network back</li>
 *   <li>{@code server.host} — ScreenMine companion server address. Empty by
 *       default: no address means the mod simply never opens a socket, which is
 *       the normal single-player case</li>
 *   <li>{@code server.port} — companion server port</li>
 *   <li>{@code debug.codecs} — {@code 1} makes the browser report which media
 *       codecs it can decode into the log; {@code 0} (the default) disables it</li>
 * </ul>
 */
public final class Settings {

    private static final Path FILE = Paths.get("config", "screenmine_mp.properties");

    private static final String TEMPLATE = """
            # ScreenMine (Multiplayer) settings.
            #
            # Interface language: en or ru. The mini menu writes this for you.
            language=en
            #
            # Companion server, used only for watching together. Leave the host
            # empty and the mod never tries to connect - single player works
            # exactly the same either way.
            #
            # network.enabled is a separate switch on purpose. Setting 0 here is
            # what guarantees this build never opens a socket, even if a host is
            # still sitting in the file from an earlier session.
            network.enabled=0
            server.host=
            server.port=23481
            #
            # Shared screens. Off by default: with this at 0 the mod ignores
            # screens that other players placed, so joining a server cannot make
            # this machine open a page somebody else chose.
            mp.acceptRemote=0
            #
            # Diagnostics. Set debug.codecs to 1 and load any page to have the
            # browser report which media codecs it can decode, into latest.log
            # under the ScreenMine/codecs logger.
            debug.codecs=0
            """;

    private static final Properties PROPS = new Properties();
    private static boolean loaded;

    private Settings() {
    }

    public static synchronized String get(String key, String fallback) {
        ensureLoaded();
        String value = PROPS.getProperty(key);
        return value == null ? fallback : value.trim();
    }

    public static synchronized int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * A yes/no setting.
     *
     * <p>Anything that is not plainly a yes counts as no. A switch that guards
     * whether the mod opens a socket should fail closed: a typo in the file
     * must not be the thing that turns the network on.
     */
    public static synchronized boolean getBool(String key, boolean fallback) {
        String value = get(key, fallback ? "1" : "0").toLowerCase(java.util.Locale.ROOT);
        if (value.equals("1") || value.equals("true") || value.equals("yes") || value.equals("on")) {
            return true;
        }
        if (value.equals("0") || value.equals("false") || value.equals("no") || value.equals("off")) {
            return false;
        }
        return false;   // unrecognised: closed
    }

    /** Sets a key and rewrites the file, keeping every other key intact. */
    public static synchronized void set(String key, String value) {
        ensureLoaded();
        PROPS.setProperty(key, value);
        save();
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (!Files.exists(FILE)) {
                Files.createDirectories(FILE.getParent());
                Files.writeString(FILE, TEMPLATE);
            }
            try (InputStream in = Files.newInputStream(FILE)) {
                PROPS.load(in);
            }
        } catch (IOException | RuntimeException ex) {
            ScreenMineClient.LOG.warn("could not read {}: {}", FILE, ex.toString());
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                PROPS.store(out, "ScreenMine (Multiplayer) settings");
            }
        } catch (IOException | RuntimeException ex) {
            ScreenMineClient.LOG.warn("could not write {}: {}", FILE, ex.toString());
        }
    }
}
