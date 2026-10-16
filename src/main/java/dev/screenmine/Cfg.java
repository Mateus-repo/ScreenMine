package dev.screenmine;

/**
 * Compile-time configuration.
 *
 * <p>The one thing that is deliberately not compiled in is the companion
 * server's address: it belongs to whoever runs the server, not to this source
 * tree, so it is read from {@code config/screenmine.properties} instead. With no
 * address set the mod never opens a socket at all, which is exactly what single
 * player wants.
 */
public final class Cfg {
    private Cfg() {
    }

    /**
     * Whether the network layer may be used at all.
     *
     * <p>Off by default, and deliberately separate from having an address. An
     * address left in a config file from an earlier session should not be
     * enough to start opening sockets: the player has to ask for it again, every
     * time. This is the local build, whose whole job is to work in single player
     * on any server, and a remote control surface it does not need is an attack
     * surface it should not carry.
     */
    public static final boolean NETWORK_ENABLED = Settings.getBool("network.enabled", false);

    /** ScreenMine companion server, from config/screenmine.properties. */
    public static final String SERVER_HOST = Settings.get("server.host", "");
    public static final int SERVER_PORT = Settings.getInt("server.port", 23481);

    /**
     * False unless the network is both switched on and pointed somewhere.
     *
     * <p>Both are needed. {@code network.enabled} is the player's decision; an
     * empty host is the absence of one.
     */
    public static boolean serverConfigured() {
        return NETWORK_ENABLED && !SERVER_HOST.isBlank();
    }

    /**
     * Why the network is off, for the log.
     *
     * <p>"The network is inactive" is a claim that has to be checkable. A build
     * that carries networking needs to be able to say so in one line at startup,
     * because that line is the evidence.
     */
    public static String networkStatus() {
        if (!NETWORK_ENABLED) {
            return "off: network.enabled=false, so no socket is opened and packets are dropped";
        }
        if (SERVER_HOST.isBlank()) {
            return "switched on but server.host is empty, so no socket is opened";
        }
        return "connecting to " + SERVER_HOST + ":" + SERVER_PORT;
    }

    /** Wire protocol version; must match the Python server. */
    public static final int PROTOCOL_VERSION = 1;

    /** Off-screen browser resolution. The in-world quad keeps this aspect ratio.
     *  1080p costs more CPU than 720p — drop it back if the framerate suffers. */
    public static final int BROWSER_WIDTH = 1920;
    public static final int BROWSER_HEIGHT = 1080;

    public static final String DEFAULT_URL = "https://www.youtube.com";

    /** Screen size limits, in blocks (width). */
    public static final float MIN_WIDTH = 1.0f;
    public static final float MAX_WIDTH = 32.0f;
    public static final float DEFAULT_WIDTH = 6.0f;

    /** How far in front of the player a freshly spawned screen is placed. */
    public static final double SPAWN_DISTANCE = 4.0;

    /** Max distance at which you can point at / interact with a screen. */
    public static final double INTERACT_RANGE = 24.0;

    /** Solid plate right behind the picture, so the screen is never see-through. */
    public static final boolean OPAQUE_BACKGROUND = true;

    /** Draw a bezel around the picture. */
    public static final boolean DRAW_FRAME = false;

    /** Outline the screen you are aiming at. */
    public static final boolean HIGHLIGHT_AIMED = false;

    /** Screen frame thickness in blocks. The picture itself has zero thickness. */
    public static final float FRAME_THICKNESS = 0.03f;

    /** How much one wheel notch scrolls the page. */
    public static final double SCROLL_SPEED = 3.0;

    /** Default per-screen volume, 0..100. Local to each viewer. */
    public static final int DEFAULT_VOLUME = 70;

    /** Volume step for one keypress. */
    public static final int VOLUME_STEP = 10;

    /** Full volume closer than this many blocks; silent past AUDIO_FAR. */
    public static final double AUDIO_NEAR = 6.0;
    public static final double AUDIO_FAR = 40.0;

    /** How often the audio gain is recomputed, in client ticks. */
    public static final int AUDIO_TICKS = 5;

    /** Clicks landing within this window count towards the grab gesture. */
    public static final long MULTICLICK_WINDOW_MS = 1000L;

    /** Clicks needed before the screen is grabbed (3 clicks, grab on the 4th). */
    public static final int GRAB_CLICKS = 4;

    /** How often the stream owner reports playback state, in client ticks. */
    public static final int STATE_REPORT_TICKS = 60;

    /** Resync a viewer when its playback drifts more than this many seconds. */
    public static final double MAX_DRIFT_SECONDS = 2.0;

    // ---- following the server's playback clock ----

    /** How far behind the server clock we tolerate before catching up, seconds. */
    public static final double SYNC_BEHIND_TOLERANCE = 1.2;

    /** How far ahead we tolerate. Tighter: running ahead can only be fixed by jumping back. */
    public static final double SYNC_AHEAD_TOLERANCE = 0.6;

    /** Overshoot when catching up forward — the seek itself costs time. */
    public static final double SYNC_SEEK_LEAD = 0.35;

    /** No second corrective seek within this window, or a cold join stutters forever. */
    public static final long SYNC_SEEK_COOLDOWN_MS = 3000L;

    /** How often the follower checks itself against the server clock, in client ticks. */
    public static final int SYNC_TICKS = 10;

    public static final int RECONNECT_DELAY_MS = 5000;
}
