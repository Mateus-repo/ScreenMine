package dev.screenmine.mp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which shared screens exist, who owns them, and who may touch them.
 *
 * <p>The server is the authority. It is the only place that knows who owns a
 * screen, and a client that decides ownership for itself decides nothing: every
 * rule here has to be checked again on the client because a modified client
 * cannot be made to ask permission.
 *
 * <p>In memory only, deliberately. Persistence was cut from the first version:
 * registering a block and an item on the server is a great deal more work than
 * the first proof is worth, and the agreed behaviour is that a screen lives
 * exactly as long as its owner is connected.
 *
 * <p>No Minecraft types in here either. A screen is a record of primitives, so
 * this whole class can be driven by {@code tools/ProbeSharedScreens.java}
 * without a server running.
 */
public final class SharedScreens {

    /** One shared screen, as the server knows it. */
    public record Screen(UUID id, UUID owner, String ownerName, String url, String dimension,
                         double x, double y, double z,
                         float yaw, float width, float height) {

        /** The same screen after the owner pointed it somewhere else. */
        public Screen withUrl(String newUrl) {
            return new Screen(id, owner, ownerName, newUrl, dimension, x, y, z, yaw, width, height);
        }

        /** The same screen after the owner moved it. */
        public Screen withPose(double nx, double ny, double nz, float nyaw) {
            return new Screen(id, owner, ownerName, url, dimension, nx, ny, nz, nyaw, width, height);
        }
    }

    private final Set<String> allowedHosts;
    private final int maxPerPlayer;

    private final Map<UUID, Screen> byId = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> byOwner = new HashMap<>();

    private final Map<UUID, RateLimiter> placeRate = new HashMap<>();
    private final Map<UUID, RateLimiter> navigateRate = new HashMap<>();
    private final Map<UUID, RateLimiter> moveRate = new HashMap<>();
    private final Map<UUID, RateLimiter> removeRate = new HashMap<>();

    public SharedScreens(Set<String> allowedHosts, int maxPerPlayer) {
        this.allowedHosts = Set.copyOf(allowedHosts);
        this.maxPerPlayer = maxPerPlayer;
    }

    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    public int maxPerPlayer() {
        return maxPerPlayer;
    }

    public int count() {
        return byId.size();
    }

    public Collection<Screen> all() {
        return List.copyOf(byId.values());
    }

    public int countOf(UUID player) {
        List<UUID> ids = byOwner.get(player);
        return ids == null ? 0 : ids.size();
    }

    /** Whether this player owns this screen. The question every message asks. */
    public boolean isOwner(UUID screenId, UUID player) {
        Screen s = byId.get(screenId);
        return s != null && s.owner().equals(player);
    }

    /**
     * Accepts a placement, or explains why not.
     *
     * <p>{@code playerDimension} and the player's position come from the server's
     * own knowledge, never from the message. A client that says "I am here, at
     * 0 64 0, in the overworld" is only believed about the URL it wants opened.
     *
     * <p>Order matters and is not arbitrary. Rate first, so a spammer is turned
     * away before the more expensive checks; then the quota, so a player over
     * their limit hears about the limit; then the dimension, because a screen in
     * the wrong dimension is refused for the clearest possible reason before
     * anyone argues about coordinates.
     */
    public ScreenRules.Rejection place(UUID player, String playerName, String url,
                                       String dimension, double x, double y, double z,
                                       float yaw, float width, float height,
                                       double px, double py, double pz,
                                       String playerDimension,
                                       long nowMillis) {
        if (!rate(placeRate, player, RateLimiter.PLACE_LIMIT).tryAcquire(nowMillis)) {
            return new ScreenRules.Rejection("place-too-fast",
                    "at most " + RateLimiter.PLACE_LIMIT + " per "
                            + (RateLimiter.WINDOW_MILLIS / 1000) + "s");
        }
        if (countOf(player) >= maxPerPlayer) {
            return new ScreenRules.Rejection("too-many-screens",
                    maxPerPlayer + " is the limit");
        }
        ScreenRules.Rejection bad = ScreenRules.checkDimension(dimension, playerDimension);
        if (bad != null) {
            return bad;
        }
        bad = ScreenRules.checkUrl(url, allowedHosts);
        if (bad != null) {
            return bad;
        }
        bad = ScreenRules.checkSize(width, height);
        if (bad != null) {
            return bad;
        }
        bad = ScreenRules.checkPosition(x, y, z);
        if (bad != null) {
            return bad;
        }
        bad = ScreenRules.checkDistance(x, y, z, px, py, pz);
        if (bad != null) {
            return bad;
        }
        bad = ScreenRules.checkYaw(yaw);
        if (bad != null) {
            return bad;
        }

        Screen screen = new Screen(UUID.randomUUID(), player, playerName, url.trim(),
                dimension, x, y, z, yaw, width, height);
        byId.put(screen.id(), screen);
        byOwner.computeIfAbsent(player, k -> new ArrayList<>()).add(screen.id());
        return null;
    }

