package dev.screenmine.mp;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * What the server will and will not accept, with no Minecraft in sight.
 *
 * <p>Deliberately pure. Every rule here takes primitives and strings and
 * returns a verdict, which means {@code tools/ProbeScreenRules.java} can
 * exercise all of them in a plain JVM instead of a running game. Validating a
 * domain suffix wrong is silent - the screen simply never appears for one
 * player and always appears for another - and a rule that can only be tested by
 * opening Minecraft is a rule that will be tested once.
 *
 * <p>The defaults are the ones agreed for the first version: friends only,
 * https, and two hosts.
 */
public final class ScreenRules {

    /** Screens one player may have placed at once. */
    public static final int MAX_SCREENS_PER_PLAYER = 2;

    /** A screen smaller than this is a speck, larger is a wall. In blocks. */
    public static final float MIN_SIZE = 1.0f;
    public static final float MAX_SIZE = 32.0f;

    /** Outside this a URL is a mistake or an attempt. */
    public static final int MIN_URL_LENGTH = 8;
    public static final int MAX_URL_LENGTH = 512;

    /** How far a placed screen may be from a world origin. Blocks. */
    public static final double MAX_COORDINATE = 30_000_000.0;

    /** The hosts a shared screen may open unless the server says otherwise. */
    public static final Set<String> DEFAULT_ALLOWED_HOSTS = Set.of("youtube.com", "youtu.be");

    /** Why a message was refused. Null means accepted. */
    public record Rejection(String code, String detail) {
        @Override
        public String toString() {
            return code + (detail == null ? "" : " (" + detail + ")");
        }
    }

    private ScreenRules() {
    }

