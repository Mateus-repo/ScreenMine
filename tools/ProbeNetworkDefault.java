import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Prova que o build local nao abre socket sem o jogador o pedir.
 *
 * <p>Isto existe porque "a rede esta desligada" e uma afirmacao, e afirmacoes
 * sobre portas e sockets nao se provam lendo o codigo. Cada caso corre numa
 * JVM nova, com o diretorio de trabalho posto no caso, e com um
 * config escrito a mao; o que se le e o que Cfg decide, que e exactamente o
 * que NetClient.start() usa para nao criar a thread.
 *
 * <p>O caso que importa e o terceiro: um host deixado no ficheiro de uma
 * sessao anterior nao pode ser suficiente para ligar a rede. E o que faria um
 * build local comecar a falar com um servidor sem ninguem pedir.
 *
 * <p>Porque uma JVM por caso: Settings le o ficheiro uma vez para um
 * Properties estatico, entao dentro do mesmo processo nao da para trocar de
 * ficheiro e ver o resultado.
 *
 * <p>Corre: java -cp build/classes/java/main tools/ProbeNetworkDefault.java
 */
public class ProbeNetworkDefault {

    private static int passed;
    private static int failed;

    /** Um caso: nome, o que se escreve no config, e o que se espera. */
    private record Case(String name, String config, boolean want) {
    }

    public static void main(String[] args) throws Exception {
        // Chamado de volta a partir de uma JVM nova, ja com o diretorio de
        // trabalho do caso. Imprime so o veredicto, para o processo pai ler.
        if (args.length > 0 && args[0].equals("case")) {
            System.out.print(dev.screenmine.Cfg.serverConfigured());
            return;
        }

        List<Case> cases = List.of(
                new Case("sem chave network.enabled", "server.host=exemplo.local\n", false),
                new Case("desligado, host vazio", "network.enabled=0\nserver.host=\n", false),
                new Case("desligado, host preenchido  <-- o caso perigoso",
                        "network.enabled=0\nserver.host=exemplo.local\n", false),
                new Case("valor invalido  <-- tem de falhar fechada",
                        "network.enabled=sim-por-favor\nserver.host=exemplo.local\n", false),
                new Case("valor vazio", "network.enabled=\nserver.host=exemplo.local\n", false),
                new Case("so espacos", "network.enabled=    \nserver.host=exemplo.local\n", false),
                new Case("ligado, host vazio", "network.enabled=1\nserver.host=\n", false),
                new Case("ligado e com host  <-- tem de ligar",
                        "network.enabled=1\nserver.host=exemplo.local\n", true),
                new Case("ligado com 'true'",
                        "network.enabled=true\nserver.host=exemplo.local\n", true),
                new Case("ligado com 'YES'",
                        "network.enabled=YES\nserver.host=exemplo.local\n", true),
                new Case("ligado com espacos a volta",
                        "network.enabled=  1  \nserver.host=exemplo.local\n", true));

        for (Case c : cases) {
            run(c);
        }

        System.out.println();
        System.out.println(passed + " passaram, " + failed + " falharam");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void run(Case c) throws Exception {
        Path dir = Files.createTempDirectory("sm-net-");
        // O nome do ficheiro vem do proprio Settings. Hardcoded, este probe
        // escrevia screenmine.properties enquanto o mod lia
        // screenmine_mp.properties: escrevia um ficheiro que ninguem lia e os
        // casos que passam a falhar pareciam bugs do interruptor.
        Path cfg = dir.resolve(dev.screenmine.Settings.file());
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, c.config());

        String out;
        try {
            out = child(dir);
        } catch (Exception ex) {
            System.out.printf("FALHOU  %-46s %s%n", c.name(), oneLine(ex.toString()));
            failed++;
            return;
        }
        boolean got = Boolean.parseBoolean(out);
        boolean ok = got == c.want();
        System.out.printf("%-7s %-46s esperado=%-5s obtido=%-5s%n",
                ok ? "PASS" : "FALHOU", c.name(), c.want(), got);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }

    private static String child(Path dir) throws Exception {
        Path self = Path.of("tools", "ProbeNetworkDefault.java").toAbsolutePath();
        // Cada entrada do classpath tem de ser absoluta: a JVM filha corre noutro
        // diretorio de trabalho, e um caminho relativo ai aponta para o sitio
        // errado. Falha com ClassNotFoundException, que nao diz nada sobre o que
        // se queria testar.
        //
        // Entrada a entrada, e nao o classpath inteiro como um caminho so: em
        // Windows o separador e' ';', que nao e' legal num caminho. Feito assim
        // o probe so corria com uma unica entrada no classpath e rebentava com
        // IllegalPathException em qualquer outro - 11 de 11 falhas que nao tinham
        // nada a ver com o que se estava a testar.
        String cp = java.util.Arrays.stream(
                        System.getProperty("java.class.path")
                                .split(java.util.regex.Pattern.quote(
                                        java.io.File.pathSeparator)))
                .filter(s -> !s.isBlank())
                .map(s -> Path.of(s).toAbsolutePath().normalize().toString())
                .collect(java.util.stream.Collectors.joining(
                        java.io.File.pathSeparator));
        List<String> cmd = new ArrayList<>(List.of("java", "-cp", cp, self.toString(), "case"));
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        if (p.waitFor() != 0) {
            throw new IllegalStateException(out.isEmpty() ? "processo terminou com erro" : out);
        }
        return out;
    }

    private static String oneLine(String s) {
        String flat = s.replace("\r", " ").replace("\n", " ");
        return flat.length() > 90 ? flat.substring(0, 90) + "..." : flat;
    }
}