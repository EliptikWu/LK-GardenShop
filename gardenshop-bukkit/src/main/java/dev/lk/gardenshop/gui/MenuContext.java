package dev.lk.gardenshop.gui;

import dev.lk.gardenshop.core.ConfigSnapshot;
import dev.lk.gardenshop.core.pricing.PriceSweep;
import dev.lk.gardenshop.economy.EconomyProvider;
import dev.lk.gardenshop.item.MythicItems;
import dev.lk.gardenshop.item.PackIntegrity;
import dev.lk.gardenshop.pack.PackTracker;
import dev.lk.gardenshop.sell.SellLine;
import dev.lk.gardenshop.sell.SellService;
import dev.lk.gardenshop.stats.StatsService;
import dev.lk.gardenshop.util.Messages;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Everything the menus need, bundled so each one takes two constructor arguments instead of
 * eight.
 *
 * <p>The configuration arrives as a {@link Supplier} rather than a value: menus are built once
 * and can outlive a {@code /gs reload}, so each render must read whatever is live rather than
 * whatever was current when the menu opened.
 */
public record MenuContext(
        MenuService menus,
        Supplier<ConfigSnapshot> configs,
        SellService sell,
        StatsService stats,
        Messages messages,
        PriceSweep sweep,
        MythicItems mythic,
        PackIntegrity packIntegrity,
        PackTracker packs,
        Logger logger
) {

    public ConfigSnapshot snapshot() {
        return configs.get();
    }

    /**
     * Whether this player must be shown a menu with no pack art.
     *
     * <p>Decided per render rather than per menu, because {@link
     * dev.lk.gardenshop.core.config.MenuStyle#AUTO} answers it per player: someone who declined the
     * download would otherwise see the shop front as a missing-character box.
     */
    public boolean plainFor(Player player) {
        return snapshot().menuStyle().plainFor(player.getUniqueId(), packs::hasPack);
    }

    public EconomyProvider economy() {
        return sell.provider();
    }

    /**
     * The pack's own art for a drop type, or nothing if it cannot be had.
     *
     * <p>Gated on {@code resource-pack.installed}, the server-wide "this server ships no pack"
     * switch. With it off nobody has the textures, and a page of identical blank sheets carries less
     * than a page of stand-in materials does. It is <b>not</b> gated per player: a server that has
     * declared it ships the pack gets the real items, and a player who declined the download seeing
     * blank paper is the consequence of declining, not something to design around.
     */
    public Optional<ItemStack> packArt(String mythicType) {
        return snapshot().pack().installed() ? mythic.menuItem(mythicType) : Optional.empty();
    }

    /**
     * Whether this drop type has art of its own, or is wearing a plainer sibling's.
     *
     * <p>A type with no art drawn yet is indistinguishable from the one it borrows from, so showing
     * it as that crop invites the reader to conclude two different prices belong to the same plant.
     * Marked instead — see {@link dev.lk.gardenshop.item.PackIntegrity#borrowers}.
     */
    public boolean hasOwnArt(String mythicType) {
        return packIntegrity.latest().hasOwnArt(mythicType);
    }

    /** What a bulk sale of this player's bag would come to. */
    public Totals inventoryTotals(Player player) {
        List<SellLine> lines = sell.appraiseInventory(player);
        BigDecimal total = BigDecimal.ZERO;
        int items = 0;
        for (SellLine line : lines) {
            total = total.add(line.lineTotal());
            items += line.amount();
        }
        return new Totals(total, items, lines.size());
    }

    public record Totals(BigDecimal total, int items, int stacks) {

        public boolean isEmpty() {
            return stacks == 0;
        }
    }
}
