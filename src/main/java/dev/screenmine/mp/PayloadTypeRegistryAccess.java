package dev.screenmine.mp;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place payload types are registered.
 *
 * <p>Kept apart from {@link Payloads} for a reason that is not tidiness:
 * registration has to happen on a side that may not have the other side's code.
 * A dedicated server never sees the client classes and a client never sees the
 * server ones, so anything that touches a client-only class here would take one
 * of them down at startup. {@link ServerEntrypoint} and the client initialiser
 * both come through here and neither can pull in the other.
 *
 * <p>Everything here is one direction at a time. Calling
 * {@code clientboundPlay().register(...)} on a dedicated server is not a no-op
 * that gets ignored, it is a registration for a channel that does not exist
 * there.
 */
final class PayloadTypeRegistryAccess {

    private static final Logger LOG = LoggerFactory.getLogger("ScreenMine/mp");

    private static int serverbound;
    private static int clientbound;
    private static boolean done;

    private PayloadTypeRegistryAccess() {
    }

    /**
     * Whether {@link Payloads#registerAll()} has already run in this process.
     *
     * <p>Registration is not idempotent: the second call throws
     * "Packet type is already registered" and takes the game down at startup.
     * It happened for real, because the client runs both the main and the
     * client entrypoint and both registered. The fix belongs where the mistake
     * was; this flag only stops the same mistake from being fatal a second
     * time, and says so in the log rather than pretending all is well.
     */
    static synchronized boolean beginRegistration() {
        if (done) {
            LOG.warn("payloads are already registered; ignoring a second call");
            return false;
        }
        done = true;
        return true;
    }

    static <T extends CustomPacketPayload> void serverbound(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
        serverbound++;
        LOG.info("payload registered serverbound {}", type.id());
    }

    static <T extends CustomPacketPayload> void clientbound(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
        clientbound++;
        LOG.info("payload registered clientbound {}", type.id());
    }

    /** The line that proves registration ran, and how much of it. */
    static void logged() {
        LOG.info("payloads registered: {} serverbound, {} clientbound, max {} bytes",
                serverbound, clientbound, Payloads.MAX_MESSAGE_BYTES);
    }
}