import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefBrowserSettings;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserFactory;

import org.cef.SystemBootstrap;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Does one browser on a machine with no screen actually work, and what does a
 * frame cost?
 *
 * <p>This is the spike for server-side rendering. Everything it measures is a
 * number that decides the architecture:
 *
 * <ul>
 *   <li>Can JCEF render a page with no display at all? That is the whole idea -
 *       the friends' Linux server has no X11, no Wayland and no Xvfb.</li>
 *   <li>How long does one screenshot take? At 15 frames a second, a capture
 *       costing more than 66ms is more than one core of budget.</li>
 *   <li>How many bytes is a frame as JPEG, per resolution and per quality? That
 *       is the bandwidth, and it decides whether this needs FFmpeg at all. The
 *       JDK's ImageIO writes JPEG with no native dependency, so if the numbers
 *       are sane the expensive part of the plan can be dropped.</li>
 *   <li>How often does the frame actually change? A page that is still most of
 *       the time only needs sending when it moves.</li>
 * </ul>
 *
 * <p>No Minecraft anywhere in here, on purpose. If this cannot work the project
 * is dead, and that should be found out in an afternoon rather than a month.
 *
 * <p>Run it with the JCEF natives on the library path:
 * <pre>
 * java -Djava.library.path=&lt;jcefDir&gt; -cp jcef-api.jar SpikeServerBrowser \
 *      &lt;jcefDir&gt; &lt;url&gt; &lt;seconds&gt; &lt;outDir&gt;
 * </pre>
 */
public final class SpikeServerBrowser {

