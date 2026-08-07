package dev.lk.gardenshop.gui;

import dev.lk.gardenshop.command.GardenShopCommand;
import dev.lk.gardenshop.core.config.GuiSettings;
import dev.lk.gardenshop.core.config.MenuLayout;
import dev.lk.gardenshop.core.domain.CropDrop;
import dev.lk.gardenshop.economy.EconomyProvider;
import dev.lk.gardenshop.item.WeightStamper;
import dev.lk.gardenshop.sell.SellLine;
import dev.lk.gardenshop.sell.SellResult;
import dev.lk.gardenshop.stats.PlayerStats;
import dev.lk.gardenshop.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * The shop front: what the held crop is worth, what the whole bag is worth, and the two
 * buttons that act on either.
 *
 * <p>Stands in for Steven's Sell Stuff stand - appraise, sell this, sell everything - with the
 * favorite flag still deciding what a bulk sale is allowed to touch.
 */
public final class SellMenu extends Menu {

    private final MenuContext context;

    public SellMenu(MenuContext context, Player viewer) {
        super(context.menus(), viewer);
        this.context = context;
    }

    private MenuLayout layout() {
        return context.snapshot().gui().sellMenu();
    }

    @Override
    protected Component title() {
        // The backdrop rides in the title as a font glyph; MenuBackground drops it when this
        // viewer has no pack, since the glyph would then render as a missing-character box.
        return MenuBackground.title(layout(), context.messages().get("gui.sell-menu.title"),
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

        renderHeld(layout, economy);
        renderSellHand(layout, economy);
        renderSellAll(layout, economy);
        renderPrices(layout);
        renderStats(layout, economy);

        if (layout.hasFiller()) {
            fill(Icon.filler(Icon.material(layout.filler(), Material.GRAY_STAINED_GLASS_PANE,
                    context.logger())));
        }
    }

    // --------------------------------------------------------------------- rendering

    private void renderHeld(MenuLayout layout, EconomyProvider economy) {
        Optional<Integer> slot = layout.slot(GuiSettings.BUTTON_HELD);
        if (slot.isEmpty()) {
            return;
        }

        Optional<SellLine> appraisal = context.sell().appraiseHand(viewer);
        if (appraisal.isEmpty()) {
            set(slot.get(), button(layout, GuiSettings.BUTTON_HELD, "BARRIER",
                    context.messages().get("gui.sell-menu.held.empty-name"),
                    context.messages().getList("gui.sell-menu.held.empty-lore")));
            return;
        }

        SellLine line = appraisal.get();
        TagResolver[] tags = cropTags(line, economy);
        // A copy of the player's actual crop, so the icon carries its real model rather than
        // a stand-in that looks like something else.
        ItemStack source = viewer.getInventory().getItemInMainHand();
        set(slot.get(), Icon.fromItem(source,
                context.messages().get("gui.sell-menu.held.name", tags),
                context.messages().getList("gui.sell-menu.held.lore", tags)));
    }

    private void renderSellHand(MenuLayout layout, EconomyProvider economy) {
        Optional<Integer> slot = layout.slot(GuiSettings.BUTTON_SELL_HAND);
        if (slot.isEmpty()) {
            return;
        }

        Optional<SellLine> appraisal = context.sell().appraiseHand(viewer);
        boolean sellable = appraisal.isPresent() && economy.isAvailable();

        if (!sellable) {
            set(slot.get(), Icon.of(Material.GRAY_DYE,
                    context.messages().get("gui.sell-menu.sell-hand.disabled-name"),
                    context.messages().getList("gui.sell-menu.sell-hand.disabled-lore",
                            Placeholder.unparsed("reason", economy.isAvailable()
                                    ? context.messages().plain("gui.reason.not-a-crop")
                                    : economy.description()))));
            return;
        }

        TagResolver[] tags = cropTags(appraisal.get(), economy);
        set(slot.get(), button(layout, GuiSettings.BUTTON_SELL_HAND, "GOLD_INGOT",
                context.messages().get("gui.sell-menu.sell-hand.name", tags),
                context.messages().getList("gui.sell-menu.sell-hand.lore", tags)));
    }

    private void renderSellAll(MenuLayout layout, EconomyProvider economy) {
        Optional<Integer> slot = layout.slot(GuiSettings.BUTTON_SELL_ALL);
        if (slot.isEmpty()) {
            return;
        }

        MenuContext.Totals totals = context.inventoryTotals(viewer);
        if (totals.stacks() == 0 || !economy.isAvailable()) {
            set(slot.get(), Icon.of(Material.GRAY_DYE,
                    context.messages().get("gui.sell-menu.sell-all.empty-name"),
                    context.messages().getList("gui.sell-menu.sell-all.empty-lore",
                            Placeholder.unparsed("reason", economy.isAvailable()
                                    ? context.messages().plain("gui.reason.nothing-to-sell")
                                    : economy.description()))));
            return;
        }

        set(slot.get(), button(layout, GuiSettings.BUTTON_SELL_ALL, "CHEST",
                context.messages().get("gui.sell-menu.sell-all.name"),
                context.messages().getList("gui.sell-menu.sell-all.lore",
                        Placeholder.unparsed("total", economy.format(totals.total())),
                        Placeholder.unparsed("items", Integer.toString(totals.items())),
                        Placeholder.unparsed("stacks", Integer.toString(totals.stacks())))));
    }

    private void renderPrices(MenuLayout layout) {
        Optional<Integer> slot = layout.slot(GuiSettings.BUTTON_PRICES);
        if (slot.isEmpty()) {
            return;
        }
        set(slot.get(), button(layout, GuiSettings.BUTTON_PRICES, "BOOK",
                context.messages().get("gui.sell-menu.prices.name"),
                context.messages().getList("gui.sell-menu.prices.lore",
                        Placeholder.unparsed("crops",
                                Integer.toString(context.snapshot().species().size())))));
    }

    private void renderStats(MenuLayout layout, EconomyProvider economy) {
        Optional<Integer> slot = layout.slot(GuiSettings.BUTTON_STATS);
        if (slot.isEmpty()) {
            return;
        }
        PlayerStats stats = context.stats().of(viewer.getUniqueId());
        set(slot.get(), button(layout, GuiSettings.BUTTON_STATS, "GOLDEN_HOE",
                context.messages().get("gui.sell-menu.stats.name",
                        Placeholder.unparsed("player", viewer.getName())),
                context.messages().getList("gui.sell-menu.stats.lore",
                        Placeholder.unparsed("earned", economy.format(stats.totalEarned())),
                        Placeholder.unparsed("sold", Long.toString(stats.itemsSold())),
                        Placeholder.unparsed("sales", Long.toString(stats.salesCount())))));
    }

    // ----------------------------------------------------------------------- clicking

    @Override
    public void onClick(InventoryClickEvent event) {
        MenuLayout layout = layout();
        int slot = event.getSlot();

        if (matches(layout, GuiSettings.BUTTON_SELL_HAND, slot)) {
            sellHand();
        } else if (matches(layout, GuiSettings.BUTTON_SELL_ALL, slot)) {
            sellAll();
        } else if (matches(layout, GuiSettings.BUTTON_PRICES, slot)) {
            openPriceBook();
        }
    }

    private void sellHand() {
        if (!viewer.hasPermission(GardenShopCommand.PERMISSION_SELL)) {
            context.messages().send(viewer, "no-permission");
            return;
        }
        SellResult result = context.sell().sellHand(viewer);
        GardenShopCommand.reportSale(context.messages(), context.economy(), viewer, result);
        // Re-rendered rather than closed: a player emptying their bag one stack at a time
        // should not have to reopen the menu between each.
        refresh();
    }

    private void sellAll() {
        if (!viewer.hasPermission(GardenShopCommand.PERMISSION_SELL)) {
            context.messages().send(viewer, "no-permission");
            return;
        }
        if (context.inventoryTotals(viewer).isEmpty()) {
            return;
        }

        if (context.snapshot().gui().confirmBulkSale()) {
            context.menus().openLater(new ConfirmSellMenu(context, viewer));
            return;
        }
        SellResult result = context.sell().sellInventory(viewer);
        GardenShopCommand.reportSale(context.messages(), context.economy(), viewer, result);
        refresh();
    }

    private void openPriceBook() {
        if (!viewer.hasPermission(GardenShopCommand.PERMISSION_PRICES)) {
            context.messages().send(viewer, "no-permission");
            return;
        }
        context.menus().openLater(new PriceBookMenu(context, viewer));
    }

    private static boolean matches(MenuLayout layout, String button, int slot) {
        return layout.slot(button).map(configured -> configured == slot).orElse(false);
    }

    // ----------------------------------------------------------------------- helpers

    static TagResolver[] cropTags(SellLine line, EconomyProvider economy) {
        CropDrop drop = line.quote().drop();
        return new TagResolver[]{
                Placeholder.component("crop", Text.legacy(drop.species().displayName())),
                Placeholder.unparsed("variant", drop.variant().name()),
                Placeholder.unparsed("mutations", drop.mutations().isEmpty()
                        ? "-"
                        : String.join(", ", drop.mutations().stream().map(Enum::name).toList())),
                Placeholder.unparsed("weight", WeightStamper.formatWeight(drop.weightKg())),
                Placeholder.component("band", Text.legacy(line.quote().bandLabel())),
                Placeholder.unparsed("unit", economy.format(line.quote().unitPrice())),
                Placeholder.unparsed("amount", Integer.toString(line.amount())),
                Placeholder.unparsed("total", economy.format(line.lineTotal()))
        };
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
