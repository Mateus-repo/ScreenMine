package dev.screenmine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Everything a player can change without recompiling, kept in
 * {@code config/screenmine.properties}.
 *
 * <p>The file is created with a commented template on first launch, so the
 * available keys are discoverable without reading the source. All access goes
 * through here rather than through several classes touching the same file,
 * which is how one of them ends up erasing the other's keys on save.
 *
 * <p>Known keys:
 * <ul>
 *   <li>{@code language} — {@code en} or {@code ru}; written by the menu</li>
 *   <li>{@code server.host} — ScreenMine companion server address. Empty by
 *       default: no address means the mod simply never opens a socket, which is
 *       the normal single-player case</li>
 *   <li>{@code server.port} — companion server port</li>
 * </ul>
 */
public final class Settings {

    private static final Logger LOGGER = LoggerFactory.getLogger("ScreenMine");

    private static final Path FILE = Paths.get("config", "screenmine.properties");

    private static final String TEMPLATE = """
            # ScreenMine settings.
            #
            # Interface language: en or ru. The mini menu writes this for you.
            language=en
            #
            # Companion server, used only for watching together. Leave the host
            # empty and the mod never tries to connect - single player works
            # exactly the same either way.
            server.host=
            server.port=23481
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
            LOGGER.warn("could not read {}: {}", FILE, ex.toString());
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                PROPS.store(out, "ScreenMine settings");
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("could not write {}: {}", FILE, ex.toString());
        }
    }
}
