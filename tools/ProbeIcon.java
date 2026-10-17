import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Descodifica o icone do mod com ImageIO, que e' o que o ModMenu usa.
 *
 * <p>Porque um descodificador do .NET nao serve: ele e' mais tolerante. O
 * ModMenu le o icone com ImageIO e, se lancar, usa o icone por omissao e
 * escreve "has a broken icon". Um PNG que o .NET abre e o ImageIO nao e'
 * exactamente o sintoma que o aviso descreve, e so este teste o distingue.
 */
public class ProbeIcon {
    public static void main(String[] args) {
        File f = new File(args[0]);
        System.out.println("ficheiro: " + f);
        System.out.println("tamanho:  " + f.length() + " bytes");
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) {
                System.out.println("RESULTADO: ImageIO devolveu null - nenhum descodificador serve");
                System.exit(1);
            }
            System.out.println("RESULTADO: OK " + img.getWidth() + "x" + img.getHeight()
                    + " tipo=" + img.getType());
        } catch (Exception ex) {
            System.out.println("RESULTADO: ImageIO Lancou " + ex.getClass().getName()
                    + ": " + ex.getMessage());
            System.exit(1);
        }
    }
}