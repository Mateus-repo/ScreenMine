package dev.screenmine.browser;

/**
 * A page used to test keyboard input, built into the mod and reachable at
 * {@code screenmine:test}.
 *
 * <p>It exists because YouTube is a bad test bench: it is a single-page app
 * that installs its own key handlers, rewrites document.title, swallows keys
 * and lives in frames. Every keyboard question asked so far had YouTube in the
 * middle of it, so a failure never said where.
 *
 * <p>This page has no framework, no frames and no opinion about keys. It
 * reports what actually arrived, so one run answers: does the key event
 * arrive, does the character event arrive, does the editing shim fire, do the
 * Ctrl shortcuts fire, and does a native select open at all.
 */
public final class TestPage {

    /** Typed into the address bar. Recognised by {@link BrowserInstance}. */
    public static final String URL = "screenmine:test";

    private TestPage() {
    }

    /**
     * Writes the page to {@code <config>/screenmine/testpage.html} and returns a
     * file URL for it.
     *
     * <p>A file, not a data: URL, because this JCEF build will not navigate to
     * one. The 2026-10-16 12:09 run asked for
     * {@code data:text/html;base64,…} and the browser stayed on
     * {@code about:blank}: onLoadEnd never fired and the screen stayed grey.
     * The only evidence was the agent reporting {@code href=about:blank},
     * because the log recorded what we asked for and never what we got.
     *
     * <p>On disk it has a second use: the path is logged, so the same page can
     * be opened in a normal browser and the two compared.
     */
    public static String fileUrl() {
        java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve("screenmine");
        java.nio.file.Path file = dir.resolve("testpage.html");
        try {
            java.nio.file.Files.createDirectories(dir);
            // Written on every visit, not only when missing: the page is part
            // of the mod, so it should follow the mod, not a stale copy.
            java.nio.file.Files.writeString(file, html(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("could not write the test page to " + file, ex);
        }
        return file.toUri().toString();
    }

    /** Where {@link #fileUrl()} writes it, for the log. */
    public static String path() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve("screenmine").resolve("testpage.html").toString();
    }

    private static String html() {
        return """
                <!doctype html>
                <html><head><meta charset="utf-8"><title>ScreenMine test</title>
                <style>
                  body{font:14px monospace;background:#181818;color:#eee;padding:16px}
                  h2{font-size:15px;margin:18px 0 6px;color:#8cf}
                  input,textarea,select{font:14px monospace;background:#fff;color:#000;
                        border:2px solid #888;padding:6px;width:420px}
                  textarea{height:70px}
                  #log{background:#0b0b0b;border:1px solid #444;height:230px;
                       overflow:auto;padding:8px;white-space:pre;font-size:12px}
                  .k{color:#6f6}.ok{color:#6f6}.bad{color:#f66}
                </style></head>
                <body>
                <h2>1. typing</h2>
                <input id="in" size="42" placeholder="click here and type">
                <h2>2. long text + caret</h2>
                <textarea id="ta">the quick brown fox jumps over the lazy dog 0123456789</textarea>
                <h2>3. native select (Bug D)</h2>
                <select id="sel">
                  <option value="">(none)</option>
                  <option value="av1">AV1</option>
                  <option value="vp9">VP9</option>
                  <option value="av01-opus">AV1 + Opus</option>
                  <option value="vp9-opus">VP9 + Opus</option>
                </select>
                <span id="selv">current: (open the list)</span>
                <h2>4. video element</h2>
                <video id="v" width="320" controls></video>
                <span id="vst">no source</span>
                <h2>5. what arrived</h2>
                <div id="log"></div>
                <script>
                var L=document.getElementById('log'),n=0;
                function say(s,cls){n++;L.textContent=('%02d '%n)+s+'\\n'+L.textContent;}
                function ed(e){var t=e.target;return t&&(t.tagName==='INPUT'||t.tagName==='TEXTAREA');}
                function desc(e){
                  var t=e.target;
                  return (t&&(t.id||t.tagName))+' key='+JSON.stringify(e.key)
                    +' code='+e.code+' keyCode='+e.keyCode+' ctrl='+e.ctrlKey
                    +' shift='+e.shiftKey+' alt='+e.altKey
                    +' isComposing='+e.isComposing;
                }
                document.addEventListener('keydown',function(e){say('KEYDOWN   '+desc(e));},true);
                document.addEventListener('keyup',function(e){say('KEYUP    '+desc(e));},true);
                document.addEventListener('keypress',function(e){
                  say('KEYPRESS '+desc(e)+' char='+JSON.stringify(String.fromCharCode(e.keyCode||0)));},true);
                document.addEventListener('beforeinput',function(e){
                  say('BEFOREIN '+desc(e)+' data='+JSON.stringify(e.data));},true);
                document.addEventListener('input',function(e){say('INPUT    '+desc(e));},true);
                var sel=document.getElementById('sel'),selv=document.getElementById('selv');
                function show(){selv.textContent='current: '+sel.value;}
                sel.addEventListener('change',show); sel.addEventListener('input',show);
                // Bug D: a lista nativa nao aparece no browser embebido. Esta
                // e a receita que a resolve, e serve tambem como teste: se o
                // select abrir sozinho, o truque ainda nao foi necessario.
                sel.addEventListener('mousedown',function(e){
                  if(sel.size>1)return;
                  e.preventDefault();
                  sel.size=Math.min(sel.options.length,8);
                  say('SELECT aberto via size='+sel.size,'ok');
                },true);
                document.addEventListener('click',function(e){
                  if(e.target===sel)return;
                  if(sel.size>1){sel.size=1;say('SELECT fechado');}
                });
                var v=document.getElementById('v'),vst=document.getElementById('vst');
                function vstat(){
                  vst.textContent='volume='+v.volume.toFixed(2)
                    +' paused='+(v.paused?'1':'0')+' readyState='+v.readyState
                    +' error='+(v.error?v.error.code:'none');
                }
                setInterval(vstat,1000); vstat();
                window.addEventListener('error',function(e){say('PAGE ERROR '+e.message,'bad');});
                say('pagina de teste do ScreenMine carregada. Clica no campo e escreve.');
                </script></body></html>
                """;
    }
}