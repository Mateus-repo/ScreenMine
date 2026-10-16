import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Lista os metodos de uma classe por reflexao, independentemente do javap.
 *
 * <p>Porque existe isto: o bytecode de MCEFBrowserImpl chama sendKeyEvent, mas
 * o javap nao lista esse metodo entre os declarados. Uma das duas leituras esta
 * errada, e a escolha muda o que se faz a seguir - se o metodo falta, qualquer
 * tecla rebenta com NoSuchMethodError; se existe, o problema e' outro. A
 * reflexao le o que a classe carregada em memoria diz, que e' a unica leitura
 * que interessa.
 */
public class ProbeMethods {

    public static void main(String[] args) throws Exception {
        for (String name : args) {
            System.out.println("=== " + name + " ===");
            Class<?> c;
            try {
                c = Class.forName(name);
            } catch (ClassNotFoundException ex) {
                System.out.println("  classe nao encontrada");
                System.out.println();
                continue;
            }
            Method[] ms = c.getDeclaredMethods();
            Arrays.sort(ms, Comparator.comparing(Method::getName));
            System.out.println("  metodos declarados: " + ms.length);
            for (Method m : ms) {
                System.out.printf("  %-6s %-28s %s%n",
                        Modifier.isPrivate(m.getModifiers()) ? "priv"
                                : Modifier.isPublic(m.getModifiers()) ? "pub" : "prot",
                        m.getName(),
                        Arrays.toString(m.getParameterTypes()).replace("class ", "")
                                .replace("[L", "["));
            }
            System.out.println();
        }
    }
}