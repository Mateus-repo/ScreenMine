package dev.screenmine.state;

import dev.screenmine.Lang;
import com.google.gson.JsonObject;
import dev.screenmine.sync.PlaybackSync;

/** Server-side view of a shared browser surface. */
public final class StreamInfo {
    public final String id;
    public final String owner;
    public String url;
    public boolean paused = true;
    public double time;
    public String title = "";

    /** Follows the server's authoritative clock for this stream. */
    public final PlaybackSync sync = new PlaybackSync();

    public StreamInfo(String id, String owner, String url) {
        this.id = id;
        this.owner = owner;
        this.url = url;
    }

    public static StreamInfo fromJson(JsonObject o) {
        StreamInfo s = new StreamInfo(
                o.get("id").getAsString(),
                o.get("owner").getAsString(),
                o.has("url") ? o.get("url").getAsString() : "");
        if (o.has("paused")) {
            s.paused = o.get("paused").getAsBoolean();
        }
        if (o.has("time")) {
            s.time = o.get("time").getAsDouble();
        }
        if (o.has("title")) {
            s.title = o.get("title").getAsString();
        }
        return s;
    }

    public String displayName() {
        String t = title == null ? "" : title.trim();
        if (!t.isEmpty()) {
            return t.length() > 40 ? t.substring(0, 39) + "…" : t;
        }
        return Lang.t("misc.screen.of", owner);
    }
}
