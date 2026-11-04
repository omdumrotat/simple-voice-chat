package de.maxhenkel.voicechat.api;

import java.util.Set;
import java.util.UUID;

/** Optional Bukkit service supplied by the standalone fork. Never changes group membership. */
public interface StandaloneRoutingService {
    /** Whether the authenticated standalone control connection is ready. */
    boolean isAvailable();

    /**
     * Replaces the player's group routing atomically. Empty transmit groups mute the microphone.
     * The override suppresses normal group and proximity transmission. Up to four groups per set.
     * Changes are synchronized on the next standalone player update; call on the Bukkit thread.
     */
    void setRouting(UUID player, Set<UUID> listenGroups, Set<UUID> transmitGroups);

    /** Restores normal routing on the next player update. Call on the Bukkit thread. */
    void clearRouting(UUID player);
}
