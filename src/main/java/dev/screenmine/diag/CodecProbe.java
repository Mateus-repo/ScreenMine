package dev.screenmine.diag;

import dev.screenmine.Settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic that reports which media codecs the embedded Chromium can actually
 * decode.
 *
 * <p>This exists because "the video does not play" has several very different
 * causes, and guessing between them wasted a run: Chromium built without
 * proprietary codecs is the usual suspect, but it is only a hypothesis until
 * the page itself is asked. {@code canPlayType} and
 * {@code MediaSource.isTypeSupported} are answered by the browser, so they are
 * the only source that settles it.
 *
 * <p>Off by default. Enable with {@code debug.codecs=1} in
 * {@code config/screenmine.properties}, then load any page: the table lands in
 * {@code latest.log} under the {@code ScreenMine/codecs} logger.
 *
 * <p>The report comes back through the browser console, which
 * {@code CefDisplayHandler.onConsoleMessage} receives directly.
 *
 * <p>It was originally sent through {@code document.title}, because
 * {@code CefBrowser.executeJavaScript} has no callback overload (verified with
 * {@code javap}) and so there is no way to read a return value. That channel lost
 * the race: the page agent rewrites the title every 250ms, and the 2026-10-15
 * 12:47 run logged "codec probe ON" and then nothing at all. The console is a
 * push channel and does not have to win a race.
 */
public final class CodecProbe {

    private static final Logger LOG = LoggerFactory.getLogger("ScreenMine/codecs");

    /** Marks a console message that belongs to a diagnostic. */
    public static final String PREFIX = "SMX|";

    /** Marks the page agent's one-shot self report. */
    public static final String AGENT_PREFIX = "SMA|";

    /** Field separator inside the payload; not valid in any value we send. */
    private static final char FIELD = '~';

    private static boolean enabled;

    private CodecProbe() {
    }

    /** True when the player asked for the probe in the settings file. */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Reads the setting once. Called from client startup, before any browser
     * exists.
     */
    public static void loadSetting() {
        enabled = Settings.getInt("debug.codecs", 0) == 1;
        if (enabled) {
            LOG.info("codec probe ON - results appear below once a page has loaded");
        }
    }

    /**
     * The probe itself, as a JS expression evaluating to nothing.
     *
     * <p>Written as one expression rather than a function so it can be appended
     * straight to the agent script. Every step is guarded: a page that has
     * locked down {@code document.title}, or a browser without
     * {@code MediaSource}, must not throw and take the agent down with it.
     */
    public static String script() {
        return "(function(){try{"
                + "var out=[];"
                // canPlayType answers "" (no), "maybe" or "probably".
                + "function cpt(mime){try{var v=document.createElement('video');"
                + "return v.canPlayType(mime)||'-';}catch(e){return 'ERR';}}"
                + "function mse(mime){try{return (window.MediaSource&&MediaSource.isTypeSupported(mime))?'yes':'no';}"
                + "catch(e){return 'ERR';}}"
                + "var codecs=[['avc1.42E01E','video/mp4; codecs=\"avc1.42E01E\"'],"
                + "['avc1.64001F','video/mp4; codecs=\"avc1.64001F\"'],"
                + "['mp4a.40.2','audio/mp4; codecs=\"mp4a.40.2\"'],"
                + "['hev1','video/mp4; codecs=\"hev1\"'],"
                + "['vp09.00.10.08','video/mp4; codecs=\"vp09.00.10.08\"'],"
                + "['av01.0.05M.08','video/mp4; codecs=\"av01.0.05M.08\"'],"
                + "['opus','audio/ogg; codecs=\"opus\"'],"
                + "['vorbis','audio/ogg; codecs=\"vorbis\"']];"
                + "for(var i=0;i<codecs.length;i++){var c=codecs[i];"
                + "out.push(c[0]+'.canPlayType='+cpt(c[1]));"
                + "out.push(c[0]+'.mse='+mse(c[1]));}"
                + "out.push('MediaSource='+(window.MediaSource?'yes':'MISSING'));"
                + "out.push('ua='+navigator.userAgent);"
                + "out.push('secureContext='+(window.isSecureContext?'yes':'no'));"
                + "console.log(\"" + PREFIX + "\"+out.join(\"~\"));"
                + "}catch(e){console.log(\"" + PREFIX + "error=\"+e);}})();";
    }

    /**
     * Logs a payload that arrived through the console.
     *
     * <p>The table is printed one field per line because a single {@code INFO}
     * line that has to be read sideways past a log prefix is much worse to work
     * with than a block.
     */
    public static void report(String payload) {
        LOG.info("---- codec probe ----");
        for (String field : payload.split(String.valueOf(FIELD))) {
            if (!field.isBlank()) {
                LOG.info("  {}", field);
            }
        }
        LOG.info("---- end codec probe ----");
    }

    /**
     * Logs the page agent's self report.
     *
     * <p>This is the one that answers "is the mod's volume reaching the page?".
     * {@code vol} is what Java last pushed, {@code media} is how many
     * {@code <video>}/{@code <audio>} elements the agent can see, and
     * {@code vvolume} is what the element's volume actually is. If those three
     * disagree, the agent is not applying anything.
     */
    public static void agentReport(String payload) {
        LOG.info("agent report: {}", payload.replace(FIELD, ' '));
    }
}