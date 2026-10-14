package dev.screenmine.state;

import dev.screenmine.Lang;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the mod knows about the social/session side. Main thread only. */
public final class AppState {

    public String you = "";
    public String sessionHost = "";
    public final List<String> sessionMembers = new ArrayList<>();

    public final List<String> friends = new ArrayList<>();
    public final List<String> friendsOnline = new ArrayList<>();
    public final List<String> requestsIn = new ArrayList<>();
    public final List<String> requestsOut = new ArrayList<>();
    public final List<String> invites = new ArrayList<>();

    /** Which Minecraft server we are on, e.g. {@code mc.example.com:25565}. */
    public String myServer = "";

    /** Same, for every online friend and session member the server told us about. */
    public final Map<String, String> serverOf = new HashMap<>();

    /** streamId -> stream, in insertion order. */
    public final Map<String, StreamInfo> streams = new LinkedHashMap<>();

    public boolean isHost() {
        return !you.isEmpty() && you.equals(sessionHost);
    }

    public boolean inSharedSession() {
        return sessionMembers.size() > 1;
    }

    public boolean isFriendOnline(String name) {
        return friendsOnline.contains(name);
    }

    /** True when we know both sides and they are on the same Minecraft server. */
    public boolean sameServerAs(String name) {
        String theirs = serverOf.get(name);
        return !myServer.isEmpty() && theirs != null && myServer.equals(theirs);
    }

    /** Null when we simply do not know where they are. */
    public Boolean serverMatch(String name) {
        String theirs = serverOf.get(name);
        if (myServer.isEmpty() || theirs == null || theirs.isEmpty()) {
            return null;
        }
        return myServer.equals(theirs);
    }

    /** Human-readable name of a server id. */
    public static String serverLabel(String id) {
        if (id == null || id.isEmpty()) {
            return Lang.t("misc.unknown");
        }
        return id.startsWith("single:") ? Lang.t("misc.singleplayer") : id;
    }

    public void clearSession() {
        sessionHost = "";
        sessionMembers.clear();
        streams.clear();
    }

    public void reset() {
        clearSession();
        myServer = "";
        serverOf.clear();
        friends.clear();
        friendsOnline.clear();
        requestsIn.clear();
        requestsOut.clear();
        invites.clear();
    }
}
