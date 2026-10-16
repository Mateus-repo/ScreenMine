package dev.screenmine.browser;


import dev.screenmine.Cfg;
import dev.screenmine.diag.CodecProbe;
import com.mojang.blaze3d.platform.InputConstants;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;

import com.mojang.renderpearl.api.textures.GpuTextureView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.Vector;
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

    /** Ticks to wait before the agent is injected without an onLoadEnd. */
    private static final int AGENT_TICKS = 40;
    /** How often to re-check whether the page's frame set changed, in ticks. */
    private static final int FRAME_WATCH_TICKS = 40;
    /** Ceiling on frames we will run JS in, so a hostile page cannot flood us. */
    private static final int MAX_FRAMES = 16;

    /**
     * Editing shim, injected with the rest of the agent.
     *
     * <p>JCEF's off-screen path delivers every {@code KEY_PRESSED} to Blink as a
     * {@code RAWKEYDOWN}, and raw keydowns deliberately do not trigger the
     * browser's default editing actions. Page {@code keydown} listeners do see
     * the event - the page just never acts on it. So Backspace, Delete, the
     * caret keys and every Ctrl shortcut arrive and do nothing. Known upstream
     * as DimasKama/mcef-modern#4, still open, and PR #5 explicitly does not fix
     * it. Delete this the day that lands.
     *
     * <p>It lives in the agent rather than in Java on purpose: it runs in the
     * frame that actually holds focus, which after the all-frames change may be
     * a subframe, and it sees the event before Minecraft has to know anything
     * about modifiers.
     *
     * <p>Ctrl+C is handled outside the editable check on purpose. Copying a
     * selection on a page - text, a line of code - has no focused field to
     * detect, and that is the case that was reported as broken.
     */
    private static final String EDIT_SHIM_JS =
            "(function(){if(window.__smEdit)return;window.__smEdit=1;" +
            "function ed(e){var t=e.target;return !!(t&&(t.isContentEditable||t.tagName==='INPUT'||t.tagName==='TEXTAREA'));}" +
            "function set(t,a,b){try{t.setSelectionRange(a,b);}catch(x){}}" +
            "function cmd(n){try{return document.execCommand(n);}catch(x){return false;}}" +
            "function typed(t){t.dispatchEvent(new Event('input',{bubbles:true}));}" +
            "document.addEventListener('keydown',function(e){" +
            "var t=e.target,k=(e.key||'').toLowerCase(),mod=e.ctrlKey||e.metaKey;" +
            // copy works on a page selection too, not just inside a field
            "if(mod&&!e.altKey&&k==='c'){e.preventDefault();cmd('copy');return;}" +
            "if(!ed(e))return;" +
            "var s=t.selectionStart,n=t.selectionEnd,v=t.value==null?'':t.value;" +
            "if(mod&&!e.altKey){" +
            "if(k==='a'){e.preventDefault();set(t,0,v.length);typed(t);return;}" +
            "if(k==='v'){e.preventDefault();cmd('paste');typed(t);return;}" +
            "if(k==='x'){e.preventDefault();cmd('cut');typed(t);return;}" +
            "if(k==='z'&&!e.shiftKey){e.preventDefault();cmd('undo');typed(t);return;}" +
            "if((k==='z'&&e.shiftKey)||k==='y'){e.preventDefault();cmd('redo');typed(t);return;}" +
            "return;}" +
            "if(k==='backspace'){if(s!==n){t.setRangeText('',s,n,'end');}else if(s>0){t.setRangeText('',s-1,s,'end');}e.preventDefault();}" +
            "else if(k==='delete'){if(s!==n){t.setRangeText('',s,n,'end');}else if(n<v.length){t.setRangeText('',n,n+1,'end');}e.preventDefault();}" +
            // bare arrows only: with a modifier the browser may want them for
            // something else, and the history shortcut is handled in Java
            "else if(e.key==='ArrowLeft'&&!mod&&!e.altKey){if(s===n&&s>0)set(t,s-1,s-1);e.preventDefault();}" +
            "else if(e.key==='ArrowRight'&&!mod&&!e.altKey){if(s===n&&n<v.length)set(t,n+1,n+1);e.preventDefault();}" +
            "else if(e.key==='Home'&&!mod){set(t,0,0);e.preventDefault();}" +
            "else if(e.key==='End'&&!mod){set(t,v.length,v.length);e.preventDefault();}" +
            "else return;" +
            "typed(t);},true);})();";

    /**
     * Injected once per page load. Four times a second it reports playback state
     * through the window title and enforces the volume the mod asked for —
     * enforcing on a timer is what makes it survive YouTube's SPA navigation.
     */
    private static final String AGENT_JS =
            "(function(){if(window.__smAgent)return;window.__smAgent=1;" +
            "if(typeof window.__smVol!=='number')window.__smVol=0.7;" +
            "var last='';" +
            "var lastSent='';" +
            "function pageTitle(){var m=document.querySelector('meta[property=\"og:title\"]');" +
            "if(m&&m.content)return m.content;" +
            "var h=document.querySelector('h1.ytd-watch-metadata,h1.title');" +
            "if(h&&h.innerText)return h.innerText;return window.__smBase||'';}" +
            "window.__smBase=document.title;" +
            EDIT_SHIM_JS +
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
            // State goes out through the console, not through document.title.
            //
            // The title channel is unusable and it took four runs to accept it.
            // The page writes document.title too - YouTube does, with the video
            // name - so the mod and the page fight over one string, and the
            // mod lost every time: not one SM| receipt in any run, which is
            // why isEditing() has been permanently false and the world input
            // mixins refuse to forward keys. Console has no owner to race with.
            //
            // The title is still rewritten, because something in the chain may
            // still be listening for it, and it costs nothing. The payload is
            // the same shape, so parseState is unchanged.
            "var s='SM|'+t+'|'+p+'|'+ed+'|'+ad+'|'+u+'|'+pageTitle().replace(/\\|/g,' ').slice(0,120);" +
            "if(s!==last||document.title.slice(0,3)!=='SM|'){last=s;document.title=s;}" +
            "if(s!==lastSent){lastSent=s;console.log(\"" + STATE_PREFIX + "\"+s.slice(3));}" +
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

    /*
 * Threading.
 *
 * <p>Two threads touch this object. The client tick owns position, audio,
 * playback commands and lifecycle; the CEF UI thread owns the handlers and
 * writes through parseState and onLoadEnd. Every field below is read on one
 * thread and written on the other, so it is volatile - without it the JMM does
 * not promise the write is visible, and the failure mode is a rare bug that
 * reproduces once a month. The playback fields below were already volatile;
 * these are the ones that were not.
 */