    /**
     * Moves a screen to a new URL.
     *
     * @return the updated screen, or null when refused; {@code reason} then says
     *         why, and {@code reason} is null when it was accepted
     */
    public Screen navigate(UUID screenId, UUID player, String url,
                           long nowMillis, ScreenRules.Rejection[] reason) {
        reason[0] = null;
        if (!rate(navigateRate, player, RateLimiter.NAVIGATE_LIMIT).tryAcquire(nowMillis)) {
            reason[0] = new ScreenRules.Rejection("navigate-too-fast", null);
            return null;
        }
        if (!isOwner(screenId, player)) {
            // Deliberately the same message whether the screen does not exist
            // and whether it belongs to somebody else: a client probing for
            // other people's screen ids learns nothing either way.
            reason[0] = new ScreenRules.Rejection("not-your-screen", null);
            return null;
        }
        ScreenRules.Rejection bad = ScreenRules.checkUrl(url, allowedHosts);
        if (bad != null) {
            reason[0] = bad;
            return null;
        }
        Screen updated = byId.get(screenId).withUrl(url.trim());
        byId.put(screenId, updated);
        return updated;
    }

    /**
     * Moves a screen.
     *
     * <p>Ownership, distance and dimension are all checked again here, not only
     * at placement. The 64-block rule is a promise about placement and it is
     * only worth making if it also holds while the screen is being dragged: a
     * screen placed honestly and then dragged to the far side of the world is
     * the same problem as one placed there to begin with.
     *
     * <p>The dimension is the screen's own. Moving a screen does not move it to
     * another dimension - that would need a place.
     *
     * @return the moved screen, or null when refused
     */
    public Screen move(UUID screenId, UUID player,
                       double x, double y, double z, float yaw,
                       double px, double py, double pz,
                       long nowMillis, ScreenRules.Rejection[] reason) {
        reason[0] = null;
        if (!rate(moveRate, player, RateLimiter.MOVE_LIMIT).tryAcquire(nowMillis)) {
            reason[0] = new ScreenRules.Rejection("move-too-fast",
                    "at most " + RateLimiter.MOVE_LIMIT + " per "
                            + (RateLimiter.WINDOW_MILLIS / 1000) + "s");
            return null;
        }
        Screen current = byId.get(screenId);
        if (current == null || !current.owner().equals(player)) {
            reason[0] = new ScreenRules.Rejection("not-your-screen", null);
            return null;
        }
        ScreenRules.Rejection bad = ScreenRules.checkPosition(x, y, z);
        if (bad != null) {
            reason[0] = bad;
            return null;
        }
        bad = ScreenRules.checkDistance(x, y, z, px, py, pz);
        if (bad != null) {
            reason[0] = bad;
            return null;
        }
        bad = ScreenRules.checkYaw(yaw);
        if (bad != null) {
            reason[0] = bad;
            return null;
        }
        Screen updated = current.withPose(x, y, z, yaw);
        byId.put(screenId, updated);
        return updated;
    }

    /**
     * Takes a screen away.
     *
     * @return the removed screen, or null when refused
     */
    public Screen remove(UUID screenId, UUID player, long nowMillis,
                         ScreenRules.Rejection[] reason) {
        reason[0] = null;
        if (!rate(removeRate, player, RateLimiter.REMOVE_LIMIT).tryAcquire(nowMillis)) {
            reason[0] = new ScreenRules.Rejection("remove-too-fast", null);
            return null;
        }
        if (!isOwner(screenId, player)) {
            reason[0] = new ScreenRules.Rejection("not-your-screen", null);
            return null;
        }
        return forget(screenId);
    }

    /**
     * Everything a player left behind.
     *
     * <p>The agreed behaviour: when the owner leaves, their screens go. A screen
     * with no owner is either useless or up for grabs, and useless is the safer
     * of the two.
     */
    public List<Screen> removeAllOf(UUID player) {
        List<UUID> ids = byOwner.remove(player);
        if (ids == null) {
            return List.of();
        }
        List<Screen> gone = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Screen s = byId.remove(id);
            if (s != null) {
                gone.add(s);
            }
        }
        return gone;
    }

    /** Forgets a player entirely: screens and rate buckets. */
    public void forgetPlayer(UUID player) {
        removeAllOf(player);
        placeRate.remove(player);
        navigateRate.remove(player);
        removeRate.remove(player);
        moveRate.remove(player);
    }

    private Screen forget(UUID screenId) {
        Screen gone = byId.remove(screenId);
        if (gone == null) {
            return null;
        }
        List<UUID> ids = byOwner.get(gone.owner());
        if (ids != null) {
            ids.remove(screenId);
            if (ids.isEmpty()) {
                byOwner.remove(gone.owner());
            }
        }
        return gone;
    }

    /**
     * The bucket for one player and one kind of message.
     *
     * <p>The limit has to come from the caller. Building the bucket here with a
     * fixed limit of one would have made every kind of message as rare as the
     * rarest, and the navigates limit would have quietly become the places one.
     */
    private static RateLimiter rate(Map<UUID, RateLimiter> map, UUID player, int limit) {
        return map.computeIfAbsent(player, k -> new RateLimiter(limit, RateLimiter.WINDOW_MILLIS));
    }

    @Override
    public String toString() {
        return "SharedScreens{" + byId.size() + " screens, hosts="
                + allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).sorted()
                .toList() + "}";
    }
}
