package dev.screenmine.browser;


import dev.screenmine.Cfg;
import dev.screenmine.diag.CodecProbe;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;

import com.mojang.renderpearl.api.textures.GpuTextureView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One off-screen Chromium browser bound to one shared stream.
 *
 * <p>Playback state is read back out of the page through the window title: a
 * tiny JS agent writes {@code SM|<time>|<paused>|<title>} into
 * {@code document.title}, which arrives here via {@link CefDisplayHandlerAdapter}.
 * That avoids needing a CEF message router.
 */
public final class BrowserInstance {

    private static final Logger LOGGER = LoggerFactory.getLogger("ScreenMine");

    private static final String STATE_PREFIX = "SM|";

    /**
     * Injected once per page load. Twice a second it reports playback state
     * through the window title and enforces the volume the mod asked for —
     * enforcing on a timer is what makes it survive YouTube's SPA navigation.
     */
    private static final String AGENT_JS =
            "(function(){if(window.__smAgent)return;window.__smAgent=1;" +
            "if(typeof window.__smVol!=='number')window.__smVol=0.7;" +
            "var last='';" +
            "function pageTitle(){var m=document.querySelector('meta[property=\"og:title\"]');" +
            "if(m&&m.content)return m.content;" +
            "var h=document.querySelector('h1.ytd-watch-metadata,h1.title');" +
            "if(h&&h.innerText)return h.innerText;return window.__smBase||'';}" +
            "window.__smBase=document.title;" +
            // --- editing shim -------------------------------------------------
            // JCEF's OSR path only ever delivers KEY_PRESSED as a RAWKEYDOWN, so
            // Blink's default editing actions (Backspace, Delete, caret movement)
            // never fire, even though keydown listeners in the page do see the
            // event. Known upstream: DimasKama/mcef-modern#4, still open, and PR
            // #5 explicitly does not fix it. Remove this once that is resolved.
            "(function(){if(window.__smEdit)return;window.__smEdit=1;" +
            "function ed(e){var t=e.target;return t&&(t.isContentEditable||t.tagName==='INPUT'||t.tagName==='TEXTAREA');}" +
            "function set(e,a,b){var t=e.target;try{t.setSelectionRange(a,b);}catch(x){}}" +
            "document.addEventListener('keydown',function(e){if(!ed(e))return;var t=e.target,s=t.selectionStart,n=t.selectionEnd,v=t.value;" +
            "if(e.key==='Backspace'){if(s!==n){t.setRangeText('',s,n,'end');}else if(s>0){t.setRangeText('',s-1,s,'end');}e.preventDefault();}" +
            "else if(e.key==='Delete'){if(s!==n){t.setRangeText('',s,n,'end');}else if(n<v.length){t.setRangeText('',n,n+1,'end');}e.preventDefault();}" +
            "else if(e.key==='ArrowLeft'){if(s===n&&s>0)set(e,s-1,s-1);e.preventDefault();}" +
            "else if(e.key==='ArrowRight'){if(s===n&&n<v.length)set(e,n+1,n+1);e.preventDefault();}" +
            "else if(e.key==='Home'){set(e,0,0);e.preventDefault();}" +
            "else if(e.key==='End'){set(e,v.length,v.length);e.preventDefault();}" +
            "t.dispatchEvent(new Event('input',{bubbles:true}));},true);})();" +
            "var lastMedia=-1,lastVol=-1;" +
            "setInterval(function(){try{" +
            "var all=document.querySelectorAll('video,audio');" +
            "for(var i=0;i<all.length;i++){var e=all[i];" +
            "if(Math.abs(e.volume-window.__smVol)>0.01)e.volume=window.__smVol;" +
            "if(e.muted&&window.__smVol>0)e.muted=false;}" +
            "var v=document.querySelector('video');" +
            "var t=v?v.currentTime.toFixed(2):'-1';" +
            "var p=(!v||v.paused)?'1':'0';" +
            "var a=document.activeElement;" +
            "var ed=(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA'||a.isContentEditable))?'1':'0';" +
            "var ad=(document.querySelector('.ad-showing,.video-ads,.ytp-ad-player-overlay,.ytp-ad-module')!==null)?'1':'0';" +
            "var u=window.location.href.replace(/\\|/g,'');" +
            "var s='SM|'+t+'|'+p+'|'+ed+'|'+ad+'|'+u+'|'+pageTitle().replace(/\\|/g,' ').slice(0,120);" +
            // Depends on what the DOCUMENT holds, not on what we last wrote.
            // The old guard was `if(s!==last)`, and `last` is our own payload -
            // so the instant the page set document.title itself (YouTube does,
            // with the video name) the agent stopped rewriting, the mod stopped
            // receiving SM|, and everything derived from it froze at its initial
            // value: hasVideo, playbackPaused, isEditing, isAd. That is a single
            // candidate for both "N does nothing" and "editing keys never work".
            "if(s!==last||document.title.slice(0,3)!=='SM|'){last=s;document.title=s;}" +
            // Self report, on change only. href and top are the two fields that
            // say whether this agent is even in the document anyone is looking
            // at: an about:blank href means executeJavaScript landed on the
            // initial frame, and 'iframe' means it landed in a subframe, where
            // the querySelectorAll above cannot see the video.
            "if(all.length!==lastMedia||Math.abs(window.__smVol-lastVol)>0.001){" +
            "lastMedia=all.length;lastVol=window.__smVol;" +
            "console.log(\"" + CodecProbe.AGENT_PREFIX + "vol=\"+window.__smVol" +
            "+\"|media=\"+all.length" +
            "+\"|vvolume=\"+(v?v.volume.toFixed(2):'-')" +
            "+\"|vpaused=\"+(v?(v.paused?'1':'0'):'-')" +
            "+\"|editable=\"+ed" +
            "+\"|top=\"+(window.top===window?'main':'iframe')" +
            "+\"|href=\"+window.location.href.replace(/\\|/g,'').slice(0,80));" +
            "}" +
            "}catch(e){}},250);" +
            "})();";

