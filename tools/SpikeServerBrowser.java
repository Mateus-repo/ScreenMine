import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefBrowserSettings;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserFactory;

import org.cef.handler.CefAppHandlerAdapter;
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

        System.out.println("=== SPICE: um browser, sem ecra ===");
        System.out.println("  jcef   : " + jcefDir);
        System.out.println("  url    : " + url);
        System.out.println("  ficheiros em : " + outDir.getAbsolutePath());
        System.out.println();

        // Windowless: this is what makes a server with no display work at all.
        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = true;
        settings.cache_path = new File(outDir, "jcef-cache").getAbsolutePath();
        settings.resources_dir_path = jcefDir;
        settings.locales_dir_path = jcefDir;
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;

        // No runMessageLoop() in this build: the embedder drives the pump itself
        // and CEF asks to be called back through onScheduleMessagePumpWork.
        AtomicLong pumpRequests = new AtomicLong();
        CefAppHandlerAdapter handler = new CefAppHandlerAdapter(new String[0]) {
            @Override
            public void onScheduleMessagePumpWork(long delayMs) {
                pumpRequests.incrementAndGet();
            }

            @Override
            public void onContextInitialized() {
                System.out.println("  [cef] contexto pronto");
            }
        };

        // addAppHandler first: there is no getInstance(handler, settings) in this
        // build, so the handler has to be registered before the instance exists.
        CefApp.addAppHandler(handler);
        System.out.println("  a arrancar o CEF...");
        long t0 = System.nanoTime();
        CefApp app = CefApp.getInstance(new String[0], settings);
        System.out.println("  [cef] arrancado em " + ms(t0) + " ms, estado="
                + CefApp.getState());

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
        while (System.currentTimeMillis() < deadline) {
            app.doMessageLoopWork(10);
            if (++tick % 20 != 0) {
                Thread.sleep(10);
                continue;
            }
            if (!browser.hasDocument() || browser.isLoading()) {
                continue;
            }
            long shotStart = System.nanoTime();
            BufferedImage img;
            try {
                img = browser.createScreenshot(false).get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                System.out.println("  [shot] falhou: " + ex);
                continue;
            }
            long tookMs = ms(shotStart);
            if (img == null) {
                System.out.println("  [shot] devolveu null");
                continue;
            }
            shots.add(price(img, tookMs));
            // Keep two pictures on disk so a human can look and confirm this is
            // really the page and not a grey rectangle.
            if (saved < 2) {
                File png = new File(outDir, "frame-" + saved + ".png");
                ImageIO.write(img, "png", png);
                System.out.println("  [shot] " + png.getName() + "  "
                        + img.getWidth() + "x" + img.getHeight()
                        + "  " + tookMs + " ms  " + png.length() / 1024 + " KB (png)");
                saved++;
            }
        }

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