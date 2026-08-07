package dev.lk.gardenshop.item;

import dev.lk.gardenshop.core.config.ItemSettings;
import dev.lk.gardenshop.core.registry.DropDefinition;
import dev.lk.gardenshop.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Assigns an item its authoritative weight and makes the lore say so.
 *
 * <p>The plugin owns the weight rather than reading the pack's. Two reasons: the
 * pack's {@code <random.AAtoBB>} lore is ambiguous for any range whose lower bound
 * starts with a zero (see {@link LoreWeightParser}), and a weight the server wrote
 * into PDC cannot be forged by renaming an item.
 */
public final class WeightStamper {

    /** Two decimals, matching what the lore shows, so quotes are never off by a rounding. */
    private static final String WEIGHT_PATTERN = "%.2f";

    private final ItemTagService tags;
    private final LoreWeightParser loreParser;

    public WeightStamper(ItemTagService tags, LoreWeightParser loreParser) {
        this.tags = tags;
        this.loreParser = loreParser;
    }

    /**
     * Rolls a fresh weight from the type's range and stamps it.
     *
     * @return the weight assigned
     */
    public double stampFresh(ItemStack item, DropDefinition definition, ItemSettings settings) {
        double weight = definition.weightRange().roll(ThreadLocalRandom.current());
        apply(item, definition, weight, settings);
        return weight;
    }

    /**
     * Stamps a weight recovered from an older item's lore, clamped into the range the
     * type is supposed to roll within.
     *
     * @return the weight actually stored, which may differ from {@code weightKg}
     */
    public double adopt(ItemStack item, DropDefinition definition, double weightKg, ItemSettings settings) {
        double clamped = definition.weightRange().clamp(weightKg);
        apply(item, definition, clamped, settings);
        return clamped;
    }

    private void apply(ItemStack item, DropDefinition definition, double weightKg, ItemSettings settings) {
        tags.writeIdentity(item, definition, weightKg);
        if (settings.rewriteLore()) {
            rewriteLore(item, weightKg, settings);
        }
    }

    /**
     * Replaces the lore's weight line with the stored value, or inserts one at the top
     * if the item has none.
     */
    public void rewriteLore(ItemStack item, double weightKg, ItemSettings settings) {
        String formatted = String.format(Locale.ROOT, WEIGHT_PATTERN, weightKg);
        Component line = Text.legacy(String.format(Locale.ROOT, settings.weightLoreFormat(), formatted));

        item.editMeta(meta -> {
            List<Component> existing = meta.lore();
            List<Component> lore = existing == null ? new ArrayList<>() : new ArrayList<>(existing);

            int index = loreParser.indexOfWeightLine(lore, settings.weightLoreMarker());
            if (index >= 0) {
                lore.set(index, line);
            } else {
                lore.add(0, line);
            }
            meta.lore(lore);
        });
    }

    /** The 2-decimal rendering used in lore and messages, so both always agree. */
    public static String formatWeight(double weightKg) {
        return String.format(Locale.ROOT, WEIGHT_PATTERN, weightKg);
    }
}
