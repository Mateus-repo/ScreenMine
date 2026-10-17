package dev.screenmine.mp;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * The six messages that make a shared screen work.
 *
 * <h2>Shape</h2>
 *
 * <p>Server to client: a screen appears, a screen goes away, a screen navigates.
 * Client to server: place, remove, navigate.
 *
 * <p>The client does not choose the screen id. It puts a URL and a position on
 * the table and the server answers with an id, because an id a client picks is
 * an id it can collide with, overwrite or guess. {@link Place} carries no id and
 * {@link ScreenAdded} hands one back.
 *
 * <h2>Registration</h2>
 *
 * <p>{@link #registerAll} must run on both sides, and it must be the same on
 * both. A payload type the other side does not know is a message the connection
 * drops, which looks exactly like a network problem and is not one.
 *
 * <p>The names are checked, not assumed. Verified against
 * fabric-networking-api-v1-6.3.10 and the 26.3 jar:
 *
 * <ul>
 *   <li>{@code PayloadTypeRegistry.play(...)} does not exist. The accessors are
 *       {@code serverboundPlay()} and {@code clientboundPlay()}.</li>
 *   <li>{@code register(Type, StreamCodec)} and {@code registerLarge(Type,
 *       StreamCodec, int)} are the registration calls; the second takes the
 *       maximum size, which is where the size limit lives.</li>
 *   <li>{@code Identifier.fromNamespaceAndPath(String, String)} builds the ids.</li>
 * </ul>
 */
public final class Payloads {

    /** Every payload, both directions, under this namespace. */
    private static final String NS = "screenmine_mp";

    // ----------------------------------------------------------- definitions //

    /**
     * Client to server: put a screen in the world.
     *
     * <p>No id. The server assigns one, which is what keeps a client from
     * choosing which screen it is talking about.
     */
    public record Place(String url, double x, double y, double z,
                        float yaw, float width, float height)
            implements CustomPacketPayload {

        public static final Type<Place> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(NS, "place"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Place> CODEC = StreamCodec.of(
                (buf, v) -> {
                    ByteBufCodecs.STRING_UTF8.encode(buf, v.url);
                    buf.writeDouble(v.x);
                    buf.writeDouble(v.y);
                    buf.writeDouble(v.z);
                    buf.writeFloat(v.yaw);
                    buf.writeFloat(v.width);
                    buf.writeFloat(v.height);
                },
                buf -> new Place(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readFloat(), buf.readFloat(), buf.readFloat()));

        @Override
        public Type<Place> type() {
            return TYPE;
        }
    }

    /** Client to server: take this screen away. */
    public record Remove(UUID screenId) implements CustomPacketPayload {

        public static final Type<Remove> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(NS, "remove"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Remove> CODEC = StreamCodec.of(
                (buf, v) -> buf.writeUUID(v.screenId),
                buf -> new Remove(buf.readUUID()));

        @Override
        public Type<Remove> type() {
            return TYPE;
        }
    }

    /** Client to server: point this screen somewhere else. */
    public record Navigate(UUID screenId, String url) implements CustomPacketPayload {

        public static final Type<Navigate> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(NS, "navigate"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Navigate> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeUUID(v.screenId);
                    ByteBufCodecs.STRING_UTF8.encode(buf, v.url);
                },
                buf -> new Navigate(buf.readUUID(), ByteBufCodecs.STRING_UTF8.decode(buf)));

        @Override
        public Type<Navigate> type() {
            return TYPE;
        }
    }

    /** Server to client: here is a screen, at this exact place. */
    public record ScreenAdded(UUID screenId, String owner, String url,
                              double x, double y, double z,
                              float yaw, float width, float height)
            implements CustomPacketPayload {

        public static final Type<ScreenAdded> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(NS, "screen_added"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenAdded> CODEC =
                StreamCodec.of(
                        (buf, v) -> {
                            buf.writeUUID(v.screenId);
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.owner);
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.url);
                            buf.writeDouble(v.x);
                            buf.writeDouble(v.y);
                            buf.writeDouble(v.z);
                            buf.writeFloat(v.yaw);
                            buf.writeFloat(v.width);
                            buf.writeFloat(v.height);
                        },
                        buf -> new ScreenAdded(
                                buf.readUUID(),
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                                buf.readFloat(), buf.readFloat(), buf.readFloat()));

        @Override
        public Type<ScreenAdded> type() {
            return TYPE;
        }
    }

    /** Server to client: this screen is gone. */
    public record ScreenRemoved(UUID screenId) implements CustomPacketPayload {

        public static final Type<ScreenRemoved> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(NS, "screen_removed"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenRemoved> CODEC =
                StreamCodec.of(
                        (buf, v) -> buf.writeUUID(v.screenId),
                        buf -> new ScreenRemoved(buf.readUUID()));

        @Override
        public Type<ScreenRemoved> type() {
            return TYPE;
        }
    }

    /** Server to client: this screen moved to a new page. */
    public record ScreenNavigate(UUID screenId, String url) implements CustomPacketPayload {

        public static final Type<ScreenNavigate> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(NS, "screen_navigate"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenNavigate> CODEC =
                StreamCodec.of(
                        (buf, v) -> {
                            buf.writeUUID(v.screenId);
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.url);
                        },
                        buf -> new ScreenNavigate(
                                buf.readUUID(), ByteBufCodecs.STRING_UTF8.decode(buf)));

        @Override
        public Type<ScreenNavigate> type() {
            return TYPE;
        }
    }

    private Payloads() {
    }

    /**
     * Registers every type. Call from both initialisers.
     *
     * <p>{@code registerLarge} with an explicit ceiling rather than
     * {@code register}: a URL and nine numbers are small, and a payload that
     * could be megabytes is a payload someone will make megabytes.
     */
    public static void registerAll() {
        PayloadTypeRegistryAccess.serverbound(Place.TYPE, Place.CODEC);
        PayloadTypeRegistryAccess.serverbound(Remove.TYPE, Remove.CODEC);
        PayloadTypeRegistryAccess.serverbound(Navigate.TYPE, Navigate.CODEC);
        PayloadTypeRegistryAccess.clientbound(ScreenAdded.TYPE, ScreenAdded.CODEC);
        PayloadTypeRegistryAccess.clientbound(ScreenRemoved.TYPE, ScreenRemoved.CODEC);
        PayloadTypeRegistryAccess.clientbound(ScreenNavigate.TYPE, ScreenNavigate.CODEC);
        PayloadTypeRegistryAccess.logged();
    }

    /** The largest a screen message may be, in bytes. */
    public static final int MAX_MESSAGE_BYTES = 8 * 1024;
}