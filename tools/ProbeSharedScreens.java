import dev.screenmine.mp.RateLimiter;
import dev.screenmine.mp.ScreenRules;
import dev.screenmine.mp.SharedScreens;

import java.util.List;
import java.util.UUID;

/**
 * Testa as regras do servidor sem correr Minecraft nenhum.
 *
 * <p>Existe porque cada regra aqui tem um modo de falhar silencioso. Um sufixo
 * de dominio mal feito aceita "notyoutube.com" e ninguem ve nada de errado. Um
 * limite de taxa a mais deixa um jogador legitimo mandar com o servidor em
 * pe. Um NaN passa todas as comparacoes de intervalo, porque "NaN < qualquer
 * coisa" e' falso, e depois vai para o estado e para todos os clientes.
 *
 * <p>As classes testadas nao tocam em nenhuma classe do Minecraft, por isso isto
 * corre num JVM de.SECONDS. O que se decide aqui fica decidido antes de um
 * cliente abrir um mundo.
 *
 * <p>Corre: java -cp build/classes/java/main tools/ProbeSharedScreens.java
 */
public class ProbeSharedScreens {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        urlChecks();
        hostChecks();
        sizeChecks();
        positionChecks();
        ownership();
        quota();
        spam();
        disconnect();
        rateLimiter();
        dimension();
        distance();

        System.out.println();
        System.out.println(pass + " passaram, " + fail + " falharam");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ URLs //

    private static void urlChecks() {
        section("URL");
        accept("https://www.youtube.com/watch?v=abc", null);
        accept("https://youtu.be/abc", null);
        reject("http://www.youtube.com/watch?v=abc", "url-not-https");
        reject("", "url-empty");
        reject("   ", "url-empty");
        reject("https://", "url-unparseable");
        reject("/relative/path", "url-not-https");
        reject("javascript:alert(1)", "url-not-https");
        reject("https://evil.example/x", "url-host-not-allowed");
        reject("x".repeat(ScreenRules.MAX_URL_LENGTH + 1), "url-too-long");
        reject("ab", "url-too-short");
    }

    private static void hostChecks() {
        section("dominio: o teste que nao pode falhar");
        // Exactos.
        allow("youtube.com", true);
        allow("www.youtube.com", true);
        allow("m.youtube.com", true);
        allow("music.youtube.com", true);
        allow("youtu.be", true);
        allow("YOUTUBE.COM", true);           // maiusculas
        // Estos e' que um endsWith sem ponto aceitaria. E' o ataque.
        allow("notyoutube.com", false);
        allow("myyoutube.com", false);
        allow("youtube.com.evil.example", false);
        allow("evil.example/youtube.com", false);
        allow("youtube.computer", false);
        allow("", false);
        allow(null, false);
    }

    private static void sizeChecks() {
        section("tamanho");
        okSize(6.0f, 3.375f, null);
        okSize(1.0f, 1.0f, null);             // exactamente o minimo
        okSize(32.0f, 32.0f, null);           // exactamente o maximo
        okSize(0.5f, 6.0f, "size-too-small");
        okSize(64.0f, 6.0f, "size-too-large");
        okSize(Float.NaN, 6.0f, "size-not-finite");
        okSize(6.0f, Float.POSITIVE_INFINITY, "size-not-finite");
    }

    private static void positionChecks() {
        section("posicao");
        okPos(10.0, 64.0, -20.0, null);
        okPos(0.0, -500.0, 0.0, null);       // abaixo do mar, dentro do mundo
        okPos(Double.NaN, 64.0, 0.0, "position-not-finite");
        okPos(0.0, Double.POSITIVE_INFINITY, 0.0, "position-not-finite");
        okPos(0.0, 5000.0, 0.0, "position-out-of-build-height");
        okPos(0.0, -600.0, 0.0, "position-out-of-build-height");
        okPos(1e12, 0.0, 0.0, "position-out-of-world");
    }

    // ------------------------------------------------------------- dono etc. //

    private static void ownership() {
        section("dono");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        SharedScreens s = fresh();
        ScreenRules.Rejection[] why = new ScreenRules.Rejection[1];

        check("o dono pode navegar no seu ecra",
                s.navigate(placeOk(s, a, "A"), a, "https://youtu.be/x", 0, why) != null);
        check("outro jogador nao pode navegar", s.navigate(anyId(s), b,
                "https://youtu.be/x", 0, why) == null
                && "not-your-screen".equals(why[0].code()));
        check("e recusado com o mesmo codigo para um ecra inexistente",
                s.navigate(UUID.randomUUID(), b, "https://youtu.be/x", 0, why) == null
                && "not-your-screen".equals(why[0].code()));
        check("outro jogador nao pode remover", s.remove(anyId(s), b, 0, why) == null
                && "not-your-screen".equals(why[0].code()));
        check("o dono pode remover o seu", s.remove(anyId(s), a, 1000, why) != null);

        check("URL fora da lista e' recusada mesmo ao dono",
                s.navigate(placeOk(s, a, "A"), a, "https://evil.example", 2000, why) == null
                && "url-host-not-allowed".equals(why[0].code()));
    }

