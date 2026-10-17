import java.lang.reflect.Method;

/**
 * Verifica o mapeamento de SDL scancode para Virtual-Key do Windows.
 *
 * <p>Existe porque este mapeamento e' um switch com dez casos escritos a mao e
 * um valor trocado e' silencioso: a tecla simplesmente nao faz nada, em jogo,
 * sem erro nenhum. O log diria "AWT KEY sent" e o Backspace nao apagaria - que
 * e' exactamente o sintoma que se esta a tentar fechar.
 *
 * <p>Corre: java -cp build/classes/java/main tools/ProbeAwtKeys.java
 */
public class ProbeAwtKeys {

    private static void put(java.util.Map<String, int[]> m, String name, int sdl, int vk) {
        m.put(name, new int[] {sdl, vk});
    }

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("dev.screenmine.browser.AwtKeys");
        Method t = c.getDeclaredMethod("virtualKey", int.class);
        t.setAccessible(true);

        java.util.Map<String, int[]> keys = new java.util.LinkedHashMap<>();
        // Os scancodes foram lidos de InputConstants em 26.3 com javap -constants.
        put(keys, "BACKSPACE", 42, 8);
        put(keys, "DELETE", 76, 46);
        put(keys, "LEFT", 80, 37);
        put(keys, "UP", 82, 38);
        put(keys, "RIGHT", 79, 39);
        put(keys, "DOWN", 81, 40);
        put(keys, "HOME", 74, 36);
        put(keys, "END", 77, 35);
        put(keys, "PAGEUP", 75, 33);
        put(keys, "PAGEDOWN", 78, 34);

        // Teclas que NAO devem ir por aqui: chegam como caracter e sending
        // both would submit twice.
        put(keys, "RETURN", 40, 0);
        put(keys, "TAB", 43, 0);
        put(keys, "A", 4, 0);
        put(keys, "SPACE", 44, 0);

        int bad = 0;
        System.out.printf("%-10s %8s %8s %8s  %s%n", "tecla", "sdl", "vk", "obtido", "estado");
        for (java.util.Map.Entry<String, int[]> e : keys.entrySet()) {
            int got = (Integer) t.invoke(null, e.getValue()[0]);
            boolean ok = got == e.getValue()[1];
            System.out.printf("%-10s %8d %8d %8d  %s%n",
                    e.getKey(), e.getValue()[0], e.getValue()[1], got, ok ? "OK" : "ERRADO");
            if (!ok) {
                bad++;
            }
        }
        System.out.println();
        System.out.println(bad + " de " + keys.size() + " erradas");
        System.exit(bad == 0 ? 0 : 1);
    }
}