    public final String streamId;

    private MCEFBrowser browser;
    private boolean closed;

    /** Latest values reported by the page. */
    private volatile double playbackTime = -1;
    private volatile boolean playbackPaused = true;
    private volatile boolean editing;
    private volatile boolean isAd;
    /** Set from the CEF thread, drained on the client tick. */
    private volatile String pendingNavigate;
    private volatile String pageTitle = "";
    private volatile String currentUrl;

    /** Set while we apply a remote command, so we do not echo it back. */
    private boolean applyingRemote;

    /** Current off-screen size; the fullscreen view temporarily changes it. */
    private int surfaceWidth = Cfg.BROWSER_WIDTH;
    private int surfaceHeight = Cfg.BROWSER_HEIGHT;

    /** Last gain actually pushed into the page, 0..1; -1 means "unknown". */
    private double appliedGain = -1.0;
    /** Fallback agent injection: frames waited, and whether it already ran. */
    private int agentTicks;
    private boolean agentInjected;
    /** Codec support is per Chromium build, so the probe runs once per browser. */
    private boolean probeRan;

    public BrowserInstance(String streamId, String url) {
        this.streamId = streamId;
        this.currentUrl = url == null || url.isEmpty() ? Cfg.DEFAULT_URL : url;
    }

    // ---------------------------------------------------------------- setup //

