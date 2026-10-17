package dev.screenmine.mp;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;

/**
 * The server's half of shared screens: it receives, it decides, it tells
 * everyone.
 *
 * <p>Every rule lives in {@link SharedScreens} and every one of them is checked
 * here, on the server. The client checks them again for its own reasons, but a
 * client that checks nothing is a client nobody can stop, so the server never
 * assumes.
 *
 * <p>What the server does with a refusal is say why, in the log, and send
 * nothing. A silent refusal looks like the feature being broken.
 */
final class ScreenNetwork {

    private static SharedScreens screens;
    private static ScreenRules.Rejection[] scratch = new ScreenRules.Rejection[1];

    private ScreenNetwork() {
    }

    static SharedScreens screens() {
        return screens;
    }

    /** Registers the three client-to-server receivers. */
    static void register() {
        screens = new SharedScreens(ScreenRules.DEFAULT_ALLOWED_HOSTS,
                ScreenRules.MAX_SCREENS_PER_PLAYER);

        // Place. The client names no id; the server invents one and hands it back.
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Place.TYPE, (place, context) -> {
            ServerPlayer player = context.player();
            SharedScreens.Screen screen = null;
            // The position and the dimension come from the server's own view of
            // the player, never from the message. The message is only believed
            // about the URL it wants opened: a client that says "I am here, at
            // 0 64 0, in the overworld" is describing a position the server can
            // already see, and if the two disagree the screen goes where the
            // player is, not where the message claims.
            net.minecraft.server.level.ServerPlayer server_player = player;
            double px = server_player.getX();
            double py = server_player.getY();
            double pz = server_player.getZ();
            // identifier(), not location(): checked against the 26.3 jar, where
            // ResourceKey has no location() at all.
            String playerDimension =
                    server_player.level().dimension().identifier().toString();

            ScreenRules.Rejection why = screens.place(
                    player.getUUID(), player.getGameProfile().name(),
                    place.url(), place.dimension(), place.x(), place.y(), place.z(),
                    place.yaw(), place.width(), place.height(),
                    px, py, pz, playerDimension,
                    context.server().getTickCount() * 50L);

            if (why != null) {
                refuse(context.server(), player, "place", why);
                return;
            }
            // find the one just created, by owner, newest
            for (SharedScreens.Screen s : screens.all()) {
                if (s.owner().equals(player.getUUID()) && s.url().equals(place.url())) {
                    screen = s;
                }
            }
            if (screen == null) {
                refuse(context.server(), player, "place",
                        new ScreenRules.Rejection("place-lost", null));
                return;
            }
            log(context.server(), "place", player, screen, null);
            broadcastAdded(context.server(), screen, player);
        });

        // Navigate. Owner only.
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Navigate.TYPE, (navigate, context) -> {
            ServerPlayer player = context.player();
            SharedScreens.Screen updated = screens.navigate(
                    navigate.screenId(), player.getUUID(), navigate.url(),
                    context.server().getTickCount() * 50L, scratch);
            if (updated == null) {
                refuse(context.server(), player, "navigate", scratch[0]);
                return;
            }
            log(context.server(), "navigate", player, updated, null);
            broadcast(context.server(), new Payloads.ScreenNavigate(updated.id(), updated.url()));
        });

        // Remove. Owner only.
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Remove.TYPE, (remove, context) -> {
            ServerPlayer player = context.player();
            SharedScreens.Screen gone = screens.remove(
                    remove.screenId(), player.getUUID(),
                    context.server().getTickCount() * 50L, scratch);
            if (gone == null) {
                refuse(context.server(), player, "remove", scratch[0]);
                return;
            }
            log(context.server(), "remove", player, gone, null);
            broadcast(context.server(), new Payloads.ScreenRemoved(gone.id()));
        });

        // A newcomer is told about everything that already exists. Without this
        // a player who joins late sees an empty world and never learns why.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            int sent = 0;
            for (SharedScreens.Screen s : screens.all()) {
                if (ServerPlayNetworking.canSend(player, Payloads.ScreenAdded.TYPE)) {
                    ServerPlayNetworking.send(player, addedPayload(s));
                    sent++;
                }
            }
            log(server, "join", player, null, sent + " existing screen(s) sent");
        });

        // The owner leaving takes their screens away from everybody.
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> onDisconnect(server, handler.player));
    }

    static void onDisconnect(MinecraftServer server, ServerPlayer player) {
        UUID who = player.getUUID();
        var gone = screens.removeAllOf(who);
        if (gone.isEmpty()) {
            return;
        }
        for (SharedScreens.Screen s : gone) {
            log(server, "disconnect", player, s, null);
            broadcast(server, new Payloads.ScreenRemoved(s.id()));
        }
        log(server, "disconnect-summary", player, null,
                gone.size() + " screen(s) removed");
    }

    private static Payloads.ScreenAdded addedPayload(SharedScreens.Screen s) {
        return new Payloads.ScreenAdded(s.id(), s.ownerName(), s.url(), s.dimension(),
                s.x(), s.y(), s.z(), s.yaw(), s.width(), s.height());
    }

    private static void broadcastAdded(MinecraftServer server, SharedScreens.Screen s,
                                       ServerPlayer except) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getUUID().equals(except.getUUID())) {
                continue;
            }
            if (ServerPlayNetworking.canSend(p, Payloads.ScreenAdded.TYPE)) {
                ServerPlayNetworking.send(p, addedPayload(s));
            }
        }
        // The placer needs its own copy, or the screen exists for everybody
        // except the person standing in front of it.
        ServerPlayNetworking.send(except, addedPayload(s));
    }

    private static void broadcast(MinecraftServer server, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (payload instanceof Payloads.ScreenNavigate nav) {
                if (ServerPlayNetworking.canSend(p, Payloads.ScreenNavigate.TYPE)) {
                    ServerPlayNetworking.send(p, nav);
                }
            } else if (payload instanceof Payloads.ScreenRemoved rem) {
                if (ServerPlayNetworking.canSend(p, Payloads.ScreenRemoved.TYPE)) {
                    ServerPlayNetworking.send(p, rem);
                }
            }
        }
    }

    private static void refuse(MinecraftServer server, ServerPlayer player,
                               String what, ScreenRules.Rejection why) {
        log(server, what + "-refused", player, null,
                why == null ? "?" : why.toString());
    }

    private static void log(MinecraftServer server, String what, ServerPlayer player,
                            SharedScreens.Screen screen, String note) {
        String id = player.getGameProfile().name() + "/" + player.getUUID().toString().substring(0, 8);
        String screenPart = screen == null ? "" : " screen=" + screen.id() + " url=" + screen.url();
        String notePart = note == null ? "" : " (" + note + ")";
        org.slf4j.LoggerFactory.getLogger("ScreenMine/mp")
                .info("{} {} -> {} on {} ({} screens){}{}",
                        what, id, "?", "server", screens.count(), screenPart, notePart);
    }

    static Set<String> hosts() {
        return screens == null ? Set.of() : screens.allowedHosts();
    }
}