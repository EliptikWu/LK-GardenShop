package dev.lk.gardenshop.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Routes inventory events to whichever {@link Menu} is open, and — more importantly —
 * makes sure nothing can be moved while one is.
 *
 * <h2>Why every click is cancelled, including clicks in the player's own inventory</h2>
 * A click that lands in the player's half of the view can still reach the menu: shift-click
 * moves a stack up into it, a number-key press swaps a hotbar slot with a menu slot, and a
 * drag can span both halves. Cancelling only the clicks whose raw slot falls inside the menu
 * leaves all of those open. Cancelling the entire view is one line, needs no case analysis,
 * and costs the player nothing but the ability to rearrange their bag for the few seconds a
 * menu is open.
 *
 * <p>Cancellation happens <em>before</em> the menu's handler runs, so a handler that throws
 * cannot leave a live, uncancelled inventory click behind.
 */
public final class MenuListener implements Listener {

    private final MenuService menus;
    private final Logger logger;

    public MenuListener(MenuService menus, Logger logger) {
        this.menus = menus;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Menu menu = menuOf(event.getView().getTopInventory());
        if (menu == null) {
            return;
        }

        event.setCancelled(true);
        // Belt and braces: a plugin running later could set the cursor or the result even on
        // a cancelled event, so blank them too.
        event.setResult(org.bukkit.event.Event.Result.DENY);

        // Clicks in the player's own half are swallowed, not dispatched: they are not
        // button presses, and treating them as such would fire buttons at raw slot indices
        // that happen to collide.
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        try {
            menu.onClick(event);
        } catch (RuntimeException e) {
            // A broken button must not leave the player stuck in a menu.
            logger.log(Level.WARNING, "A menu button threw for " + menu.viewer().getName()
                    + "; closing the menu.", e);
            menus.close(menu.viewer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (menuOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Menu menu = menuOf(event.getInventory());
        if (menu == null) {
            return;
        }
        menus.untrack(menu.viewer());
        try {
            menu.onClose(event);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "A menu's close handler threw.", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // InventoryCloseEvent does fire on quit, but not relying on that keeps the
        // registry from leaking if a future Paper build changes that ordering.
        menus.untrack(event.getPlayer());
    }

    private static Menu menuOf(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof Menu menu ? menu : null;
    }
}
