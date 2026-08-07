package dev.lk.gardenshop.item;

import dev.lk.gardenshop.core.ConfigSnapshot;
import dev.lk.gardenshop.core.config.ItemSettings;
import dev.lk.gardenshop.core.domain.CropDrop;
import dev.lk.gardenshop.core.registry.DropDefinition;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Turns an {@link ItemStack} into something the pricing engine can price.
 *
 * <h2>Identification order</h2>
 * <ol>
 *   <li>our own {@code mythic_type} tag — set when the item was generated, and
 *       immune to MythicMobs API changes;</li>
 *   <li>MythicMobs itself, for items harvested before this plugin was installed;</li>
 *   <li>the item adapter, which covers the two cases neither of the above can: a Mythic
 *       API call that has begun failing, and a crop supplied by some other item plugin
 *       and mapped in {@code crops.yml → extra-ids}.</li>
 * </ol>
 * The order is by trustworthiness, not by convenience. Our own tag is the only source that
 * cannot be wrong about an item we wrote it on; the adapter is last because it is the only
 * one that involves a third-party library.
 *
 * <h2>Why this method writes to the item</h2>
 * An item with no stored weight gets one assigned here and then <em>persisted</em>.
 * Deriving a weight without storing it would mean {@code /gs value} and the
 * {@code /gs sell} that follows could disagree — the appraisal would be a lie. So
 * resolution is deliberately not a pure read: the first time an item is looked at, it
 * is pinned.
 */
public final class HarvestResolver {

    /**
     * @param definition what the item is
     * @param weightKg   its authoritative weight
     * @param newlyPinned whether this call had to assign and store the weight
     */
    public record Harvest(DropDefinition definition, double weightKg, boolean newlyPinned) {

        public CropDrop toDrop() {
            return definition.withWeight(weightKg);
        }
    }

    private final ItemTagService tags;
    private final MythicItems mythic;
    private final AdapterItems adapter;
    private final LoreWeightParser loreParser;
    private final WeightStamper stamper;

    public HarvestResolver(ItemTagService tags, MythicItems mythic, AdapterItems adapter,
                           LoreWeightParser loreParser, WeightStamper stamper) {
        this.tags = tags;
        this.mythic = mythic;
        this.adapter = adapter;
        this.loreParser = loreParser;
        this.stamper = stamper;
    }

    /** Whether this item is one of our harvests at all, without touching it. */
    public boolean isHarvest(ItemStack item, ConfigSnapshot snapshot) {
        return findDefinition(item, snapshot).isPresent();
    }

    /**
     * Resolves an item, pinning its weight if it does not have one yet.
     *
     * @return empty when the item is not a known harvest drop
     */
    public Optional<Harvest> resolve(ItemStack item, ConfigSnapshot snapshot) {
        Optional<DropDefinition> found = findDefinition(item, snapshot);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        DropDefinition definition = found.get();
        ItemSettings settings = snapshot.items();

        OptionalDouble stored = tags.readWeight(item);
        if (stored.isPresent()) {
            return Optional.of(new Harvest(definition, stored.getAsDouble(), false));
        }

        // No stored weight: either a pre-plugin item, or one generated while
        // stamp-on-generate was off.
        if (settings.legacyLoreFallback()) {
            OptionalDouble fromLore = loreParser.parse(item, settings.weightLoreMarker());
            if (fromLore.isPresent()) {
                double adopted = stamper.adopt(item, definition, fromLore.getAsDouble(), settings);
                return Optional.of(new Harvest(definition, adopted, true));
            }
        }

        // Nothing to recover. Rolling a fresh weight keeps the item sellable, which
        // beats leaving a player holding something the shop refuses to look at.
        double rolled = stamper.stampFresh(item, definition, settings);
        return Optional.of(new Harvest(definition, rolled, true));
    }

    private Optional<DropDefinition> findDefinition(ItemStack item, ConfigSnapshot snapshot) {
        if (item == null || item.getType().isAir()) {
            return Optional.empty();
        }
        Optional<DropDefinition> tagged = tags.readMythicType(item)
                .flatMap(type -> snapshot.registry().find(type));
        if (tagged.isPresent()) {
            return tagged;
        }

        Optional<DropDefinition> fromMythic = mythic.typeOf(item)
                .flatMap(type -> snapshot.registry().find(type));
        if (fromMythic.isPresent()) {
            return fromMythic;
        }

        // Adapter reports every id an item answers to, so this walks them rather than trusting a
        // single one: our crops are Mythic items built on paper, and the vanilla id 'mc:paper' is a
        // true answer that happens to be the useless one.
        for (String adapterId : adapter.idsOf(item)) {
            Optional<DropDefinition> found = snapshot.registry().findByAdapterId(adapterId);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