    private static void quota() {
        section("limite por jogador");
        UUID a = UUID.randomUUID();
        SharedScreens s = fresh();
        // Espacado pela janela de taxa de proposito: duas mensagens seguidas
        // dentro da janela seriam recusadas pela taxa, e o teste mediria a
        // taxa em vez da quota. Foi assim que uma versao anterior deste probe
        // "falhou" sem haver bug nenhum.
        long w = RateLimiter.WINDOW_MILLIS;
        check("o primeiro ecra e' aceite", place(s, a, 0) == null);
        check("o segundo e' aceite", place(s, a, w) == null);
        ScreenRules.Rejection why = place(s, a, 2 * w);
        check("o terceiro e' recusado", why != null && "too-many-screens".equals(why.code()));

        UUID b = UUID.randomUUID();
        check("outro jogador tem o seu proprio limite", place(s, b, 0) == null);
    }

    private static void spam() {
        section("frequencia");
        UUID a = UUID.randomUUID();
        // Quota alta de proposito: este teste e' sobre a taxa, e com o limite
        // normal de 2 ecracos o teste seguinte era recusado por quota em vez
        // de por frequencia - duas regras a dar a mesma resposta.
        SharedScreens s = fresh(100);
        int allowed = 0;
        ScreenRules.Rejection why = null;
        // Dentro da janela: PLACE_LIMIT mensagens seguidas, todas no instante 0.
        for (int i = 0; i < 12; i++) {
            why = place(s, a, 0);
            if (why == null) {
                allowed++;
            }
        }
        check("num instante so' passam PLACE_LIMIT mensagens",
                allowed == RateLimiter.PLACE_LIMIT, allowed + " passaram");
        check("a seguinte e' recusada por frequencia",
                why != null && "place-too-fast".equals(why.code()), String.valueOf(why));

        // Passada a janela, volta a passar.
        check("passada a janela volta a passar",
                place(s, a, RateLimiter.WINDOW_MILLIS) == null);
    }

    private static void disconnect() {
        section("o dono sai");
        UUID a = UUID.randomUUID();
        SharedScreens s = fresh();
        place(s, a, 0);
        place(s, a, RateLimiter.WINDOW_MILLIS);
        check("dois ecraso antes", s.countOf(a) == 2);
        List gone = s.removeAllOf(a);
        check("leva ambos consigo", gone.size() == 2);
        check("nao fica nenhum", s.count() == 0);
        check("os ids sao todos dele", s.all().stream().allMatch(x -> a.equals(x.owner())));
    }

    private static void rateLimiter() {
        section("RateLimiter com o relogio a mao");
        RateLimiter r = new RateLimiter(3, 1000);
        check("1", r.tryAcquire(0));
        check("2", r.tryAcquire(500));
        check("3", r.tryAcquire(999));
        check("o quarto e' recusado", !r.tryAcquire(999));
        check("passada a janela, passa", r.tryAcquire(1000));
        check("o primeiro instante nao estoura por overflow",
                new RateLimiter(1, 1000).tryAcquire(0));
    }

    // ------------------------------------------------------ dimensao/distancia //

    private static void dimension() {
        section("dimensao");
        UUID a = UUID.randomUUID();
        SharedScreens s = fresh();

        check("mesma dimensao e' aceite", place(s, a, 0, 1, 64, 2, 0, 64, 0, DIM) == null);
        ScreenRules.Rejection why =
                place(s, a, W(), 1, 64, 2, 0, 64, 0, "minecraft:the_nether");
        check("outra dimensao e' recusada", why != null
                && "wrong-dimension".equals(why.code()), String.valueOf(why));
        why = place(s, a, 2 * W(), 1, 64, 2, 0, 64, 0, "");
        check("dimensao vazia e' recusada", why != null
                && "dimension-empty".equals(why.code()), String.valueOf(why));
        why = place(s, a, 3 * W(), 1, 64, 2, 0, 64, 0, "x".repeat(200));
        check("dimensao gigante e' recusada", why != null
                && "dimension-too-long".equals(why.code()), String.valueOf(why));
    }

