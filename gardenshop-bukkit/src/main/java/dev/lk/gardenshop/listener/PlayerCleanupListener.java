package dev.lk.gardenshop.listener;

import dev.lk.gardenshop.sell.SellService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Drops per-player bookkeeping when someone leaves.
 *
 * <p>Without this the sell-cooldown map grows for the lifetime of the server, which on
 * a busy network is a slow leak nobody notices until a restart fixes it.
 */
public final class PlayerCleanupListener implements Listener {

    private final SellService sell;

    public PlayerCleanupListener(SellService sell) {
        this.sell = sell;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sell.forget(event.getPlayer().getUniqueId());
    }
}