private volatile MCEFBrowser browser;
private volatile boolean closed;

    /** Latest values reported by the page. */
    private volatile double playbackTime = -1;
    private volatile boolean playbackPaused = true;
    private volatile boolean editing;
    private volatile boolean isAd;
    /** Set from the CEF thread, drained on the client tick. */
    private volatile String pendingNavigate;
    private volatile String pageTitle = "";
    private volatile String currentUrl;

    /**
     * Set while a remote navigation is being applied.
     *
     * <p>Honest caveat: this does not do what it looks like it does.
     * {@link CefBrowser#loadURL} is asynchronous and {@code onAddressChange}
     * arrives milliseconds later, long after this flag is back to false, so it
     * never actually suppresses the echo. What does suppress it is the
     * {@code sameVideo} comparison in {@code onLocalNavigate}. Kept volatile
     * because it is genuinely read on the CEF thread, and kept because removing
     * it would change behaviour that currently happens to work.
     */
    private volatile boolean applyingRemote;

    /** Current off-screen size; the fullscreen view temporarily changes it. */
    private int surfaceWidth = Cfg.BROWSER_WIDTH;
    private int surfaceHeight = Cfg.BROWSER_HEIGHT;

    /** Last gain actually pushed into the page, 0..1; -1 means "unknown". */
    private volatile double appliedGain = -1.0;
    /** Fallback agent injection: frames waited, and whether it already ran. */
    private int agentTicks;
    private volatile boolean agentInjected;
    /** Codec support is per Chromium build, so the probe runs once per browser. */
    private volatile boolean probeRan;

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
     * Makes sure the page agent is in every frame, however the page got there.
     *
     * <p>{@code onLoadEnd} normally does it, but it can be missed: the 12:41 run
     * had no {@code ONLOADEND} at all, and the signature of that is an agent that
     * never ran - no volume enforcement, no playback tracking, no editing shim,
     * which is what the "N does nothing" report was.
     *
     * <p>It also watches the frame set afterwards. Frames come and go on a
     * single-page app - an ad slot mounts, a player is replaced - and a frame
     * that appears after the injection has no agent in it. The script is
     * idempotent, so re-running it costs one no-op per live frame.
     */
    public void pumpAgent() {
        CefBrowser cef = cef();
        if (cef == null) {
            return;
        }
        if (!agentInjected) {
            if (++agentTicks < AGENT_TICKS) {
                return;
            }
            agentInjected = true;
            injectAgent();
            LOGGER.info("AGENT injected by retry (onLoadEnd never fired)");
            return;
        }
        if (++agentTicks < FRAME_WATCH_TICKS) {
            return;
        }
        agentTicks = 0;
        // Compare like with like. The injection records getFrameIdentifiers(),
        // not getFrameCount(), and the two need not agree about the main frame.
        int seen = frameIdentifiers(cef).size();
        if (seen != knownFrames) {
            injectAgent();
            LOGGER.info("frame set changed to {}, agent re-injected", seen);
        }
    }

    private static Vector<String> frameIdentifiers(CefBrowser cef) {
        try {
            Vector<String> ids = cef.getFrameIdentifiers();
            return ids == null ? new Vector<>() : ids;
        } catch (RuntimeException ex) {
            return new Vector<>();
        }
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
    /** How many ordinary title changes to print before going quiet. */
    private static final int TITLE_LOG_FIRST = 5;

    private int stateReports;
    private boolean stateLoggedOnce;
    /** Every title change seen, recognised or not. */
    private int titleChanges;

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
                // Every title change is counted, not just the ones we recognise.
                // Five runs produced zero SM| receipts, and the only way to tell
                // "the agent never writes the title" from "JCEF never tells us"
                // is to know whether this callback fires at all.
                self.titleChanges++;
                if (self.titleChanges <= TITLE_LOG_FIRST) {
                    LOGGER.info("onTitleChange #{} {}", self.titleChanges, title);
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
                if (message == null) {
                    return false;
                }
                // The page's state channel. Not gated on debug.codecs: this is
                // how isEditing(), hasVideo() and playbackPaused() are fed, and
                // gating it would mean the mod quietly stops working when a
                // diagnostic flag is off.
                if (message.startsWith(STATE_PREFIX)) {
                    BrowserInstance self = lookup(b);
                    if (self != null) {
                        self.logStateReport(message);
                        self.parseState(message);
                    }
                    return true;
                }
                // Diagnostics only, and only when asked for. Pages log plenty
                // of their own, so only our prefixed lines are taken.
                if (CodecProbe.enabled()) {
                    if (message.startsWith(CodecProbe.PREFIX)) {
                        CodecProbe.report(message.substring(CodecProbe.PREFIX.length()));
                    } else if (message.startsWith(CodecProbe.AGENT_PREFIX)) {
                        CodecProbe.agentReport(message.substring(CodecProbe.AGENT_PREFIX.length()));
                    }
                }
                return false;   // let the other console listeners keep it
            }
        });
    }

    /** The raw JCEF browser behind the MCEF Modern wrapper, or null. */
    private CefBrowser cef() {
        return browser == null ? null : browser.getCefBrowser();
    }

    /**
     * Runs JS in every frame, not just the main one.
     *
     * <p>The page agent reports {@code document.querySelectorAll('video,audio')}
     * from whichever document it was injected into, and the main document cannot
     * see into an iframe at all. So an embedded player - which the mod already
     * accepts as input, since {@code reportOwnedStreams} filters on
     * {@code /embed/} - is invisible to the agent by construction, and every
     * command sent only to the main frame misses it too.
     *
     * <p>{@code AGENT_JS} is idempotent ({@code if(window.__smAgent)return}),
     * so broadcasting it is safe to repeat, which is what makes the frame-set
     * watcher in {@link #pumpAgent()} harmless.
     */
    private void executeInAllFrames(String code) {
        CefBrowser cef = cef();
        if (cef == null) {
            return;
        }
        cef.executeJavaScript(code, cef.getURL(), 0);
        Vector<String> ids = frameIdentifiers(cef);
        int extra = 0;
        for (String id : ids) {
            if (extra >= MAX_FRAMES) {
                LOGGER.warn("page has more than {} frames, the rest are ignored", MAX_FRAMES);
                break;
            }
            CefFrame frame = cef.getFrameByIdentifier(id);
            if (frame == null || frame.isMain()) {
                continue;
            }
            frame.executeJavaScript(code, frame.getURL(), 0);
            extra++;
        }
        // Always record the count, subframes or not. It used to be set only when
        // extra > 0, so on a page with no subframes it stayed 0 while
        // getFrameCount() said 1 - the watcher then saw a change every two
        // seconds and re-injected forever. The 23:41 run did exactly that,
        // 23 re-injections and 64 gain pushes for one screen.
        knownFrames = ids.size();
    }

    /** Frame count seen at the last injection, so a change can be noticed. */
    private volatile int knownFrames;

    private void injectAgent() {
        CefBrowser cef = cef();
        if (cef != null) {
            // frameCount is here because "the agent cannot see the video" has two
            // very different causes and this tells them apart: 1 means the video
            // should have been found in the main frame and something else is
            // wrong, 2 or more means the video is probably in an iframe.
            LOGGER.info("INJECT agent, frames={}, url={}", cef.getFrameCount(), cef.getURL());
            executeInAllFrames(AGENT_JS);
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
        // The built-in test page is a marker, not a real address. It becomes a
        // data URL so there is no file to write and nothing to clean up. It
        // still goes through the normal load path, so the agent, the handlers
        // and the texture all behave exactly as they do for any other page.
        String target = TestPage.URL.equalsIgnoreCase(url) ? TestPage.dataUrl() : url;
        currentUrl = target;
        playbackTime = -1;
        appliedGain = -1.0;
        cef.loadURL(target);
        applyingRemote = false;
    }

    public void js(String code) {
        executeInAllFrames(code);
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

    /**
     * Browser-level shortcuts that script cannot do.
     *
     * <p>History is the reason this exists. {@code Ctrl+A/C/V/X/Z/Y} are handled
     * in the page's editing shim, where the focused frame is known and the
     * modifier state is already in hand, but back and forward are properties of
     * the browser, not of a document. {@link CefBrowser#goBack} and
     * {@link CefBrowser#goForward} are public API and were verified with javap,
     * so no mixin is involved.
     *
     * @return true when the event was consumed and must not reach the page
     */
    public boolean handleShortcut(KeyEvent event) {
        CefBrowser cef = cef();
        if (cef == null || event == null) {
            return false;
        }
        // InputWithModifiers already answers these, verified with javap; they know
        // about the control-side quirk that a raw mask comparison would miss.
        // There is no hasSuperDown, so the Windows key is not screened out - it
        // is not bound to anything the browser acts on.
        if (event.hasAltDown() && !event.hasControlDown() && !event.hasShiftDown()) {
            if (event.key() == InputConstants.KEY_LEFT) {
                goBack();
                LOGGER.info("SHORTCUT alt+left -> goBack");
                return true;
            }
            if (event.key() == InputConstants.KEY_RIGHT) {
                if (cef.canGoForward()) {
                    cef.goForward();
                    LOGGER.info("SHORTCUT alt+right -> goForward");
                } else {
                    LOGGER.info("SHORTCUT alt+right ignored, no forward history");
                }
                return true;
            }
        }

        // Ctrl combos through the public CefFrame API, verified with javap.
        // The page shim already tries these with execCommand, but JCEF's
        // offscreen path delivers KEY_PRESSED as a raw keydown, which by
        // design triggers no default editing action - so the shim's
        // preventDefault has nothing to prevent and the browser never acts.
        // Doing it here means the command goes through the browser instead of
        // through a script in a frame that may not be the one holding focus.
        if (event.hasControlDown() && !event.hasAltDown()) {
            CefFrame frame = cef.getFocusedFrame();
            if (frame == null) {
                frame = cef.getMainFrame();
            }
            if (frame == null) {
                return false;
            }
            String action = ctrlAction(event);
            if (action == null) {
                return false;
            }
            switch (action) {
                case "selectAll" -> frame.selectAll();
                case "copy" -> frame.copy();
                case "cut" -> frame.cut();
                case "paste" -> frame.paste();
                case "undo" -> frame.undo();
                case "redo" -> frame.redo();
                default -> {
                    return false;
                }
            }
            LOGGER.info("SHORTCUT ctrl -> {}", action);
            return true;
        }
        return false;
    }

    /**
     * Which CefFrame editing command a Ctrl combo means, or null.
     *
     * <p>Shift matters for exactly one of them: Ctrl+Z is undo and Ctrl+Shift+Z
     * is redo, as on every browser. Reading the key alone would make both undo.
     */
    private static String ctrlAction(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_A) {
            return "selectAll";
        }
        if (key == InputConstants.KEY_C) {
            return "copy";
        }
        if (key == InputConstants.KEY_X) {
            return "cut";
        }
        if (key == InputConstants.KEY_V) {
            return "paste";
        }
        if (key == InputConstants.KEY_Z) {
            return event.hasShiftDown() ? "redo" : "undo";
        }
        if (key == InputConstants.KEY_Y) {
            return "redo";
        }
        return null;
    }

    /**
     * The character a control key stands for, or 0 when the key has none.
     *
     * <p>The values are the ASCII control codes, which is what a page editing
     * command actually looks for: {@code \b} deletes backwards, {@code \r}
     * submits, {@code \t} moves focus.
     */
    private static int controlCharacter(KeyEvent event) {
        if (event.hasControlDown() || event.hasAltDown()) {
            return 0;   // Ctrl+Backspace and friends are not plain editing
        }
        int key = event.key();
        if (key == InputConstants.KEY_BACKSPACE) {
            return 8;   // \b
        }
        if (key == InputConstants.KEY_RETURN) {
            return 13;  // \r - not 10: 10 is the numpad enter, which is what
        }              // mcef-modern's own table maps KEY_RETURN to
        if (key == InputConstants.KEY_TAB) {
            return 9;   // \t
        }
        if (key == InputConstants.KEY_DELETE) {
            return 127; // DEL
        }
        return 0;
    }

    /**
     * Delivers a control key as the character it stands for.
     *
     * <p>Why this exists. Everything that works today in the embedded browser
     * goes through {@code charTyped}: letters, digits, punctuation. Backspace
     * and Enter do not, and they do not work. The reason is visible in the
     * platform change rather than in this mod. 1.21.4 had GLFW, whose text
     * callback also reported the control characters; 26.3 uses SDL3, whose
     * text input only ever carries printable characters. So a key like
     * Backspace now reaches the game as a key event and never as a character,
     * and on top of that mcef-modern's own SDL scancode table sends KEY_RETURN
     * as AWT's VK_ENTER (10) instead of VK_RETURN (13), which is the numpad
     * enter, not the one you press.
     *
     * <p>Sending the character explicitly routes these keys down the one path
     * that is known to reach the page. It is a fallback, not a replacement: the
     * raw key event is still sent too, so a key that does work keeps working.
     *
     * @return true when a character was sent for this key
     */
    public boolean sendControlCharacter(KeyEvent event) {
        int codepoint = controlCharacter(event);
        if (codepoint == 0) {
            return false;
        }
        MCEFBrowser b = raw();
        if (b == null) {
            return false;
        }
        LOGGER.info("SYNTH CHAR key={} cp={} '{}'",
                keyName(event), codepoint, new Character((char) codepoint).toString());
        b.onCharTyped(new CharacterEvent(codepoint));
        return true;
    }

    /** Readable name for a key, so the log says BACKSPACE instead of 42. */
    private static String keyName(KeyEvent event) {
        try {
            return InputConstants.getKey(event).getName();
        } catch (RuntimeException ex) {
            return "raw-" + event.key();
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