    private static void distance() {
        section("distancia ao jogador");
        UUID a = UUID.randomUUID();

        // 64 blocos: o limite, exacto, tem de passar.
        SharedScreens s1 = fresh();
        check("exactamente 64 blocos e' aceite",
                place(s1, a, 0, 64, 64, 0, 0, 64, 0, DIM) == null);

        SharedScreens s2 = fresh();
        ScreenRules.Rejection why = place(s2, a, 0, 64.1, 64, 0, 0, 64, 0, DIM);
        check("64.1 blocos e' recusado", why != null
                && "too-far-from-player".equals(why.code()), String.valueOf(why));

        // A diagonal conta: 100 blocos em x e 100 em z sao 141 de distancia.
        SharedScreens s3 = fresh();
        check("a diagonal conta, nao cada eixo",
                place(s3, a, 0, 100, 64, 100, 0, 64, 0, DIM) != null);

        // NaN na posicao do jogador tem de ser recusado e nao passar em silencio.
        SharedScreens s4 = fresh();
        why = place(s4, a, 0, 1, 64, 2, Double.NaN, 64, 0, DIM);
        check("jogador em NaN e' recusado", why != null
                && "player-position-not-finite".equals(why.code()), String.valueOf(why));

        SharedScreens s5 = fresh();
        why = place(s5, a, 0, 1, 64, 2, 0, 64, 0, DIM);
        check("lugar normal ainda passa depois de tudo isto", why == null);
    }

    private static long W() {
        return RateLimiter.WINDOW_MILLIS;
    }
    // ------------------------------------------------------------- ajudantes //


    private static SharedScreens fresh() {
        return fresh(ScreenRules.MAX_SCREENS_PER_PLAYER);
    }

    private static SharedScreens fresh(int maxPerPlayer) {
        return new SharedScreens(ScreenRules.DEFAULT_ALLOWED_HOSTS, maxPerPlayer);
    }

    private static final String DIM = "minecraft:overworld";

    private static ScreenRules.Rejection place(SharedScreens s, UUID who, long now) {
        return place(s, who, now, 1, 64, 2, 0, 64, 0, DIM);
    }

    private static ScreenRules.Rejection place(SharedScreens s, UUID who, long now,
                                               double x, double y, double z,
                                               double px, double py, double pz,
                                               String dim) {
        return s.place(who, "P", "https://youtu.be/x", dim, x, y, z,
                0f, 6f, 3.375f, px, py, pz, DIM, now);
    }

    private static UUID placeOk(SharedScreens s, UUID who, String name) {
        s.place(who, name, "https://youtu.be/x", DIM, 1, 64, 2,
                0f, 6f, 3.375f, 0, 64, 0, DIM, 0);
        return s.all().stream().filter(x -> who.equals(x.owner()))
                .reduce((x, y) -> y).orElseThrow().id();
    }

    private static UUID anyId(SharedScreens s) {
        return s.all().stream().findFirst().orElseThrow().id();
    }

    private static void section(String name) {
        System.out.println();
        System.out.println("== " + name);
    }

    private static void check(String what, boolean ok) {
        check(what, ok, null);
    }

    private static void check(String what, boolean ok, Object detail) {
        System.out.println((ok ? "PASS  " : "FALHOU ") + what
                + (ok || detail == null ? "" : "   -> " + detail));
        if (ok) {
            pass++;
        } else {
            fail++;
        }
    }

    private static void accept(String url, Object ignored) {
        check("aceita " + shortUrl(url), ruleOf(url) == null, ruleOf(url));
    }

    private static void reject(String url, String code) {
        ScreenRules.Rejection r = ruleOf(url);
        check("recusa " + shortUrl(url) + " (" + code + ")",
                r != null && r.code().equals(code), String.valueOf(r));
    }

    private static ScreenRules.Rejection ruleOf(String url) {
        return ScreenRules.checkUrl(
                url, ScreenRules.DEFAULT_ALLOWED_HOSTS);
    }

    private static void allow(String host, boolean expected) {
        boolean got = ScreenRules.hostAllowed(
                host, ScreenRules.DEFAULT_ALLOWED_HOSTS);
        check(("" + host).isEmpty() ? "host vazio -> nao" : "host " + host + " -> "
                + (expected ? "aceite" : "recusado"), got == expected);
    }

    private static void okSize(float w, float h, String code) {
        ScreenRules.Rejection r =
                ScreenRules.checkSize(w, h);
        check("tamanho " + w + "x" + h + (code == null ? " aceite" : " -> " + code),
                code == null ? r == null : r != null && r.code().equals(code),
                String.valueOf(r));
    }

    private static void okPos(double x, double y, double z, String code) {
        ScreenRules.Rejection r =
                ScreenRules.checkPosition(x, y, z);
        check("posicao " + x + "," + y + "," + z + (code == null ? " aceite" : " -> " + code),
                code == null ? r == null : r != null && r.code().equals(code),
                String.valueOf(r));
    }

    private static String shortUrl(String u) {
        return u.length() > 34 ? u.substring(0, 31) + "..." : "'" + u + "'";
    }
}