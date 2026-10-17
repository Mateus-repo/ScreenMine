import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Confere a tabela de teclas que o CdpKeys envia.
 *
 * <p>Existe porque a tabela e' uma tabela escrita a mao e um valor trocado e'
 * silencioso: a tecla nao faz nada, em jogo, sem erro nenhum. O log diria
 * "CDP KEY sent" e o Backspace nao apagaria - que e' o mesmo sintoma de duas
 * tentativas que ja falharam.
 *
 * <p>Confere tambem que o JSON tem o formato certo e que o tipo de evento e'
 * rawKeyDown, nao char. Um rawKeyDown com "text" faz a pagina inserir texto em
 * vez de agir, que e' exactamente o que o Delete fez antes.
 *
 * <p>Corre: java -cp build/classes/java/main tools/ProbeCdpKeys.java
 */
public class ProbeCdpKeys {

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("dev.screenmine.browser.CdpKeys");
        Class<?> keyClass = Class.forName("dev.screenmine.browser.CdpKeys$Key");
        Method lookup = c.getDeclaredMethod("key", int.class);
        lookup.setAccessible(true);
        Method rawKeyDown = keyClass.getDeclaredMethod("rawKeyDown");
        rawKeyDown.setAccessible(true);
        Method keyUp = keyClass.getDeclaredMethod("keyUp");
        keyUp.setAccessible(true);
        Method vkOf = keyClass.getDeclaredMethod("vk");
        vkOf.setAccessible(true);

        // Scancodes lidos de InputConstants em 26.3 com javap -constants,
        // Virtual-Keys do Windows, e o nome/code que o DOM espera.
        String[][] expected = {
                {"BACKSPACE", "42", "Backspace", "Backspace", "8"},
                {"DELETE", "76", "Delete", "Delete", "46"},
                {"LEFT", "80", "ArrowLeft", "ArrowLeft", "37"},
                {"UP", "82", "ArrowUp", "ArrowUp", "38"},
                {"RIGHT", "79", "ArrowRight", "ArrowRight", "39"},
                {"DOWN", "81", "ArrowDown", "ArrowDown", "40"},
                {"HOME", "74", "Home", "Home", "36"},
                {"END", "77", "End", "End", "35"},
                {"PAGEUP", "75", "PageUp", "PageUp", "33"},
                {"PAGEDOWN", "78", "PageDown", "PageDown", "34"},
                // Nao devem ir por aqui: chegam como caracter.
                {"RETURN", "40", "-", "-", "-"},
                {"TAB", "43", "-", "-", "-"},
                {"A", "4", "-", "-", "-"},
                {"SPACE", "44", "-", "-", "-"},
        };

        List<String> fails = new ArrayList<>();
        System.out.printf("%-10s %6s %-12s %-12s %4s  %s%n",
                "tecla", "sdl", "key", "code", "vk", "json");
        for (String[] e : expected) {
            Object k = lookup.invoke(null, Integer.parseInt(e[1]));
            boolean shouldBeNull = "-".equals(e[2]);

            if (shouldBeNull) {
                boolean ok = k == null;
                System.out.printf("%-10s %6s %-12s %-12s %4s  %s%n",
                        e[0], e[1], "(nenhuma)", "(nenhuma)", "-",
                        ok ? "OK - nao vai por aqui" : "ERRADO - passou para o CDP");
                if (!ok) {
                    fails.add(e[0]);
                }
                continue;
            }

            if (k == null) {
                System.out.printf("%-10s %6s %-12s %-12s %4s  FALHOU - nao mapeada%n",
                        e[0], e[1], e[2], e[3], e[4]);
                fails.add(e[0]);
                continue;
            }

            // setAccessible: the record's fields are private final, and a probe
            // that cannot read them is a probe that cannot check anything.
            java.lang.reflect.Field keyField = keyClass.getDeclaredField("key");
            keyField.setAccessible(true);
            java.lang.reflect.Field codeField = keyClass.getDeclaredField("code");
            codeField.setAccessible(true);
            Object keyName = keyField.get(k);
            Object codeName = codeField.get(k);
            int vk = (Integer) vkOf.invoke(k);
            String down = (String) rawKeyDown.invoke(k);
            String up = (String) keyUp.invoke(k);

            List<String> problems = new ArrayList<>();
            if (!e[2].equals(keyName)) {
                problems.add("key=" + keyName);
            }
            if (!e[3].equals(codeName)) {
                problems.add("code=" + codeName);
            }
            if (!e[4].equals(String.valueOf(vk))) {
                problems.add("vk=" + vk);
            }
            if (!down.contains("\"type\":\"rawKeyDown\"")) {
                problems.add("nao e' rawKeyDown");
            }
            if (!down.contains("\"windowsVirtualKeyCode\":" + vk)) {
                problems.add("sem windowsVirtualKeyCode");
            }
            if (down.contains("\"text\"")) {
                problems.add("tem text - inseriria caracteres");
            }
            if (!up.contains("\"type\":\"keyUp\"")) {
                problems.add("sem keyUp");
            }

            System.out.printf("%-10s %6s %-12s %-12s %4d  %s%n",
                    e[0], e[1], keyName, codeName, vk,
                    problems.isEmpty() ? "OK" : "FALHOU: " + String.join(", ", problems));
            if (!problems.isEmpty()) {
                fails.add(e[0]);
            }
        }

        System.out.println();
        System.out.println(fails.isEmpty()
                ? "todas as " + expected.length + " linhas certas"
                : fails.size() + " erradas: " + fails);
        System.exit(fails.isEmpty() ? 0 : 1);
    }
}