    /** Frame sizes worth pricing, as width x height. */
    private static final int[][] SIZES = {{1280, 720}, {854, 480}, {640, 360}, {480, 270}};
    /** JPEG qualities worth pricing. */
    private static final int[] QUALITIES = {80, 60, 40, 25};

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("uso: SpikeServerBrowser <jcefDir> <url> <segundos> <outDir>");
            System.exit(2);
            return;
        }
        String jcefDir = args[0];
        String url = args[1];
        int seconds = Integer.parseInt(args[2]);
        File outDir = new File(args[3]);
        outDir.mkdirs();

        AtomicLong pumpRequests = new AtomicLong();
        System.out.println("=== SPICE: um browser, sem ecra ===");
        System.out.println("  jcef   : " + jcefDir);
        System.out.println("  url    : " + url);
        System.out.println("  ficheiros em : " + outDir.getAbsolutePath());
        System.out.println();

        // Which native library to load, said out loud.
        //
        // The default loader calls System.loadLibrary("cef"), which on Linux
        // resolves to libcef.so. That is the Chromium core: 349 of the 349
        // Java_org_cef symbols are in libjcef.so and libcef.so has none, so
        // the JVM loads a library with no JNI in it and every native call
        // fails with UnsatisfiedLinkError on N_PreInitialize. On Windows the
        // same thing happens with jcef.dll against libcef.dll.
        //
        // So: load the JNI library by absolute path, and fall back to the
        // default behaviour rather than dying if the guess about the platform
        // is wrong.
        final String jcefDirFinal = jcefDir;
        SystemBootstrap.setLoader(new SystemBootstrap.Loader() {
            @Override
            public void loadLibrary(String name) {
                String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
                String file = os.contains("win") ? "jcef.dll"
                        : os.contains("mac") ? "libjcef.dylib"
                        : "libjcef.so";
                File candidate = new File(jcefDirFinal, file);
                if (candidate.isFile()) {
                    System.out.println("  [cef] a carregar " + candidate.getAbsolutePath());
                    System.load(candidate.getAbsolutePath());
                    return;
                }
                System.out.println("  [cef] " + file + " nao esta em " + jcefDirFinal
                        + ", a tentar pelo nome");
                System.loadLibrary(name);
            }
        });

        // Start the CEF the way MCEF starts it, which is the way that works.
        //
        // This is the third version of this block and the first that uses the
        // real API. The first called CefApp.getInstance(args, settings) by hand
        // and the JVM died with SIGTRAP and no message at all, in native code,
        // on Linux and on Windows alike - and the JVM's own crash handler said
        // nothing useful either. The second tried to fix the library loading and
        // got the JNI library right, and still died in exactly the same place.
        //
        // The reason is that CefApp.getInstance is not the entry point. The
        // right one is me.friwi.jcefmaven.CefAppBuilder, which is in a
        // different jar (jcefmaven, not jcef-api) and which does the whole
        // bootstrap through its CefInitializer step: the install directory, the
        // library path, the arguments, the progress handler. MCEF uses it, and
        // MCEF works - the game runs, on this exact native bundle. Building the
        // CefApp by hand skipped all of that, and the native side died.
        System.out.println("  a arrancar o CEF pelo CefAppBuilder...");
        me.friwi.jcefmaven.CefAppBuilder builder = new me.friwi.jcefmaven.CefAppBuilder();
        // The install dir is the directory that holds libjcef.so directly. The
        // builder's CefInitializer loads it from <installDir>/libjcef.so, and
        // pointing it at the parent gives a clean "Can't load library:
        // .../bin/lib/libjcef.so" - which is a much better error than the one
        // it gives when it does find the library and something else goes wrong.
        File installDir = new File(jcefDir);
        builder.setInstallDir(installDir);
        System.out.println("  install dir: " + installDir);
        // The bundle is already there. Without this the builder tries to
        // download it.
        builder.setSkipInstallation(true);
        // The same three arguments MCEF passes. The autoplay one matters for
        // video, the widevine one for DRM playback, and disable-web-security
        // is what MCEF does, so it is what the working configuration does.
        builder.addJcefArgs(
                "--autoplay-policy=no-user-gesture-required",
                "--disable-web-security",
                "--enable-widevine-cdm");
        // The builder owns the CefSettings. Windowless is the whole point:
        // this is a server with no display.
        //
        // Deliberately NOT setting resources_dir_path and locales_dir_path.
        // MCEF does not set them either, and the hs_err from the Windows run
        // says the crash is inside libcef.dll during N_Initialize - the
        // Chromium side refusing to start. Pointing those at a directory that
        // does not have the layout Chromium expects is exactly the kind of
        // thing that makes it CHECK-fail and take the JVM's own exception
        // handler down with it, which is the crash we got. Let the builder
        // work the layout out from the install dir, the way the working
        // configuration does.
        builder.getCefSettings().log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
        builder.setAppHandler(new me.friwi.jcefmaven.MavenCefAppHandlerAdapter() {
            @Override
            public void onScheduleMessagePumpWork(long delayMs) {
                pumpRequests.incrementAndGet();
            }

            @Override
            public void onContextInitialized() {
                System.out.println("  [cef] contexto pronto");
            }
        });

        long t0 = System.nanoTime();
        CefApp app;
        try {
            app = builder.build();
        } catch (Exception ex) {
            System.out.println("  [cef] build() falhou: " + ex);
            ex.printStackTrace(System.out);
            System.exit(1);
            return;
        }
        System.out.println("  [cef] arrancado em " + ms(t0) + " ms, estado="
                + CefApp.getState());

        // The settings go in AFTER the build, the way MCEF does it, and on a
        // fresh CefSettings rather than the builder's.
        //
        // This ordering is not cosmetic. Setting them on the builder before
        // build() left the browser windowed, and a windowed browser cannot be
        // screenshotted: createScreenshot threw "UnsupportedOperationException:
        // Unsupported for windowed rendering" ninety-eight times, which is the
        // whole reason the spike reported no frames. Windowless rendering is
        // what makes a screen of a machine with no display possible at all.
        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = true;
        settings.root_cache_path = new File(outDir, "jcef-cache").getAbsolutePath();
        settings.user_agent_product = "ScreenMine-Spike/0";
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
        app.setSettings(settings);

        CefClient client = app.createClient();

        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(org.cef.browser.CefBrowser browser,
                                  org.cef.browser.CefFrame frame, int status) {
                System.out.println("  [cef] pagina carregada, http=" + status
                        + " url=" + browser.getURL());
            }

            @Override
            public void onLoadError(org.cef.browser.CefBrowser browser,
                                    org.cef.browser.CefFrame frame,
                                    org.cef.handler.CefLoadHandler.ErrorCode code,
                                    String text, String failedUrl) {
                System.out.println("  [cef] ERRO ao carregar " + code + " " + text);
            }
        });

        CefBrowserSettings browserSettings = new CefBrowserSettings();
        // 15 fps: enough for a screen showing a video, and it is the number the
        // bandwidth table is priced at.
        browserSettings.windowless_frame_rate = 15;

        CefBrowser browser = CefBrowserFactory.create(
                client, url, false, true, null, browserSettings);
        if (browser == null) {
            System.out.println("  [cef] createBrowser devolveu null");
            System.exit(1);
            return;
        }
        System.out.println("  [cef] browser criado, id=" + browser.getIdentifier());
        System.out.println();

        // The pump. Without this the browser never loads anything.
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        List<Shot> shots = new ArrayList<>();
        int saved = 0;
        int tick = 0;
        int attempts = 0;
        String lastUrl = "";
        while (System.currentTimeMillis() < deadline) {
            app.doMessageLoopWork(10);
            if (++tick % 20 != 0) {
                Thread.sleep(10);
                continue;
            }
            // Do not skip while the page is still loading. A page that never
            // stops loading - a YouTube embed with a video playing certainly
            // does not - would then never be captured at all, and the probe
            // would report "no frames" for a browser that is working fine.
            // The screenshot itself is the test.
            attempts++;
            long shotStart = System.nanoTime();
            BufferedImage img;
            try {
                img = browser.createScreenshot(false).get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                if (attempts <= 3) {
                    System.out.println("  [shot] ainda nao: " + ex);
                }
                continue;
            }
            long tookMs = ms(shotStart);
            if (img == null) {
                if (attempts <= 3) {
                    System.out.println("  [shot] devolveu null");
                }
                continue;
            }
            String pageUrl = browser.getURL();
            if (!pageUrl.equals(lastUrl)) {
                lastUrl = pageUrl;
                System.out.println("  [shot] o browser esta em: " + pageUrl);
            }
            shots.add(price(img, tookMs));
            if (saved < 2) {
                File png = new File(outDir, "frame-" + saved + ".png");
                ImageIO.write(img, "png", png);
                System.out.println("  [shot] " + png.getName() + "  "
                        + img.getWidth() + "x" + img.getHeight()
                        + "  " + tookMs + " ms  " + png.length() / 1024 + " KB (png)");
                saved++;
            }
        }
        System.out.println("  [shot] tentativas: " + attempts
                + ", frames capturados: " + shots.size());

        report(shots, browser);
        System.out.println();
        System.out.println("  [cef] a fechar");
        browser.close(true);
        app.dispose();
        System.exit(0);
    }

    /** One captured frame, with every price already worked out. */
    private record Shot(int w, int h, long tookMs, long pngBytes,
                        long[][] jpegBytes, byte[] thumb) {

        /** True when this frame is visually the same as the previous one. */
        boolean sameAs(Shot o) {
            return thumb.length == o.thumb.length
                    && java.util.Arrays.equals(thumb, o.thumb);
        }
    }

    private static Shot price(BufferedImage img, long tookMs) throws Exception {
        int w = img.getWidth();
        int h = img.getHeight();
        long png;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ImageIO.write(img, "png", sink);
        png = sink.size();

        long[][] jpeg = new long[SIZES.length][QUALITIES.length];
        for (int s = 0; s < SIZES.length; s++) {
            BufferedImage small = scale(img, SIZES[s][0], SIZES[s][1]);
            for (int q = 0; q < QUALITIES.length; q++) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                javax.imageio.ImageWriter writer =
                        javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
                writer.setOutput(javax.imageio.ImageIO.createImageOutputStream(out));
                javax.imageio.ImageWriteParam p = writer.getDefaultWriteParam();
                if (p.canWriteCompressed()) {
                    p.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                    p.setCompressionQuality(QUALITIES[q] / 100f);
                }
                writer.write(null, new javax.imageio.IIOImage(small, null, null), p);
                jpeg[s][q] = out.size();
            }
        }

        // A crude "did it move" fingerprint: 16x9 greyscale, sampled.
        byte[] thumb = new byte[16 * 9];
        BufferedImage small = scale(img, 16, 9);
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int rgb = small.getRGB(x * small.getWidth() / 16, y * small.getHeight() / 9);
                int grey = (((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff)) / 3;
                thumb[y * 16 + x] = (byte) (grey / 8);
            }
        }
        return new Shot(w, h, tookMs, png, jpeg, thumb);
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static void report(List<Shot> shots, CefBrowser browser) {
        if (shots.isEmpty()) {
            System.out.println("=== NENHUM frame capturado ===");
            System.out.println("  isto e' a resposta mais importante do spike: nao ha video.");
            return;
        }
        Shot first = shots.get(0);
        int moved = 0;
        for (int i = 1; i < shots.size(); i++) {
            if (!shots.get(i).sameAs(shots.get(i - 1))) {
                moved++;
            }
        }

        long avgTook = 0;
        for (Shot s : shots) {
            avgTook += s.tookMs();
        }
        avgTook /= shots.size();

        System.out.println("=== O QUE CUSTA UM FRAME ===");
        System.out.println("  frames capturados : " + shots.size());
        System.out.println("  resolucao        : " + first.w() + "x" + first.h());
        System.out.println("  custo medio      : " + avgTook + " ms por screenshot");
        System.out.println("  frames que mudaram: " + moved + " de " + (shots.size() - 1));
        System.out.println("  url actual       : " + browser.getURL());
        System.out.println();
        System.out.println("=== LARGURA DE BANDA (KB/s por cliente, compressao JPEG) ===");
        System.out.printf("  %-11s %-4s %10s %10s %10s%n", "tamanho", "q", "10 fps", "15 fps", "20 fps");
        for (int s = 0; s < SIZES.length; s++) {
            for (int q = 0; q < QUALITIES.length; q++) {
                long avg = 0;
                for (Shot shot : shots) {
                    avg += shot.jpegBytes()[s][q];
                }
                avg /= shots.size();
                System.out.printf("  %-11s %-4d %10.1f %10.1f %10.1f%n",
                        SIZES[s][0] + "x" + SIZES[s][1], QUALITIES[q],
                        kb(avg * 10.0), kb(avg * 15.0), kb(avg * 20.0));
            }
        }
        System.out.println();
        System.out.println("=== NIVEIS QUE FAZEM SENTIDO ===");
        for (int s = 0; s < SIZES.length; s++) {
            for (int q = 0; q < QUALITIES.length; q++) {
                long avg = 0;
                for (Shot shot : shots) {
                    avg += shot.jpegBytes()[s][q];
                }
                avg /= shots.size();
                double kbs = kb(avg * 15.0);
                if (kbs < 400) {
                    System.out.printf("  %s q=%-3d %6.1f KB/frame %7.1f KB/s a 15 fps%n",
                            SIZES[s][0] + "x" + SIZES[s][1], QUALITIES[q],
                            avg / 1024.0, kbs);
                }
            }
        }
    }

    private static double kb(double bytes) {
        return bytes / 1024.0;
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private SpikeServerBrowser() {
    }
}