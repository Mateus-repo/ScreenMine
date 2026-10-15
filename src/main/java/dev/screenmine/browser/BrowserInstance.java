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
            "if(s!==last){last=s;document.title=s;}" +
            "}catch(e){}},250);})();";

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
        browser = MCEFApi.getInstance().createBrowser("about:blank", false);
        browser.resize(surfaceWidth, surfaceHeight);
        installHandlersOnce(browser.getCefBrowser().getClient());
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
                if (title.startsWith(CodecProbe.PREFIX)) {
                    // Diagnostic payload, not playback state. Report it and leave
                    // the page title we had before untouched: the agent rewrites
                    // the title every 250ms anyway.
                    CodecProbe.report(title.substring(CodecProbe.PREFIX.length()));
                } else if (title.startsWith(STATE_PREFIX)) {
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
        js("window.__smVol=" + String.format(java.util.Locale.ROOT, "%.3f", g) + ";");
    }

    public double appliedGain() {
        return Math.max(0.0, appliedGain);
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
