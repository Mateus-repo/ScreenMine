package dev.screenmine.net;

import com.google.gson.JsonObject;

/** Small builders for every outgoing packet the mod sends. */
public final class Packets {
    private Packets() {
    }

    private static JsonObject of(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        return o;
    }

    private static JsonObject named(String type, String name) {
        JsonObject o = of(type);
        o.addProperty("name", name);
        return o;
    }

    public static JsonObject ping() {
        return of("ping");
    }

    /** Tells the server which Minecraft server we are playing on. */
    public static JsonObject where(String serverId) {
        JsonObject o = of("where");
        o.addProperty("server", serverId);
        return o;
    }

    public static JsonObject friendList() {
        return of("friend_list");
    }

    public static JsonObject friendAdd(String name) {
        return named("friend_add", name);
    }

    public static JsonObject friendAccept(String name) {
        return named("friend_accept", name);
    }

    public static JsonObject friendDecline(String name) {
        return named("friend_decline", name);
    }

    public static JsonObject friendRemove(String name) {
        return named("friend_remove", name);
    }

    public static JsonObject invite(String name) {
        return named("invite", name);
    }

    public static JsonObject inviteAccept(String host) {
        return named("invite_accept", host);
    }

    public static JsonObject inviteDecline(String host) {
        return named("invite_decline", host);
    }

    public static JsonObject leave() {
        return of("leave");
    }

    public static JsonObject streamNew(String url) {
        JsonObject o = of("stream_new");
        o.addProperty("url", url);
        return o;
    }

    public static JsonObject streamRemove(String id) {
        JsonObject o = of("stream_remove");
        o.addProperty("id", id);
        return o;
    }

    public static JsonObject ctl(String id, String action) {
        JsonObject o = of("stream_ctl");
        o.addProperty("id", id);
        o.addProperty("a", action);
        return o;
    }

    public static JsonObject ctl(String id, String action, double value) {
        JsonObject o = ctl(id, action);
        o.addProperty("v", value);
        return o;
    }

    public static JsonObject ctl(String id, String action, String value) {
        JsonObject o = ctl(id, action);
        o.addProperty("v", value);
        return o;
    }

    public static JsonObject state(String id, double time, boolean paused, String title) {
        return state(id, time, paused, title, false);
    }

    public static JsonObject state(String id, double time, boolean paused, String title, boolean isAd) {
        return state(id, time, paused, title, isAd, null);
    }

    public static JsonObject state(String id, double time, boolean paused, String title, boolean isAd, String url) {
        JsonObject o = of("stream_state");
        o.addProperty("id", id);
        o.addProperty("time", time);
        o.addProperty("paused", paused);
        o.addProperty("title", title);
        o.addProperty("is_ad", isAd);
        if (url != null && !url.isBlank()) {
            o.addProperty("url", url);
        }
        return o;
    }
}
