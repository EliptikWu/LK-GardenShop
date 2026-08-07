package dev.lk.gardenshop.item;

import dev.lk.gardenshop.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers a weight from an item's lore.
 *
 * <p>Only used for items that predate the plugin — anything harvested since carries
 * its weight in PDC. The pack writes the line as
 * {@code "§r&f&lWeight: &r1.<random.05to40>kg"}, and MythicMobs does not zero-pad
 * that placeholder, so a roll of 7 renders as {@code 1.7kg} where {@code 1.07kg} was
 * intended. There is no way to tell the two apart after the fact, which is why the
 * caller clamps whatever comes out of here to the drop type's declared range: a
 * legacy item ends up slightly over-valued at worst, rather than wildly so.
 */
public final class LoreWeightParser {

    /** Matches the number in front of "kg", tolerating a comma decimal separator. */
    private static final Pattern WEIGHT = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*kg", Pattern.CASE_INSENSITIVE);

    /**
     * @param marker case-insensitive substring identifying the weight line, e.g.
     *               {@code "weight:"}; colour codes are stripped before matching
     */
    public OptionalDouble parse(ItemStack item, String marker) {
        if (item == null || item.getType().isAir()) {
            return OptionalDouble.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return OptionalDouble.empty();
        }
        List<Component> lore = meta.lore();
        if (lore == null || lore.isEmpty()) {
            return OptionalDouble.empty();
        }

        String needle = marker.toLowerCase(Locale.ROOT);
        for (Component line : lore) {
            String plain = Text.plain(line);
            if (!plain.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            OptionalDouble parsed = extract(plain);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return OptionalDouble.empty();
    }

    /** Finds the index of the lore line carrying the marker, or -1. */
    public int indexOfWeightLine(List<Component> lore, String marker) {
        if (lore == null) {
            return -1;
        }
        String needle = marker.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lore.size(); i++) {
            if (Text.plain(lore.get(i)).toLowerCase(Locale.ROOT).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static OptionalDouble extract(String plainLine) {
        Matcher matcher = WEIGHT.matcher(plainLine);
        if (!matcher.find()) {
            return OptionalDouble.empty();
        }
        try {
            double value = Double.parseDouble(matcher.group(1).replace(',', '.'));
            return Double.isFinite(value) && value > 0.0 ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }
}
