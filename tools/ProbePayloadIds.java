import dev.screenmine.mp.Payloads;

/**
 * Imprime os ids reais dos payloads,Straight from the running classes.
 *
 * <p>Existe porque three ways of reading a jar for a string all disagreed:
 * ASCII-decoding the class file said a literal was missing, javap's constant
 * pool said a different literal was missing, and the game had logged the id
 * correctly all along. Rather than pick the most convincing of three wrong
 * tools, ask the class itself what its ids are.
 *
 * <p>Corre: java -cp build/classes/java/main tools/ProbePayloadIds.java
 */
public class ProbePayloadIds {
    public static void main(String[] args) {
        System.out.println("place          -> " + Payloads.Place.TYPE.id());
        System.out.println("remove         -> " + Payloads.Remove.TYPE.id());
        System.out.println("navigate       -> " + Payloads.Navigate.TYPE.id());
        System.out.println("move           -> " + Payloads.Move.TYPE.id());
        System.out.println("screen_added   -> " + Payloads.ScreenAdded.TYPE.id());
        System.out.println("screen_removed -> " + Payloads.ScreenRemoved.TYPE.id());
        System.out.println("screen_navigate-> " + Payloads.ScreenNavigate.TYPE.id());
        System.out.println("screen_move    -> " + Payloads.ScreenMove.TYPE.id());
        System.out.println("playback       -> " + Payloads.Playback.TYPE.id());
        System.out.println("screen_playback-> " + Payloads.ScreenPlayback.TYPE.id());
    }
}