package dev.lk.gardenshop.gui;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knows which menu each player has open, and closes them when it matters.
 *
 * <p>Two moments make this bookkeeping necessary rather than merely tidy:
 *
 * <ul>
 *   <li><b>{@code /gs reload}.</b> An open menu is showing prices from a configuration that
 *       no longer exists. Leaving it up invites a player to confirm a sale against numbers
 *       the server has already replaced.</li>
 *   <li><b>Plugin disable.</b> A menu whose listener has been unregistered is a live
 *       inventory nothing is guarding any more. It has to come down with the plugin.</li>
 * </ul>
 */
public final class MenuService {

    private final Plugin plugin;
    private final Map<UUID, Menu> open = new ConcurrentHashMap<>();

    public MenuService(Plugin plugin) {
        this.plugin = plugin;
    }

    void track(Menu menu) {
        open.put(menu.viewer().getUniqueId(), menu);
    }

    void untrack(Player player) {
        open.remove(player.getUniqueId());
    }

    /**
     * Opens a menu on the next tick.
     *
     * <p>Deferring matters when this is called from inside an inventory click: opening a new
     * inventory while Bukkit is still resolving the click on the old one leaves the client
     * and server disagreeing about what is on screen. One tick later, the old view is
     * properly closed.
     *
     * <p>Uses the entity scheduler rather than the global one so the call is correct on
     * Folia as well as Paper.
     */
    public void openLater(Menu menu) {
        menu.viewer().getScheduler().run(plugin, task -> {
            if (menu.viewer().isOnline()) {
                menu.open();
            }
        }, null);
    }

    public void close(Player player) {
        if (open.remove(player.getUniqueId()) != null) {
            player.closeInventory();
        }
    }

    /** Shuts every open menu. Must run on the main thread. */
    public void closeAll() {
        // Copied first: closeInventory fires InventoryCloseEvent, which untracks and would
        // otherwise mutate the map mid-iteration.
        for (Menu menu : Map.copyOf(open).values()) {
            if (menu.viewer().isOnline()) {
                menu.viewer().closeInventory();
            }
        }
        open.clear();
    }

    /** Re-renders every open menu, for when the thing they display has changed. */
    public void refreshAll() {
        for (Menu menu : Map.copyOf(open).values()) {
            if (menu.viewer().isOnline()) {
                menu.refresh();
            }
        }
    }

    public int openCount() {
        return open.size();
    }
}
