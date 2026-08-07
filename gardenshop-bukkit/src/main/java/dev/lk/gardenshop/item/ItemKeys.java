package dev.lk.gardenshop.item;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * The plugin's {@link org.bukkit.persistence.PersistentDataContainer} keys.
 *
 * <p>Everything the pricing engine trusts about an item lives here rather than in its
 * lore. Lore is display text: a player with an anvil, a rename, or a creative-mode
 * NBT editor can put anything in it, and the pack's own
 * {@code <random.AAtoBB>} placeholder already renders it ambiguously. PDC is written
 * only by the server, so it is the one source of truth worth trusting.
 */
public final class ItemKeys {

    /**
     * Bumped when the meaning of a stored key changes, so a future release can
     * migrate old items instead of mis-reading them.
     */
    public static final int SCHEMA_VERSION = 1;

    private final NamespacedKey weightKg;
    private final NamespacedKey mythicType;
    private final NamespacedKey species;
    private final NamespacedKey variant;
    private final NamespacedKey mutations;
    private final NamespacedKey favorite;
    private final NamespacedKey schema;

    public ItemKeys(Plugin plugin) {
        this.weightKg = new NamespacedKey(plugin, "weight_kg");
        this.mythicType = new NamespacedKey(plugin, "mythic_type");
        this.species = new NamespacedKey(plugin, "species");
        this.variant = new NamespacedKey(plugin, "variant");
        this.mutations = new NamespacedKey(plugin, "mutations");
        this.favorite = new NamespacedKey(plugin, "favorite");
        this.schema = new NamespacedKey(plugin, "schema");
    }

    /** Harvest weight in kilograms, as a DOUBLE. */
    public NamespacedKey weightKg() {
        return weightKg;
    }

    /**
     * The Mythic internal name, cached so identification survives a MythicMobs API
     * change or a pack rename.
     */
    public NamespacedKey mythicType() {
        return mythicType;
    }

    /** Species id, for placeholders and stats without a registry lookup. */
    public NamespacedKey species() {
        return species;
    }

    public NamespacedKey variant() {
        return variant;
    }

    /** Comma-separated mutation names, in the pack's canonical order. */
    public NamespacedKey mutations() {
        return mutations;
    }

    /** Present and set to 1 when the player has locked this item against bulk sale. */
    public NamespacedKey favorite() {
        return favorite;
    }

    public NamespacedKey schema() {
        return schema;
    }
}
