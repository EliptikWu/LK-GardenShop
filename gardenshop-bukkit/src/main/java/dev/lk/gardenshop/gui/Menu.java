package dev.lk.gardenshop.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Base class for the plugin's chest menus.
 *
 * <p>Being the inventory's own {@link InventoryHolder} is how a click is recognised as
 * ours: {@code view.getTopInventory().getHolder() instanceof Menu} is exact, unlike
 * matching on a title that a resource pack or a translation could change.
 *
 * <h2>The safety rule these menus rely on</h2>
 * No menu ever holds a real, sellable item. Every slot is a display icon, and
 * {@link MenuListener} cancels every click and drag in the whole view unconditionally. That
 * combination means there is no path — shift-click, number-key swap, drag-distribute,
 * double-click-collect, drop-outside — by which a player can take something out of a menu or
 * put something in. A "drop your crops in here" chest would be more tactile and would
 * introduce a whole family of item-loss bugs on close, crash and reload; showing the crops
 * as icons and selling from the real inventory gets the same feel with none of that.
 */
public abstract class Menu implements InventoryHolder {

    protected final MenuService menus;
    protected final Player viewer;

    private Inventory inventory;

    protected Menu(MenuService menus, Player viewer) {
        this.menus = Objects.requireNonNull(menus, "menus");
        this.viewer = Objects.requireNonNull(viewer, "viewer");
    }

    protected abstract Component title();

    protected abstract int size();

    /** Fills the inventory. Called on open and on every refresh. */
    protected abstract void render();

    /**
     * Handles a click on this menu. The event is already cancelled; implementations decide
     * what the click means and must not un-cancel it.
     */
    public abstract void onClick(InventoryClickEvent event);

    /** Called when the menu closes for any reason, including a server shutdown. */
    public void onClose(InventoryCloseEvent event) {
        // Nothing by default: menus hold no items, so there is nothing to hand back.
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, size(), title());
        }
        return inventory;
    }

    public Player viewer() {
        return viewer;
    }

    /** Builds, renders and shows the menu. */
    public void open() {
        Inventory target = getInventory();
        render();
        menus.track(this);
        viewer.openInventory(target);
    }

    /** Re-renders in place, so a price or a total updates without the screen flickering. */
    public void refresh() {
        if (inventory == null) {
            return;
        }
        render();
        viewer.updateInventory();
    }

    protected void set(int slot, ItemStack icon) {
        if (slot >= 0 && slot < getInventory().getSize()) {
            getInventory().setItem(slot, icon);
        }
    }

    /** Paints every empty slot, so the menu reads as a panel rather than a chest. */
    protected void fill(ItemStack filler) {
        if (filler == null) {
            return;
        }
        Inventory target = getInventory();
        for (int slot = 0; slot < target.getSize(); slot++) {
            ItemStack existing = target.getItem(slot);
            if (existing == null || existing.getType().isAir()) {
                target.setItem(slot, filler);
            }
        }
    }

    protected void clear() {
        if (inventory != null) {
            inventory.clear();
        }
    }
}