    /** Creates the underlying browser once MCEF Modern is up. Call from the render thread. */
    public boolean ensureCreated() {
        if (closed) {
            return false;
        }
        if (browser != null) {
            return true;
        }
        // Never block the render thread on the Chromium download / install: if
        // the API is not ready yet we simply draw the grey placeholder this tick.
        if (!MCEFApi.initialize().isDone()) {
            return false;
        }
        // Create on about:blank, then navigate, so the load and display handlers
        // are registered before any real page starts loading. Creating straight at
        // the target URL raced: MCEFApiImpl.createBrowser calls createImmediately()
        // on the JCEF side, so loading begins inside createBrowser(). A fast page
        // (a cache hit, or about:blank itself) finished before installHandlersOnce
        // had run, so onLoadEnd never fired at all.
        //
        // That is not theoretical. The 2026-10-15 12:41 run had no ONLOADEND line
        // at all, which is the signature: the page agent was never injected, and
        // with it went the volume enforcement, the playback tracking and the
        // editing shim. The bug report "N does nothing in the world" was this.
        String target = currentUrl;
        LOGGER.info("CREATE url={} surface={}x{}", target, surfaceWidth, surfaceHeight);
        // Without this, a createBrowser that throws would be retried on every
        // frame of every screen, because the renderer calls ensureCreated() once
        // per screen per frame and browser would still be null. One attempt, one
        // log line, then stop trying.
        try {
            browser = MCEFApi.getInstance().createBrowser("about:blank", false);
        } catch (RuntimeException ex) {
            LOGGER.error("CREATE failed for {}: {}", streamId, ex.toString());
            browser = null;
            return false;
        }
        if (browser == null || browser.getCefBrowser() == null) {
            LOGGER.error("CREATE returned nothing usable for {}", streamId);
            browser = null;
            return false;
        }
        browser.resize(surfaceWidth, surfaceHeight);
        installHandlersOnce(browser.getCefBrowser().getClient());
        if (!handlersInstalled) {
            // Same signature as the original bug: a browser created before the
            // shared client was available never gets a load handler, so
            // onLoadEnd never fires and the page agent never arrives. Silent
            // until it is logged.
            LOGGER.error("no CefClient yet - this browser will get no load handler");
        }
        LIVE.put(browser.getCefBrowser().getIdentifier(), this);
        agentInjected = false;
        agentTicks = 0;
        // Only now, with the handlers registered and the id mapped, go to the
        // real page. currentUrl is restored because navigate() would clear it.
        currentUrl = target;
        LOGGER.info("NAVIGATE to {}", target);
        browser.getCefBrowser().loadURL(target);
        return true;
    }

    /**
     * MCEF Modern exposes a single shared {@code CefClient}, so handlers are
     * installed exactly once and dispatched by browser identifier. Registering
     * them per browser would leak a handler on every spawn.
     */
    private static final Map<Integer, BrowserInstance> LIVE = new ConcurrentHashMap<>();
    private static boolean handlersInstalled;

    /**
     * Injects the page agent as a fallback. A data: URL finishes loading before the
     * CEF handlers are registered, so onLoadEnd never fires and the agent — which
     * carries the editing shim — never runs. Retry once the page has settled.
     */
    public void pumpAgent() {
        if (agentInjected || browser == null) {
            return;
        }
        if (++agentTicks < 40) {
            return;
        }
        agentInjected = true;
        injectAgent();
        LOGGER.info("AGENT injected by retry (onLoadEnd never fired)");
    }

    /**
     * How many {@code SM|} reports to log in full before going quiet.
     *
     * <p>Not a debug aid bolted on: four runs of this mod and not one line had
     * ever shown a report arriving. That silence is the reason "N does nothing"
     * could not be reasoned about - {@code hasVideo}, {@code playbackPaused},
     * {@code isEditing} and {@code isAd} all read straight out of this payload,
     * and if it never lands they are silently stuck at their initial values.
     * Proving the channel works is worth a log line; proving it keeps working is
     * not, hence the throttle.
     */
    private static final int STATE_LOG_FIRST = 5;
    private static final int STATE_LOG_EVERY = 200;

    private int stateReports;
    private boolean stateLoggedOnce;

    private static BrowserInstance lookup(CefBrowser b) {
        return b == null ? null : LIVE.get(b.getIdentifier());
    }

