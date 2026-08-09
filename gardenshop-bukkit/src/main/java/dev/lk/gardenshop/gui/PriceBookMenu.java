package dev.lk.gardenshop.gui;

import dev.lk.gardenshop.core.config.GuiSettings;
import dev.lk.gardenshop.core.config.MenuLayout;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.Variant;
import dev.lk.gardenshop.core.pricing.PriceSweep;
import dev.lk.gardenshop.core.registry.DropDefinition;
import dev.lk.gardenshop.economy.EconomyProvider;
import dev.lk.gardenshop.item.WeightStamper;
import dev.lk.gardenshop.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The price sheet, browsable: one icon per crop, click through to every drop type it has.
 *
 * <p>The same figures {@code /gs prices} prints, which is the point — an owner tuning
 * {@code pricing.yml} can reload and see the effect without leaving the game, and a player
 * can find out what is worth planting without reading a wiki.
 */
public final class PriceBookMenu extends Menu {

    private final MenuContext context;

    /** {@code null} on the crop list; set once a crop has been opened. */
    private final Species focus;
    private final int page;

    public PriceBookMenu(MenuContext context, Player viewer) {
        this(context, viewer, null, 0);
    }

    private PriceBookMenu(MenuContext context, Player viewer, Species focus, int page) {
        super(context.menus(), viewer);
        this.context = context;
        this.focus = focus;
        this.page = page;
    }

    private MenuLayout layout() {
        return context.snapshot().gui().priceBook();
    }

    /** Slots above the navigation row, where content goes. */
    /**
     * The first slot content is laid into.
     *
     * <p>One row down rather than at the top: the backdrop's panel starts below its signage, and a
     * row of drops level with the sign sits on the woodwork instead of inside the panel.
     */
    private static final int CONTENT_START = MenuLayout.COLUMNS;

    /** How many slots content may use: everything but the skipped top row and the navigation row. */
    private int contentSlots() {
        return Math.max(MenuLayout.COLUMNS,
                layout().size() - CONTENT_START - MenuLayout.COLUMNS);
    }

    @Override
    protected Component title() {
        Component label = focus == null
                ? context.messages().get("gui.price-book.title")
                : context.messages().get("gui.price-book.crop-title",
                        Placeholder.component("crop", Text.legacy(focus.displayName())));
        return MenuBackground.title(layout(), label, context.plainFor(viewer));
    }

    @Override
    protected int size() {
        return layout().size();
    }

    @Override
    protected void render() {
        clear();
        if (focus == null) {
            renderCropList();
        } else {
            renderCropDetail();
        }
        renderNavigation();

        if (layout().hasFiller()) {
            fill(Icon.filler(Icon.material(layout().filler(), Material.GRAY_STAINED_GLASS_PANE,
                    context.logger())));
        }
    }

    private void renderCropList() {
        EconomyProvider economy = context.economy();
        List<PriceSweep.CropRow> rows = context.sweep()
                .overview(context.snapshot().registry(), context.snapshot().pricing());

        int slot = 0;
        for (PriceSweep.CropRow row : rows) {
            if (slot >= contentSlots()) {
                break;
            }
            set(CONTENT_START + slot++, icon(plainDropOf(row.species().id()), Material.WHEAT,
                    context.messages().get("gui.price-book.crop.name",
                            Placeholder.component("crop", Text.legacy(row.species().displayName()))),
                    context.messages().getList("gui.price-book.crop.lore",
                            Placeholder.unparsed("id", row.species().id()),
                            Placeholder.unparsed("typical", economy.format(row.plainMid())),
                            Placeholder.unparsed("min", economy.format(row.cheapest())),
                            Placeholder.unparsed("max", economy.format(row.dearest())),
                            Placeholder.unparsed("multiple", round(row.multiple())),
                            Placeholder.unparsed("ref",
                                    WeightStamper.formatWeight(row.species().baseWeightKg())))));
        }
    }

    private void renderCropDetail() {
        EconomyProvider economy = context.economy();
        List<PriceSweep.TypeRow> rows = context.sweep()
                .forCrop(focus.id(), context.snapshot().registry(), context.snapshot().pricing());

        int perPage = contentSlots();
        int from = page * perPage;
        for (int i = 0; i < perPage && from + i < rows.size(); i++) {
            PriceSweep.TypeRow row = rows.get(from + i);
            set(CONTENT_START + i, icon(row.mythicType(), materialFor(row),
                    context.messages().get("gui.price-book.type.name",
                            Placeholder.unparsed("variant", row.variant().name()),
                            Placeholder.unparsed("mutations", mutationsOf(row))),
                    context.messages().getList("gui.price-book.type.lore",
                            Placeholder.unparsed("wmin",
                                    WeightStamper.formatWeight(row.definition().weightRange().minKg())),
                            Placeholder.unparsed("wmax",
                                    WeightStamper.formatWeight(row.definition().weightRange().maxKg())),
                            Placeholder.component("band", Text.legacy(row.atMax().bandLabel())),
                            Placeholder.unparsed("pmin", economy.format(row.atMin().unitPrice())),
                            Placeholder.unparsed("pmax", economy.format(row.atMax().unitPrice())),
                            Placeholder.unparsed("spread", round(row.spread())),
                            Placeholder.unparsed("type", row.mythicType()))));
        }
    }

