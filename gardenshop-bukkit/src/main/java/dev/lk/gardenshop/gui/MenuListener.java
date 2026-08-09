package dev.lk.gardenshop.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
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
 * makes sure nothing can be moved into or out of one.
 *
 * <h2>Where the line is drawn</h2>
 * Every click and drag inside the menu is cancelled outright. No slot in it holds a real item, so
 * there is nothing a click there could legitimately do beyond press a button.
 *
 * <p>The player's own half stays live. Cancelling the whole view was one line and needed no case
 * analysis, but it froze their bag — with the shop open they could not so much as move a crop into
 * their hand. What replaced it is the case analysis: two actions cross the boundary from below and
 * are refused, shift-click pushing a stack up into the menu and double-click collecting matching
 * stacks out of it. A drag is refused as soon as one of its slots falls inside the menu, and
 * anything allowed below schedules a re-render, since the menu describes a bag that just changed.
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

        boolean inMenu = event.getClickedInventory() == event.getView().getTopInventory();

        // The player's own inventory stays usable while a menu is open. Cancelling every click was
        // simpler but it froze their bag: they could not so much as move a crop into their hand
        // with the shop in front of them.
        if (!inMenu) {
            if (!reachesIntoMenu(event)) {
                // Their items, their business -- but the menu is now describing a bag that has
                // changed, so re-render it a tick later, once the move has actually happened.
                refreshLater(menu);
                return;
            }
            // Shift-clicking and double-clicking cross the boundary: one pushes an item into the
            // menu, the other collects matching stacks out of it. Either would have a player
            // swapping items with our buttons.
            event.setCancelled(true);
            event.setResult(org.bukkit.event.Event.Result.DENY);
            return;
        }

        event.setCancelled(true);
        // Belt and braces: a plugin running later could set the cursor or the result even on
        // a cancelled event, so blank them too.
        event.setResult(org.bukkit.event.Event.Result.DENY);

        try {
            menu.onClick(event);
        } catch (RuntimeException e) {
            // A broken button must not leave the player stuck in a menu.
            logger.log(Level.WARNING, "A menu button threw for " + menu.viewer().getName()
                    + "; closing the menu.", e);
            menus.close(menu.viewer());
        }
    }

    /**
     * Whether a click in the player's own inventory would still move items across the boundary.
     *
     * <p>Two actions do. {@code MOVE_TO_OTHER_INVENTORY} is a shift-click, which pushes the item
     * into the open menu; {@code COLLECT_TO_CURSOR} is a double-click, which gathers every matching
     * stack it can find <em>including the menu's own icons</em>. Both would let a player walk off
     * with a button.
     */
    private static boolean reachesIntoMenu(InventoryClickEvent event) {
        return event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR;
    }

    /**
     * Re-renders a tick later, after the click has been applied.
     *
     * <p>Now that the player's inventory is live, the menu can go stale while it is open: it shows
     * the crop in their hand and what their bag is worth, and both change the moment they move
     * something. Rendering immediately would read the inventory as it was before the click.
     */
    private void refreshLater(Menu menu) {
        menus.refreshLater(menu);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        Menu menu = menuOf(event.getView().getTopInventory());
        if (menu == null) {
            return;
        }
        // Only when the drag actually touches the menu. A drag entirely inside the player's own
        // inventory is theirs to make.
        int menuSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < menuSize)) {
            event.setCancelled(true);
            return;
        }
        refreshLater(menu);
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
