package dev.lk.gardenshop.item;

import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.items.ItemExecutor;
import io.lumine.mythic.core.items.MythicItem;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * The only class that touches the MythicMobs API.
 *
 * <p>Isolating it means a Mythic upgrade that moves or renames something degrades to
 * "identification falls back to our own tags" instead of a
 * {@link NoClassDefFoundError} taking the plugin down mid-tick. Every call is
 * wrapped, and the first failure downgrades this instance permanently rather than
 * spamming the console once per inventory slot.
 */
public final class MythicItems {

    private final Logger logger;
    private final AtomicBoolean available;
    private final AtomicBoolean warned = new AtomicBoolean(false);

    private MythicItems(Logger logger, boolean available) {
        this.logger = logger;
        this.available = new AtomicBoolean(available);
    }

    public static MythicItems detect(Logger logger) {
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") == null) {
            logger.warning("MythicMobs is not installed — harvest items can only be identified "
                    + "from tags this plugin wrote itself.");
            return new MythicItems(logger, false);
        }
        try {
            // Probe now, while we can still report it cleanly, rather than on the
            // first sell attempt.
            itemExecutor();
            return new MythicItems(logger, true);
        } catch (Throwable t) {
            logger.warning("MythicMobs is installed but its item API could not be reached ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + "). Falling back to this plugin's own item tags.");
            return new MythicItems(logger, false);
        }
    }

    public boolean isAvailable() {
        return available.get();
    }

    /** The Mythic internal name of an item, or empty if it is not a Mythic item. */
    public Optional<String> typeOf(ItemStack item) {
        if (!available.get() || item == null || item.getType().isAir()) {
            return Optional.empty();
        }
        try {
            ItemExecutor items = itemExecutor();
            if (!items.isMythicItem(item)) {
                return Optional.empty();
            }
            String type = items.getMythicTypeFromItem(item);
            return type == null || type.isBlank() ? Optional.empty() : Optional.of(type);
        } catch (Throwable t) {
            degrade(t);
            return Optional.empty();
        }
    }

    /**
     * The pack's own icon for a drop type, ready to drop into a menu.
     *
     * <p>Mythic builds this, so a menu can show the actual crop — right model, right art — instead of
     * a stand-in material, and it keeps being right when the pack changes. Deliberately not a table
     * of numbers copied out of {@code growGardenItems.yml}: that file declares 180 drops but only 144
     * distinct {@code Model} ids, because each crop's plain, Nether and End drops share one. A
     * hand-kept table would be both large and ambiguous.
     *
     * <p><b>Cloned before it leaves.</b> {@code getCachedMenuItem()} hands out Mythic's own cached
     * instance; writing a name and lore onto that would corrupt every later use of it, Mythic's
     * included.
     *
     * @return empty when Mythic is absent, does not know the type, or has nothing built yet — in
     *         which case the caller shows its vanilla stand-in
     */
    public Optional<ItemStack> menuItem(String mythicType) {
        if (!available.get() || mythicType == null || mythicType.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<MythicItem> item = itemExecutor().getItem(mythicType);
            if (item.isEmpty()) {
                return Optional.empty();
            }
            // The menu item is a cache Mythic fills lazily, so build the item outright rather than
            // reporting "no icon" for a type that plainly has one.
            ItemStack icon = item.get().getCachedMenuItem();
            if (icon == null) {
                icon = BukkitAdapter.adapt(item.get().generateItemStack(1));
            }
            return icon == null || icon.getType().isAir()
                    ? Optional.empty()
                    : Optional.of(icon.clone());
        } catch (Throwable t) {
            degrade(t);
            return Optional.empty();
        }
    }

    /** Every item name Mythic knows about, for {@code /gs info} to cross-check against. */
    public Collection<String> knownTypes() {
        if (!available.get()) {
            return List.of();
        }
        try {
            return List.copyOf(itemExecutor().getItemNames());
        } catch (Throwable t) {
            degrade(t);
            return List.of();
        }
    }

    private static ItemExecutor itemExecutor() {
        // MythicBukkit narrows getItemManager() to the concrete executor, which is where
        // isMythicItem and getMythicTypeFromItem live -- the ItemManager interface it
        // overrides does not declare them.
        return MythicBukkit.inst().getItemManager();
    }

    private void degrade(Throwable cause) {
        available.set(false);
        if (warned.compareAndSet(false, true)) {
            logger.warning("The MythicMobs item API failed (" + cause.getClass().getSimpleName() + ": "
                    + cause.getMessage() + "). Identification now relies on this plugin's own tags only. "
                    + "This is logged once.");
        }
    }
}