    private static void installHandlersOnce(org.cef.CefClient client) {
        if (handlersInstalled || client == null) {
            return;
        }
        handlersInstalled = true;

        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser b, CefFrame frame, int httpStatusCode) {
                BrowserInstance self = lookup(b);
                if (self != null) {
                    LOGGER.info("ONLOADEND status={} url={} textureView={}", httpStatusCode, self.url(), self.textureView() != null);
                    self.agentInjected = true;
                    self.injectAgent();
                }
            }
        });

        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public void onTitleChange(CefBrowser b, String title) {
                BrowserInstance self = lookup(b);
                if (self == null || title == null) {
                    return;
                }
                if (title.startsWith(STATE_PREFIX)) {
                    self.logStateReport(title);
                    self.parseState(title);
                } else {
                    self.pageTitle = title;
                }
            }

            @Override
            public void onAddressChange(CefBrowser b, CefFrame frame, String url) {
                BrowserInstance self = lookup(b);
                if (self == null || (frame != null && !frame.isMain())) {
                    return;
                }
                self.currentUrl = url;
                if (!self.applyingRemote) {
                    // CEF thread — never touch mod state from here
                    self.pendingNavigate = url;
                }
            }

            /**
             * Diagnostics only.
             *
             * <p>Pages log plenty of their own, so only our prefixed lines are
             * taken. This exists because {@code executeJavaScript} has no
             * callback: the console is the one channel that pushes back to us
             * reliably.
             */
            @Override
            public boolean onConsoleMessage(CefBrowser b, org.cef.CefSettings.LogSeverity level,
                    String message, String source, int line) {
                if (message == null || !CodecProbe.enabled()) {
                    return false;
                }
                if (message.startsWith(CodecProbe.PREFIX)) {
                    CodecProbe.report(message.substring(CodecProbe.PREFIX.length()));
                } else if (message.startsWith(CodecProbe.AGENT_PREFIX)) {
                    CodecProbe.agentReport(message.substring(CodecProbe.AGENT_PREFIX.length()));
                }
                return false;   // let the other console listeners keep it
            }
        });
    }

    /** The raw JCEF browser behind the MCEF Modern wrapper, or null. */
    private CefBrowser cef() {
        return browser == null ? null : browser.getCefBrowser();
    }

    /** Run JS in the page, ignoring failures. */
    private void injectJavaScript(String code) {
        CefBrowser cef = cef();
        if (cef != null) {
            cef.executeJavaScript(code, cef.getURL(), 0);
        }
    }

    private void injectAgent() {
        CefBrowser cef = cef();
        if (cef != null) {
            // frameCount is here because "the agent cannot see the video" has two
            // very different causes and this tells them apart: 1 means the video
            // should have been found in the main frame and something else is
            // wrong, 2 or more means the video is probably in an iframe.
            LOGGER.info("INJECT agent, frames={}, url={}", cef.getFrameCount(), cef.getURL());
            cef.executeJavaScript(AGENT_JS, cef.getURL(), 0);
            // fresh page: whatever we pushed before is gone, so push it again
            appliedGain = -1.0;
            maybeProbeCodecs();
        }
    }

    /**
     * Asks the page what it can decode, once per browser.
     *
     * <p>Codec support belongs to the Chromium build, not to the page, so one
     * answer per browser is enough and avoids repeating the table on every
     * navigation. {@link CodecProbe} owns the JS and the logging.
     */
    private void maybeProbeCodecs() {
        if (probeRan || !CodecProbe.enabled()) {
            return;
        }
        probeRan = true;
        CefBrowser cef = cef();
        if (cef != null) {
            cef.executeJavaScript(CodecProbe.script(), cef.getURL(), 0);
        }
    }

    /**
     * Sets the playback gain, 0..1. Cheap to call every few ticks — the JS is
     * only sent when the value actually moved.
     */
    public void applyGain(double gain) {
        double g = Math.max(0.0, Math.min(1.0, gain));
        if (appliedGain >= 0.0 && Math.abs(g - appliedGain) < 0.015) {
            return;
        }
        appliedGain = g;
        // Logged because "N does nothing" was impossible to reason about: from
        // Java the handler looked correct and the chat message appeared, so the
        // only question left was whether the value ever left the JVM.
        LOGGER.info("GAIN {} -> __smVol={}", streamId,
                String.format(java.util.Locale.ROOT, "%.3f", g));
        js("window.__smVol=" + String.format(java.util.Locale.ROOT, "%.3f", g) + ";");
    }

    public double appliedGain() {
        return Math.max(0.0, appliedGain);
    }

    /** Throttled proof that the page's state channel is alive. */
    private void logStateReport(String title) {
        stateReports++;
        if (stateReports == 1) {
            LOGGER.info("SM| channel alive for {} - first payload: {}", streamId, title);
        } else if (stateReports <= STATE_LOG_FIRST) {
            LOGGER.info("SM| #{} {}", stateReports, title);
        } else if (stateReports % STATE_LOG_EVERY == 0) {
            LOGGER.info("SM| #{} (still alive) {}", stateReports, title);
        } else if (!stateLoggedOnce) {
            stateLoggedOnce = true;
            LOGGER.info("SM| further payloads for {} throttled to every {}th",
                    streamId, STATE_LOG_EVERY);
        }
    }

    private void parseState(String title) {
        String[] parts = title.split("\\|", 7);
        if (parts.length < 4) {
            return;
        }
        try {
            playbackTime = Double.parseDouble(parts[1]);
            playbackPaused = "1".equals(parts[2]);
            editing = "1".equals(parts[3]);
            if (parts.length > 4) {
                isAd = "1".equals(parts[4]);
            }
            if (parts.length > 5) {
                String pageUrl = parts[5];
                if (pageUrl != null && !pageUrl.isBlank() && !pageUrl.equals(currentUrl)) {
                    currentUrl = pageUrl;
                    if (!applyingRemote) {
                        // CEF thread — never touch mod state from here
                        pendingNavigate = pageUrl;
                    }
                }
            }
            if (parts.length > 6) {
                pageTitle = parts[6];
            }
        } catch (NumberFormatException ignored) {
            // page wrote something unexpected; keep the previous state
        }
    }

    // ---------------------------------------------------------------- access //

    public MCEFBrowser raw() {
        return browser;
    }

    /** True once Chromium has uploaded at least one frame. */
    public boolean ready() {
        return textureView() != null;
    }

    /**
     * The current frame as a GPU texture, or null before the first upload.
     * Minecraft 26.3 textures are {@code GpuTextureView}s rather than raw GL
     * integer ids, so this is what the render pass binds.
     */
    public GpuTextureView textureView() {
        return browser == null ? null : browser.getTextureView();
    }

    /** Cursor shape the page is asking for, for {@code requestCursor}. */
    public com.mojang.blaze3d.platform.cursor.CursorType cursorType() {
        return browser == null ? null : browser.getCursorType();
    }

    public double playbackTime() {
        return playbackTime;
    }

    public boolean playbackPaused() {
        return playbackPaused;
    }

    /** Resizes the off-screen surface. Used by the fullscreen browser view. */
    public void resize(int width, int height) {
        int w0 = surfaceWidth, h0 = surfaceHeight;
        if (width <= 0 || height <= 0) {
            LOGGER.warn("REFUSING zero-size resize {}x{} - a browser with no size never paints", width, height);
            return;
        }
        if (width == surfaceWidth && height == surfaceHeight) {
            // Same size: calling browser.resize anyway makes CEF reload the page,
            // which threw away the page position every time the overlay closed.
            return;
        }
        surfaceWidth = width;
        surfaceHeight = height;
        if (browser != null) {
            LOGGER.info("RESIZE {}x{} (was {}x{}) textureView={}", width, height, w0, h0, browser.getTextureView() != null);
            browser.resize(width, height);
        }
    }

    public void resetSurface() {
        resize(Cfg.BROWSER_WIDTH, Cfg.BROWSER_HEIGHT);
    }

    public boolean isAd() {
        return isAd;
    }

    /**
     * Takes the navigation the page reported on the CEF thread, or null.
     * Called from the client tick, so mod state is only ever touched from the
     * game thread.
     */
    public String consumePendingNavigate() {
        String url = pendingNavigate;
        if (url != null) {
            pendingNavigate = null;
        }
        return url;
    }

    /**
     * Identity of what a URL is actually showing: the YouTube video id when
     * there is one, otherwise the address without its query string.
     *
     * <p>YouTube keeps rewriting its own address bar, so comparing URLs as plain
     * strings says "different page" every few seconds and reloads forever.
     */
    public static String videoKey(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        int hash = u.indexOf('#');
        if (hash >= 0) {
            u = u.substring(0, hash);
        }
        String id = youtubeId(u);
        if (!id.isEmpty()) {
            return "yt:" + id;
        }
        int q = u.indexOf('?');
        return q >= 0 ? u.substring(0, q) : u;
    }

    private static String youtubeId(String u) {
        for (String marker : new String[]{"?v=", "&v="}) {
            int i = u.indexOf(marker);
            if (i >= 0) {
                return cut(u.substring(i + marker.length()));
            }
        }
        for (String marker : new String[]{"youtu.be/", "/embed/", "/shorts/", "/live/"}) {
            int i = u.indexOf(marker);
            if (i >= 0) {
                return cut(u.substring(i + marker.length()));
            }
        }
        return "";
    }

    private static String cut(String s) {
        int end = s.length();
        for (char c : new char[]{'?', '&', '/'}) {
            int p = s.indexOf(c);
            if (p >= 0 && p < end) {
                end = p;
            }
        }
        return s.substring(0, end);
    }

    /** True when both URLs point at the same video / page, ignoring query churn. */
    public static boolean sameVideo(String a, String b) {
        String ka = videoKey(a);
        return !ka.isEmpty() && ka.equals(videoKey(b));
    }

    public boolean hasVideo() {
        return playbackTime >= 0;
    }

    /**
     * True while the page has a text field focused. The mod only steals the
     * keyboard from the game while this is set, so WASD keeps working the rest
     * of the time.
     */
    public boolean isEditing() {
        return editing;
    }

    public String pageTitle() {
        return pageTitle == null ? "" : pageTitle;
    }

    public String url() {
        return currentUrl == null ? "" : currentUrl;
    }

    // ---------------------------------------------------------------- control //

    public void navigate(String url, boolean remote) {
        CefBrowser cef = cef();
        if (cef == null || url == null || url.isEmpty()) {
            return;
        }
        applyingRemote = remote;
        currentUrl = url;
        playbackTime = -1;
        appliedGain = -1.0;
        cef.loadURL(url);
        applyingRemote = false;
    }

    public void js(String code) {
        CefBrowser cef = cef();
        if (cef != null) {
            cef.executeJavaScript(code, cef.getURL(), 0);
        }
    }

    public void play() {
        js("(function(){var v=document.querySelector('video');if(v)v.play();})()");
    }

    public void pause() {
        js("(function(){var v=document.querySelector('video');if(v)v.pause();})()");
    }

    public void togglePlay() {
        js("(function(){var v=document.querySelector('video');if(!v)return;" +
           "if(v.paused)v.play();else v.pause();})()");
    }

    public void seekBy(double seconds) {
        js("(function(){var v=document.querySelector('video');if(v)v.currentTime=Math.max(0,v.currentTime+("
           + seconds + "));})()");
    }

    public void seekTo(double seconds) {
        js("(function(){var v=document.querySelector('video');if(v)v.currentTime=Math.max(0,"
           + seconds + ");})()");
    }

    public void setPaused(boolean paused) {
        if (paused) {
            pause();
        } else {
            play();
        }
    }

    public void reload() {
        CefBrowser cef = cef();
        if (cef != null) {
            cef.reload();
        }
    }

    public void goBack() {
        CefBrowser cef = cef();
        if (cef != null) {
            cef.goBack();
        }
    }

    public void close() {
        closed = true;
        if (browser != null) {
            LIVE.remove(browser.getCefBrowser().getIdentifier());
            try {
                browser.close();
            } catch (Exception ex) {
                LOGGER.warn("failed to close browser: {}", ex.toString());
            }
            browser = null;
        }
    }

    public boolean isClosed() {
        return closed;
    }
}