    private void renderNavigation() {
        MenuLayout layout = layout();

        layout.slot(GuiSettings.BUTTON_BACK).ifPresent(slot -> set(slot, button(layout, GuiSettings.BUTTON_BACK, "ARROW",
                context.messages().get(focus == null
                        ? "gui.price-book.back-to-shop"
                        : "gui.price-book.back-to-crops"))));

        if (focus == null) {
            return;
        }

        int total = totalPages();
        if (page > 0) {
            layout.slot(GuiSettings.BUTTON_PREVIOUS_PAGE).ifPresent(slot -> set(slot, button(layout, GuiSettings.BUTTON_PREVIOUS_PAGE, "SPECTRAL_ARROW",
                    context.messages().get("gui.price-book.previous-page",
                            Placeholder.unparsed("page", Integer.toString(page)),
                            Placeholder.unparsed("pages", Integer.toString(total))))));
        }
        if (page + 1 < total) {
            layout.slot(GuiSettings.BUTTON_NEXT_PAGE).ifPresent(slot -> set(slot, button(layout, GuiSettings.BUTTON_NEXT_PAGE, "SPECTRAL_ARROW",
                    context.messages().get("gui.price-book.next-page",
                            Placeholder.unparsed("page", Integer.toString(page + 2)),
                            Placeholder.unparsed("pages", Integer.toString(total))))));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        MenuLayout layout = layout();
        int slot = event.getSlot();

        if (is(layout, GuiSettings.BUTTON_BACK, slot)) {
            if (focus == null) {
                context.menus().openLater(new SellMenu(context, viewer));
            } else {
                context.menus().openLater(new PriceBookMenu(context, viewer, null, 0));
            }
            return;
        }
        if (focus != null && is(layout, GuiSettings.BUTTON_PREVIOUS_PAGE, slot) && page > 0) {
            context.menus().openLater(new PriceBookMenu(context, viewer, focus, page - 1));
            return;
        }
        if (focus != null && is(layout, GuiSettings.BUTTON_NEXT_PAGE, slot) && page + 1 < totalPages()) {
            context.menus().openLater(new PriceBookMenu(context, viewer, focus, page + 1));
            return;
        }

        // A content slot on the crop list opens that crop. The raw slot has to be mapped back
        // through CONTENT_START, or every click would open the crop one place along.
        if (focus == null && slot >= CONTENT_START && slot < CONTENT_START + contentSlots()) {
            int index = slot - CONTENT_START;
            List<Species> species = new ArrayList<>(context.snapshot().species());
            if (index < species.size()) {
                context.menus().openLater(new PriceBookMenu(context, viewer, species.get(index), 0));
            }
        }
    }

    private int totalPages() {
        if (focus == null) {
            return 1;
        }
        int rows = context.sweep()
                .forCrop(focus.id(), context.snapshot().registry(), context.snapshot().pricing())
                .size();
        int perPage = contentSlots();
        return Math.max(1, (rows + perPage - 1) / perPage);
    }

    /**
     * The crop itself, or a stand-in material when the pack cannot supply it.
     *
     * <p>Showing the real item is the whole point of this page: thirty drop types rendered as three
     * repeated vanilla materials tells a reader nothing about which is which, where the pack's own art
     * distinguishes every one of them. Mythic hands the item over ready-made, so there is no table of
     * model numbers to keep in step with the pack.
     */
    private ItemStack icon(String mythicType, Material fallback, Component name, List<Component> lore) {
        if (!context.hasOwnArt(mythicType)) {
            // Nothing is drawn for this drop yet: it wears a plainer sibling's sprite, so showing it
            // would put the same picture next to two different prices and invite the reader to
            // conclude they are the same plant. A barrier says "not this one" without pretending.
            List<Component> marked = new ArrayList<>(lore);
            marked.add(context.messages().get("gui.price-book.type.no-art"));
            return Icon.of(unavailableIcon(), name, marked);
        }
        return context.packArt(mythicType)
                .map(art -> Icon.fromItem(art, name, lore))
                .orElseGet(() -> Icon.of(fallback, name, lore));
    }

    /** Configurable, because "unavailable" is a look an owner may want to match to their pack. */
    private Material unavailableIcon() {
        return Icon.material(layout().icon(GuiSettings.BUTTON_UNAVAILABLE, "BARRIER").materialFor(false),
                Material.BARRIER, context.logger());
    }

    /**
     * A crop's plain drop — NORMAL variant, no mutations — which is the one that looks like the crop.
     *
     * <p>Found by asking the registry rather than composing the name here: the composer is the only
     * thing that knows how names are built, and duplicating that rule in a menu is how the two drift.
     */
    private String plainDropOf(String speciesId) {
        return context.snapshot().registry().all().stream()
                .filter(drop -> drop.species().id().equals(speciesId))
                .filter(drop -> drop.variant() == Variant.NORMAL && drop.mutations().isEmpty())
                .map(DropDefinition::mythicType)
                .findFirst()
                .orElse("");
    }

    /**
     * Gold and Rainbow drops get their own stand-in so a page is readable without the pack.
     *
     * <p>The plain one is wheat rather than the paper these items are really built on. Paper would
     * be the honest answer and the wrong picture: the crop pack owns {@code models/item/paper.json},
     * so on a server running it a plain sheet of paper is drawn as one of its crops — a specific,
     * wrong crop, right next to a price.
     */
    private static Material materialFor(PriceSweep.TypeRow row) {
        return switch (row.variant()) {
            case GOLD -> Material.GOLD_NUGGET;
            case RAINBOW -> Material.AMETHYST_SHARD;
            case NORMAL -> Material.WHEAT;
        };
    }

    private static String mutationsOf(PriceSweep.TypeRow row) {
        if (row.definition().mutations().isEmpty()) {
            return "-";
        }
        return String.join("+", row.definition().mutations().stream().map(Enum::name).toList());
    }

    private static boolean is(MenuLayout layout, String button, int slot) {
        return layout.slot(button).map(configured -> configured == slot).orElse(false);
    }

    private static String round(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
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