    /**
     * Whether the URL may be opened in a shared screen.
     *
     * <p>Four separate refusals, not one, because they are four different
     * mistakes and lumping them together makes a bug report useless: a blank
     * field, a plain http link pasted by accident, a site nobody vetted, and a
     * two-hundred-character payload.
     */
    public static Rejection checkUrl(String url, Set<String> allowedHosts) {
        if (url == null || url.isBlank()) {
            return new Rejection("url-empty", null);
        }
        if (url.length() > MAX_URL_LENGTH) {
            return new Rejection("url-too-long", url.length() + " chars");
        }
        if (url.length() < MIN_URL_LENGTH) {
            return new Rejection("url-too-short", url);
        }

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            return new Rejection("url-unparseable", null);
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) {
            // Checked before the host, because the host check subsumes it and
            // would otherwise report "no host" for a javascript: URL. The
            // refusal is the same either way; the reason is what makes a bug
            // report useful, and "your scheme is not https" is the true one.
            return new Rejection("url-not-https", uri.getScheme());
        }
        if (uri.getHost() == null) {
            // No host means a relative reference, an opaque URI, or something
            // built to make URI.create happy. None of those can be vetted.
            return new Rejection("url-no-host", null);
        }
        if (!hostAllowed(uri.getHost(), allowedHosts)) {
            return new Rejection("url-host-not-allowed", uri.getHost());
        }
        return null;
    }

    /**
     * Whether the host is one of ours, or a subdomain of one.
     *
     * <p>The suffix match is the whole point and is easy to get wrong. Matching
     * with a bare {@code endsWith} would accept {@code notyoutube.com} and
     * {@code youtube.com.evil.example}, which is the whole attack. Matching
     * requires either an exact hit or a dot before the domain, so a subdomain
     * passes and a lookalike does not.
     */
    /**
     * Rewrites a YouTube video URL to the one form that cannot drift.
     *
     * <p>Taken from WebDisplays' {@code VideoType.getURLFromID}, and the reason
     * is specific enough to be worth writing down. Watching "together" means two
     * independent Chromium sessions, and on {@code /watch} the page contains the
     * places where YouTube decides for itself: the recommendations sidebar and
     * the autoplay radio. Those are per session. One browser shows the home
     * feed, the other rolls into a video; one lands on
     * {@code watch?v=A&list=RDA&start_radio=1} and the other on a different
     * radio. Nothing downstream can fix that - playback sync can only agree on a
     * position inside a page, and there is no position to agree on when the two
     * pages are not the same page.
     *
     * <p>{@code /embed/<id>} has neither the sidebar nor the radio. Same video on
     * every client, so there is something for the sync to align.
     *
     * <p>What is lost, plainly: the sidebar, the comments and the channel header.
     * That is the trade and for watching together it is the right one, but it is
     * a visible change and not something to do quietly to somebody's own screen.
     * So this is applied to <em>shared</em> screens only; a screen spawned
     * locally still opens whatever the player asked for.
     *
     * <p>Deliberately no {@code autoplay=1}, unlike the original. An embed that
     * starts without a user gesture is muted by the browser, and a muted video
     * would silently kill the distance-based audio falloff that is the whole
     * reason this mod makes noise at all. Playback is driven by the existing
     * agent instead.
     *
     * <p>Anything not positively recognised as one YouTube video comes back
     * untouched, so this cannot mangle an address it does not understand. It is
     * idempotent: canonicalising twice gives the same answer.
     */
    public static String canonicalShareUrl(String url) {
        String id = youtubeVideoId(url);
        return id.isEmpty() ? url : "https://www.youtube.com/embed/" + id;
    }

    /**
     * The bare video id, or empty when this is not one specific YouTube video.
     *
     * <p>The home page, a channel, a playlist and a search all return empty, and
     * that is the point: there is no single video to agree on for any of them.
     */
    public static String youtubeVideoId(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            return "";
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) {
            return "";
        }
        String host = uri.getHost();
        if (host == null) {
            return "";
        }
        host = host.toLowerCase(java.util.Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();

        if (host.equals("youtu.be")) {
            return sanitizeId(path.startsWith("/") ? path.substring(1) : path);
        }
        if (!hostAllowed(host, DEFAULT_ALLOWED_HOSTS)) {
            // Only the YouTube hosts the allowlist names. A lookalike host must
            // not be treated as YouTube, or this becomes a redirector.
            return "";
        }
        if (path.equals("/watch")) {
            String q = uri.getRawQuery();
            if (q == null) {
                return "";
            }
            for (String part : q.split("&")) {
                if (part.startsWith("v=")) {
                    return sanitizeId(part.substring(2));
                }
            }
            return "";
        }
        for (String prefix : new String[]{"/embed/", "/shorts/", "/live/"}) {
            if (path.startsWith(prefix)) {
                return sanitizeId(path.substring(prefix.length()));
            }
        }
        return "";
    }

    /**
     * Keeps only what a YouTube video id can contain.
     *
     * <p>The id is going back into a URL that every client will open, so it is
     * checked rather than trusted. Anything outside {@code [A-Za-z0-9_-]}, and
     * anything longer than an id can be, is not an id - and an unrecognised
     * string returns empty, which leaves the caller's URL untouched.
     */
    private static String sanitizeId(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > 64) {
            return "";
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return "";
            }
        }
        return candidate;
    }

    public static boolean hostAllowed(String host, Set<String> allowedHosts) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String allowed : allowedHosts) {
            String a = allowed.toLowerCase(Locale.ROOT);
            if (a.startsWith("*.")) {
                // A leading wildcard means subdomains only, not the bare domain.
                a = a.substring(2);
                if (h.endsWith("." + a)) {
                    return true;
                }
            } else if (h.equals(a) || h.endsWith("." + a)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the screen is a sane size in blocks. */
    public static Rejection checkSize(float width, float height) {
        if (!Float.isFinite(width) || !Float.isFinite(height)) {
            return new Rejection("size-not-finite", width + "x" + height);
        }
        if (width < MIN_SIZE || height < MIN_SIZE) {
            return new Rejection("size-too-small", width + "x" + height);
        }
        if (width > MAX_SIZE || height > MAX_SIZE) {
            return new Rejection("size-too-large", width + "x" + height);
        }
        return null;
    }

    /**
     * Whether the position is inside the world and not a NaN sent as a number.
     *
     * <p>A NaN would sail past every comparison in a range check - {@code NaN <
     * anything} is false - and would then be stored and sent to every client.
     */
    public static Rejection checkPosition(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return new Rejection("position-not-finite", x + "," + y + "," + z);
        }
        if (Math.abs(x) > MAX_COORDINATE || Math.abs(y) > MAX_COORDINATE
                || Math.abs(z) > MAX_COORDINATE) {
            return new Rejection("position-out-of-world", x + "," + y + "," + z);
        }
        if (y < -512.0 || y > 4096.0) {
            return new Rejection("position-out-of-build-height", String.valueOf(y));
        }
        return null;
    }

    /** How far a screen may be placed from the player placing it. Blocks. */
    public static final double MAX_PLACEMENT_DISTANCE = 64.0;

    /** Longest dimension id that will be believed. */
    public static final int MAX_DIMENSION_LENGTH = 128;

    /** Whether the yaw is a real angle. */
    public static Rejection checkYaw(float yaw) {
        if (!Float.isFinite(yaw)) {
            return new Rejection("yaw-not-finite", String.valueOf(yaw));
        }
        return null;
    }

    /**
     * Whether the screen is in the dimension the placer is actually in.
     *
     * <p>Checked against the server's own knowledge of where the player is, not
     * against what the message claims. A message that says "I am in the nether"
     * while the server can see the player in the overworld is refused for
     * saying so, and the client only renders screens whose dimension matches the
     * one it is in.
     *
     * <p>Without this a screen could be placed in a dimension the placer cannot
     * see, and anyone who goes there finds a screen nobody remembers putting
     * there.
     */
    public static Rejection checkDimension(String claimed, String actual) {
        if (claimed == null || claimed.isBlank()) {
            return new Rejection("dimension-empty", null);
        }
        if (claimed.length() > MAX_DIMENSION_LENGTH) {
            return new Rejection("dimension-too-long", claimed);
        }
        if (actual != null && !claimed.equals(actual)) {
            return new Rejection("wrong-dimension", claimed + " != " + actual);
        }
        return null;
    }

    /**
     * Whether a URL a remote browser is about to open is on the allowlist.
     *
     * <p>The same rule as {@link #hostAllowed}, reached from the URL rather than
     * from a host, so the client guard and the server check cannot drift apart.
     *
     * <p>Fail-closed for anything that is not https. A remote browser has no
     * business being sent to a file:, data: or about: URL by a page another
     * player chose. Those schemes are fine on the way in - the test page is a
     * file - and not on the way out of a shared screen.
     */
    public static boolean hostAllowedForNavigation(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        return hostAllowed(uri.getHost(), DEFAULT_ALLOWED_HOSTS);
    }

    /**
     * Whether the screen is close enough to the player placing it.
     *
     * <p>Three dimensions, not one, and each is refused for a different reason:
     * a NaN anywhere would make every comparison false and sail through a
     * single range check, so it is caught first; then the distance itself.
     *
     * <p>64 blocks is about the render distance' worth of "visible from here".
     * A screen the placer cannot see is a screen nobody can find again.
     */
    public static Rejection checkDistance(double x, double y, double z,
                                          double px, double py, double pz) {
        if (!Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)) {
            return new Rejection("player-position-not-finite", null);
        }
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(distance)) {
            return new Rejection("distance-not-finite", null);
        }
        if (distance > MAX_PLACEMENT_DISTANCE) {
            return new Rejection("too-far-from-player",
                    (int) Math.ceil(distance) + " blocks");
        }
        return null;
    }
}