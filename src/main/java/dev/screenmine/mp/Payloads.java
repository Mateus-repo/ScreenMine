package dev.screenmine.mp;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * The messages that make a shared screen work: ten of them, five each way.
 *
 * <h2>Shape</h2>
 *
 * <p>Server to client: a screen appears, a screen goes away, a screen navigates,
 * a screen moved, and the owner reports where the video is.
 * Client to server: place, remove, navigate, move, and the same playback
 * report - which only the owner may send.
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
    public record Place(String url, String dimension, double x, double y, double z,
                        float yaw, float width, float height)
            implements CustomPacketPayload {

        public static final Type<Place> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(NS, "place"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Place> CODEC = StreamCodec.of(
                (buf, v) -> {
                    ByteBufCodecs.STRING_UTF8.encode(buf, v.url);
                    ByteBufCodecs.STRING_UTF8.encode(buf, v.dimension);
                    buf.writeDouble(v.x);
                    buf.writeDouble(v.y);
                    buf.writeDouble(v.z);
                    buf.writeFloat(v.yaw);
                    buf.writeFloat(v.width);
                    buf.writeFloat(v.height);
                },
                buf -> new Place(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
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

    /**
     * Client to server: the owner moved their screen.
     *
     * <p>Without this the owner's screen moves for the owner and nobody else,
     * which is worse than not moving at all: two people in front of the same
     * screen see it in different places, and the person who placed it cannot
     * tell whether anybody else can still see it.
     */
    public record Move(UUID screenId, double x, double y, double z, float yaw)
            implements CustomPacketPayload {

        public static final Type<Move> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(NS, "move"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Move> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeUUID(v.screenId);
                    buf.writeDouble(v.x);
                    buf.writeDouble(v.y);
                    buf.writeDouble(v.z);
                    buf.writeFloat(v.yaw);
                },
                buf -> new Move(buf.readUUID(), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(), buf.readFloat()));

        @Override
        public Type<Move> type() {
            return TYPE;
        }
    }

    /** Server to client: this screen moved. */
    public record ScreenMove(UUID screenId, double x, double y, double z, float yaw)
            implements CustomPacketPayload {

        public static final Type<ScreenMove> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(NS, "screen_move"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenMove> CODEC =
                StreamCodec.of(
                        (buf, v) -> {
                            buf.writeUUID(v.screenId);
                            buf.writeDouble(v.x);
                            buf.writeDouble(v.y);
                            buf.writeDouble(v.z);
                            buf.writeFloat(v.yaw);
                        },
                        buf -> new ScreenMove(buf.readUUID(), buf.readDouble(),
                                buf.readDouble(), buf.readDouble(), buf.readFloat()));

        @Override
        public Type<ScreenMove> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: where my shared screen's video is, and whether it plays.
     *
     * <p>One browser per client, so two people watching the same screen are two
     * separate sessions that drift apart. This is the owner reporting its own
     * browser, once every {@link PlaybackSync#REPORT_INTERVAL_MILLIS}, and the
     * server deciding whether that is worth passing on. It is deliberately the
     * only party that may send it - a viewer asking to be moved is a viewer
     * taking the wheel, and the server refuses with the same "not-your-screen"
     * it uses everywhere else.
     *
     * <p>{@code time} is a raw double from a packet and ends up in a script, so
     * {@link PlaybackSync#checkTime} exists to keep NaN out of a browser.
     */
    public record Playback(UUID screenId, String url, double time, boolean paused)
            implements CustomPacketPayload {

        public static final Type<Playback> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(NS, "playback"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Playback> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeUUID(v.screenId);
                    ByteBufCodecs.STRING_UTF8.encode(buf, v.url);
                    buf.writeDouble(v.time);
                    buf.writeBoolean(v.paused);
                },
                buf -> new Playback(buf.readUUID(), ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readDouble(), buf.readBoolean()));

        @Override
        public Type<Playback> type() {
            return TYPE;
        }
    }

    /** Server to client: the owner says where the video is. Follow it. */
    public record ScreenPlayback(UUID screenId, String url, double time, boolean paused)
            implements CustomPacketPayload {

        public static final Type<ScreenPlayback> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(NS, "screen_playback"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenPlayback> CODEC =
                StreamCodec.of(
                        (buf, v) -> {
                            buf.writeUUID(v.screenId);
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.url);
                            buf.writeDouble(v.time);
                            buf.writeBoolean(v.paused);
                        },
                        buf -> new ScreenPlayback(buf.readUUID(),
                                ByteBufCodecs.STRING_UTF8.decode(buf), buf.readDouble(),
                                buf.readBoolean()));

        @Override
        public Type<ScreenPlayback> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: that request was refused, and here is why.
     *
     * <p>Without this the client never hears about a refusal. It sends a
     * request, the server says no, and the player's button does nothing at all -
     * which is indistinguishable from the feature being broken. With the
     * whole-server screen limit defaulting to one, the second placement is
     * refused every time, so this is not a nicety.
     *
     * <p>It carries the server's own limit, because the client cannot know it:
     * the limit is the server's budget, and the server is the only party that
     * has it. Reading it from the client's own copy of the config would be
     * reading a number that may not be the one the server is using.
     */
    public record ScreenRefused(String code, String detail, int limit)
            implements CustomPacketPayload {

        public static final Type<ScreenRefused> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(NS, "screen_refused"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenRefused> CODEC =
                StreamCodec.of(
                        (buf, v) -> {
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.code);
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.detail == null ? "" : v.detail);
                            buf.writeInt(v.limit);
                        },
                        buf -> new ScreenRefused(
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                buf.readInt()));

        @Override
        public Type<ScreenRefused> type() {
            return TYPE;
        }
    }

    /** Server to client: here is a screen, at this exact place. */
    public record ScreenAdded(UUID screenId, String owner, String url, String dimension,
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
                            ByteBufCodecs.STRING_UTF8.encode(buf, v.dimension);
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
        if (!PayloadTypeRegistryAccess.beginRegistration()) {
            return;
        }
        PayloadTypeRegistryAccess.serverbound(Place.TYPE, Place.CODEC);
        PayloadTypeRegistryAccess.serverbound(Remove.TYPE, Remove.CODEC);
        PayloadTypeRegistryAccess.serverbound(Navigate.TYPE, Navigate.CODEC);
        PayloadTypeRegistryAccess.serverbound(Move.TYPE, Move.CODEC);
        PayloadTypeRegistryAccess.serverbound(Playback.TYPE, Playback.CODEC);
        PayloadTypeRegistryAccess.clientbound(ScreenPlayback.TYPE, ScreenPlayback.CODEC);
        PayloadTypeRegistryAccess.clientbound(ScreenAdded.TYPE, ScreenAdded.CODEC);
        PayloadTypeRegistryAccess.clientbound(ScreenRemoved.TYPE, ScreenRemoved.CODEC);
        PayloadTypeRegistryAccess.clientbound(ScreenNavigate.TYPE, ScreenNavigate.CODEC);
        PayloadTypeRegistryAccess.clientbound(ScreenMove.TYPE, ScreenMove.CODEC);
        PayloadTypeRegistryAccess.clientbound(ScreenRefused.TYPE, ScreenRefused.CODEC);
        PayloadTypeRegistryAccess.logged();
    }

    /** The largest a screen message may be, in bytes. */
    public static final int MAX_MESSAGE_BYTES = 8 * 1024;
}