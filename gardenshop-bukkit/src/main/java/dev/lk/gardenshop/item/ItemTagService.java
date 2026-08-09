package dev.lk.gardenshop.item;

import dev.lk.gardenshop.core.domain.Mutation;
import dev.lk.gardenshop.core.registry.DropDefinition;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.StringJoiner;

/** Reads and writes the plugin's persistent tags on an {@link ItemStack}. */
public final class ItemTagService {

    private final ItemKeys keys;

    public ItemTagService(ItemKeys keys) {
        this.keys = keys;
    }

    public OptionalDouble readWeight(ItemStack item) {
        PersistentDataContainer container = containerOf(item);
        if (container == null) {
            return OptionalDouble.empty();
        }
        Double weight = container.get(keys.weightKg(), PersistentDataType.DOUBLE);
        // A non-positive stored weight would break the ratio maths downstream, so
        // treat it as absent and let the caller re-derive it.
        return weight == null || !Double.isFinite(weight) || weight <= 0.0
                ? OptionalDouble.empty()
                : OptionalDouble.of(weight);
    }

    public Optional<String> readMythicType(ItemStack item) {
        return readString(item, keys.mythicType());
    }

    public Optional<String> readSpecies(ItemStack item) {
        return readString(item, keys.species());
    }

    public boolean isFavorite(ItemStack item) {
        PersistentDataContainer container = containerOf(item);
        if (container == null) {
            return false;
        }
        Byte flag = container.get(keys.favorite(), PersistentDataType.BYTE);
        return flag != null && flag != 0;
    }

    /**
     * Flips the favorite flag.
     *
     * @return the new state, {@code true} meaning protected from bulk sale
     */
    public boolean toggleFavorite(ItemStack item) {
        boolean next = !isFavorite(item);
        item.editMeta(meta -> {
            if (next) {
                meta.getPersistentDataContainer().set(keys.favorite(), PersistentDataType.BYTE, (byte) 1);
            } else {
                // Removing rather than storing 0 keeps unfavourited items byte-identical
                // to never-favourited ones, so they still stack together.
                meta.getPersistentDataContainer().remove(keys.favorite());
            }
        });
        return next;
    }

    /**
     * Stamps identity and weight onto an item.
     *
     * <p>Species, variant and mutations are duplicated out of the registry so
     * placeholders and stats can read them without a lookup, and so an item stays
     * self-describing if a crop is later removed from {@code crops.yml}.
     */
    public void writeIdentity(ItemStack item, DropDefinition definition, double weightKg) {
        item.editMeta(meta -> {
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(keys.schema(), PersistentDataType.INTEGER, ItemKeys.SCHEMA_VERSION);
            container.set(keys.weightKg(), PersistentDataType.DOUBLE, weightKg);
            container.set(keys.mythicType(), PersistentDataType.STRING, definition.mythicType());
            container.set(keys.species(), PersistentDataType.STRING, definition.species().id());
            container.set(keys.variant(), PersistentDataType.STRING, definition.variant().name());
            container.set(keys.mutations(), PersistentDataType.STRING, joinMutations(definition.mutations()));
        });
    }

    private static String joinMutations(Set<Mutation> mutations) {
        if (mutations.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(",");
        for (Mutation mutation : mutations) {
            joiner.add(mutation.name());
        }
        return joiner.toString();
    }

    private Optional<String> readString(ItemStack item, org.bukkit.NamespacedKey key) {
        PersistentDataContainer container = containerOf(item);
        if (container == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(container.get(key, PersistentDataType.STRING));
    }

    /** {@code null} for air or anything without meta. */
    private static PersistentDataContainer containerOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer();
    }
}
