import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executa o mapeamento de codigos de tecla do mcef-modern e diz o que ele
 * produz para as teclas que interessam.
 *
 * <p>Porque executar em vez de ler o bytecode: o metodo e' um tableswitch com
 * mais de 200 casos e um default. Ler a tabela a mao e' lento e facil de errar;
 * reflexao devolve o numero real, o que torna a conclusao provavel em vez de
 * apenas plausivel.
 *
 * <p>Minecraft 26.3 trocou o GLFW pelo SDL3, portanto KeyEvent.key() devolve um
 * SDL_SCANCODE, nao um codigo GLFW nem um Virtual-Key do Windows. Os valores de
 * baixo foram lidos de InputConstants com javap -_constants, todos de 26.3.
 * A coluna "vk" e' o Virtual-Key do Windows, que e' o que o Chromium espera.
 */
public class ProbeKeycodes {

    private static void put(Map<String, int[]> m, String name, int sdl, int vk) {
        m.put(name, new int[] {sdl, vk});
    }

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("net.dimaskama.mcef.impl.MCEFBrowserImpl");
        Method t = c.getDeclaredMethod("toAwtKeyCode", int.class);
        t.setAccessible(true);

        Map<String, int[]> keys = new LinkedHashMap<>();
        put(keys, "A", 4, 65);
        put(keys, "C", 6, 67);
        put(keys, "Z", 29, 90);
        put(keys, "0", 39, 48);
        put(keys, "RETURN", 40, 13);
        put(keys, "ESCAPE", 41, 27);
        put(keys, "BACKSPACE", 42, 8);
        put(keys, "TAB", 43, 9);
        put(keys, "SPACE", 44, 32);
        put(keys, "CAPSLOCK", 57, 20);
        put(keys, "F1", 58, 112);
        put(keys, "F12", 69, 123);
        put(keys, "INSERT", 73, 45);
        put(keys, "HOME", 74, 36);
        put(keys, "PAGEUP", 75, 33);
        put(keys, "DELETE", 76, 46);
        put(keys, "END", 77, 35);
        put(keys, "PAGEDOWN", 78, 34);
        put(keys, "RIGHT", 79, 39);
        put(keys, "LEFT", 80, 37);
        put(keys, "DOWN", 81, 40);
        put(keys, "UP", 82, 38);
        put(keys, "LSHIFT", 225, 16);
        put(keys, "LALT", 226, 18);
        put(keys, "LCTRL", 224, 17);

        int bad = 0;
        int unknown = 0;
        System.out.printf("%-10s %8s %8s %8s  %s%n",
                "tecla", "sdl/mc", "vk", "awt", "estado");
        for (Map.Entry<String, int[]> e : keys.entrySet()) {
            int sdl = e.getValue()[0];
            int vk = e.getValue()[1];
            int awt = (Integer) t.invoke(null, sdl);
            String state;
            if (awt == vk) {
                state = "OK";
            } else if (awt == 0) {
                state = "DESCONHECIDO";
                unknown++;
            } else {
                state = "ERRADO";
            }
            if (awt != vk) {
                bad++;
            }
            System.out.printf("%-10s %8d %8d %8d  %s%n", e.getKey(), sdl, vk, awt, state);
        }
        System.out.println();
        System.out.println(bad + " de " + keys.size() + " chaves nao batem certo ("
                + unknown + " comecam por 0, ou seja VK_UNDEFINED)");
        System.exit(bad == 0 ? 0 : 1);
    }
}