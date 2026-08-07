package dev.lk.gardenshop.pack;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers who successfully loaded <em>our</em> resource pack.
 *
 * <p>This is the only reliable signal behind {@link dev.lk.gardenshop.core.config.MenuStyle#AUTO}:
 * a player who declined the download, or whose download failed, must be shown a plain menu, because
 * pack glyphs render as missing-character boxes for them.
 *
 * <p>Only {@code SUCCESSFULLY_LOADED} counts. {@code ACCEPTED} means the client agreed to start
 * downloading, not that it finished — treating it as loaded shows broken art during the download and
 * forever after if it fails.
 *
 * <p>And only <em>our</em> pack counts, which the id on the event is what makes possible. Since
 * 1.20.3 a client holds a stack of packs and this event fires for every one of them, so on a server
 * that also runs ItemsAdder, Nexo or Oraxen an unfiltered listener would read their pack's status as
 * ours in both directions.
 */
public final class PackTracker implements Listener {

    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();

    /** Whether this player is known to have our pack. */
    public boolean hasPack(UUID player) {
        return loaded.contains(player);
    }

    public int loadedCount() {
        return loaded.size();
    }

    /**
     * Marks everyone as pack-less, for when the pack itself changed.
     *
     * <p>Called on reload: the art a player downloaded may no longer be the art we now serve, and
     * assuming otherwise would draw glyphs they do not have.
     */
    public void forgetAll() {
        loaded.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        // Another plugin's pack loading would otherwise mark this player as having ours, and we
        // would draw glyphs they do not have; its download failing would drop a player who really
        // does have ours to a plain menu. Both are silent, and both look like our bug.
        if (!PackDelivery.PACK_ID.equals(event.getID())) {
            return;
        }

        UUID player = event.getPlayer().getUniqueId();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> loaded.add(player);
            // Everything else - declined, failed, discarded, or merely accepted - is not a pack
            // this player is currently rendering with.
            default -> loaded.remove(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        loaded.remove(event.getPlayer().getUniqueId());
    }
}
