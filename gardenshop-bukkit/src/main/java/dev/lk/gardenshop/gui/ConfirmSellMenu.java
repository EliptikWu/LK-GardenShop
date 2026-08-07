package dev.lk.gardenshop.gui;

import dev.lk.gardenshop.command.GardenShopCommand;
import dev.lk.gardenshop.core.config.GuiSettings;
import dev.lk.gardenshop.core.config.MenuLayout;
import dev.lk.gardenshop.economy.EconomyProvider;
import dev.lk.gardenshop.sell.SellResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.List;

/**
 * The last step before a bulk sale.
 *
 * <p>Its whole reason to exist is that {@code sell all} is irreversible and one misclick wide.
 * Favorites are the other half of that protection, and the count shown here is what tells a
 * player whether they remembered to set them.
 *
 * <h2>The number shown is a quote, not a promise</h2>
 * The total is computed when this screen opens. Confirming does <em>not</em> pay out that
 * figure: {@link dev.lk.gardenshop.sell.SellService#sellInventory} re-prices the inventory
 * from scratch, so a crop picked up or dropped in between is priced correctly rather than at a
 * stale rate. If the two differ, the message the player receives reports what was actually
 * paid. Selling at a displayed-but-stale price would be the bug; a number that moved is not.
 */
public final class ConfirmSellMenu extends Menu {

    private final MenuContext context;

    /** Captured at open time so the screen does not re-walk the inventory on every render. */
    private final BigDecimal quotedTotal;
    private final int quotedItems;
    private final int quotedStacks;
    private final int favorited;

    public ConfirmSellMenu(MenuContext context, Player viewer) {
        super(context.menus(), viewer);
        this.context = context;

        MenuContext.Totals totals = context.inventoryTotals(viewer);
        this.quotedTotal = totals.total();
        this.quotedItems = totals.items();
        this.quotedStacks = totals.stacks();
        this.favorited = countFavorited();
    }

    private MenuLayout layout() {
        return context.snapshot().gui().confirmMenu();
    }

    @Override
    protected Component title() {
        return MenuBackground.title(layout(), context.messages().get("gui.confirm-menu.title"),
                context.plainFor(viewer));
    }

    @Override
    protected int size() {
        return layout().size();
    }

    @Override
    protected void render() {
        clear();
        MenuLayout layout = layout();
        EconomyProvider economy = context.economy();

        layout.slot(GuiSettings.BUTTON_SUMMARY).ifPresent(slot -> set(slot, button(layout, GuiSettings.BUTTON_SUMMARY, "PAPER",
                context.messages().get("gui.confirm-menu.summary.name"),
                context.messages().getList("gui.confirm-menu.summary.lore",
                        Placeholder.unparsed("total", economy.format(quotedTotal)),
                        Placeholder.unparsed("items", Integer.toString(quotedItems)),
                        Placeholder.unparsed("stacks", Integer.toString(quotedStacks)),
                        Placeholder.unparsed("favorites", Integer.toString(favorited))))));

        layout.slot(GuiSettings.BUTTON_CONFIRM).ifPresent(slot -> set(slot, button(layout, GuiSettings.BUTTON_CONFIRM, "LIME_CONCRETE",
                context.messages().get("gui.confirm-menu.confirm.name"),
                context.messages().getList("gui.confirm-menu.confirm.lore",
                        Placeholder.unparsed("total", economy.format(quotedTotal))))));

        layout.slot(GuiSettings.BUTTON_CANCEL).ifPresent(slot -> set(slot, button(layout, GuiSettings.BUTTON_CANCEL, "RED_CONCRETE",
                context.messages().get("gui.confirm-menu.cancel.name"),
                context.messages().getList("gui.confirm-menu.cancel.lore"))));

        if (layout.hasFiller()) {
            fill(Icon.filler(Icon.material(layout.filler(), Material.GRAY_STAINED_GLASS_PANE,
                    context.logger())));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        MenuLayout layout = layout();
        int slot = event.getSlot();

        if (layout.slot(GuiSettings.BUTTON_CONFIRM).map(configured -> configured == slot).orElse(false)) {
            confirm();
        } else if (layout.slot(GuiSettings.BUTTON_CANCEL).map(configured -> configured == slot)
                .orElse(false)) {
            context.menus().openLater(new SellMenu(context, viewer));
        }
    }

    private void confirm() {
        if (!viewer.hasPermission(GardenShopCommand.PERMISSION_SELL)) {
            context.messages().send(viewer, "no-permission");
            return;
        }

        // Re-prices from the live inventory; the quote above is only ever a display.
        SellResult result = context.sell().sellInventory(viewer);
        GardenShopCommand.reportSale(context.messages(), context.economy(), viewer, result);

        if (result.isSold() && result.total().compareTo(quotedTotal) != 0) {
            context.messages().send(viewer, "gui.confirm-menu.total-changed",
                    Placeholder.unparsed("quoted", context.economy().format(quotedTotal)),
                    Placeholder.unparsed("paid", context.economy().format(result.total())));
        }
        context.menus().openLater(new SellMenu(context, viewer));
    }

    /**
     * How many crops the bulk sale will leave alone.
     *
     * <p>Counted here rather than taken from the appraisal, because the appraisal
     * deliberately excludes favorites and so cannot report what it skipped. This is the
     * number that tells a player whether they remembered to protect their record harvest.
     */
    private int countFavorited() {
        return context.sell().countFavoritedHarvests(viewer);
    }

    /**
     * Builds a configurable button, resolving its look for this viewer.
     *
     * <p>The style is asked per render because {@code AUTO} answers it per player: someone who
     * declined the pack download must get the vanilla fallback, not a missing-glyph box.
     */
    private ItemStack button(MenuLayout layout, String id, String fallback,
                             Component name, List<Component> lore) {
        return Icon.of(layout.icon(id, fallback), context.plainFor(viewer), context.logger(), name, lore);
    }

    private ItemStack button(MenuLayout layout, String id, String fallback, Component name) {
        return button(layout, id, fallback, name, List.of());
    }

}
