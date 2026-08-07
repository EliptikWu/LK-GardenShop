package dev.lk.gardenshop.item;

import dev.lk.itembridge.ItemBridge;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * The only class that touches the item-identity library.
 *
 * <p>{@code ItemBridge} gives every custom item an id of the form {@code plugin:id} across
 * MythicMobs, Crucible, ItemsAdder, Nexo, Oraxen and CraftEngine. It is what Vault is to economies,
 * for item identity — and it is ours, shaded in, so nothing here depends on a third party's release
 * schedule or licence.
 *
 * <p><b>It is a third resort, not a replacement.</b> {@link HarvestResolver} still identifies a
 * harvest from our own tag first and from MythicMobs second; this only speaks up when both have
 * missed. That ordering is deliberate: the tag-then-Mythic path is the one with the tests behind it,
 * and demoting it in favour of a general-purpose lookup would be trading a known-good path for a
 * shorter one. What the bridge adds is the two cases the old path cannot cover — an item whose
 * Mythic API call has started failing, and a crop that comes from some other plugin entirely.
 *
 * <p>Every call is wrapped and the first failure downgrades this instance permanently, the same
 * pattern and for the same reason as {@link MythicItems}: a library fault must cost us a fallback,
 * not a sale.
 */
public final class AdapterItems {

    private final Logger logger;
    private final AtomicBoolean available;
    private final AtomicBoolean warned = new AtomicBoolean(false);
    /** Non-null only in tests. See {@link #stub}. */
    private final List<String> stubIds;

    private AdapterItems(Logger logger, boolean available, List<String> stubIds) {
        this.logger = logger;
        this.available = new AtomicBoolean(available);
        this.stubIds = stubIds;
    }

    /** An instance that always misses, for tests and for when initialisation failed. */
    public static AdapterItems disabled(Logger logger) {
        return new AdapterItems(logger, false, null);
    }

    /**
     * Test seam: reports {@code ids} for every item, without the library involved.
     *
     * <p>Here because the identification order in {@link HarvestResolver} decides which crop's price
     * a player is paid, and that order has to be assertable without six item plugins installed.
     */
    static AdapterItems stub(List<String> ids) {
        return new AdapterItems(Logger.getLogger("stub"), true, List.copyOf(ids));
    }

    /**
     * Detects which item plugins are installed. Call once, from {@code onEnable}.
     *
     * <p>Not from a reload: detection is a class lookup per source and the answer cannot change
     * while the server runs. It has to run after the item plugins have enabled, which is what the
     * {@code softdepend} list in {@code plugin.yml} is for.
     */
    public static AdapterItems init(Plugin plugin) {
        Logger logger = plugin.getLogger();
        try {
            Set<String> sources = ItemBridge.sourceNames();
            logger.info(() -> "Item sources found: " + String.join(", ", sources));
            return new AdapterItems(logger, true, null);
        } catch (Throwable t) {
            // Wide on purpose. A bundled library that fails to start is a cosmetic loss here, and
            // there is no version of this worth taking the plugin down for.
            logger.warning("The item adapter could not start (" + t.getClass().getSimpleName() + ": "
                    + t.getMessage() + "). Harvests will be identified by tag and by MythicMobs only, "
                    + "which is what the plugin did before the adapter existed.");
            return new AdapterItems(logger, false, null);
        }
    }

    public boolean isAvailable() {
        return available.get();
    }

    /** Which item plugins answered, for the startup banner. */
    public Set<String> sources() {
        if (!available.get() || stubIds != null) {
            return Set.of();
        }
        try {
            return ItemBridge.sourceNames();
        } catch (Throwable t) {
            degrade(t);
            return Set.of();
        }
    }

    /**
     * Every id this item answers to, across all installed item plugins.
     *
     * <p>The plural call, not the singular one. Our crops are Mythic items built on {@code Id: paper},
     * so the vanilla source recognises them too — and a single-id call could hand back {@code mc:paper}
     * and lose the Mythic name that actually identifies the crop. Asking for all of them makes the
     * answer independent of source ordering.
     *
     * @return possibly empty, never null
     */
    public List<String> idsOf(ItemStack item) {
        if (!available.get() || item == null || item.getType().isAir()) {
            return List.of();
        }
        if (stubIds != null) {
            return stubIds;
        }
        try {
            return ItemBridge.idsOf(item);
        } catch (Throwable t) {
            degrade(t);
            return List.of();
        }
    }

    /**
     * Every id every installed item plugin knows about.
     *
     * <p>For {@code /gs adapter list}, so an owner can find the exact id of an item instead of
     * guessing at it. Can be several thousand entries on a server with a big ItemsAdder pack, which
     * is the caller's problem to page through.
     */
    public List<String> allIds() {
        if (!available.get() || stubIds != null) {
            return List.of();
        }
        try {
            return List.copyOf(ItemBridge.allIds());
        } catch (Throwable t) {
            degrade(t);
            return List.of();
        }
    }

    /** Whether any installed plugin recognises this id. Used to reject typos at bind time. */
    public boolean exists(String adapterId) {
        if (!available.get() || adapterId == null || adapterId.isBlank()) {
            return false;
        }
        try {
            return ItemBridge.exists(adapterId);
        } catch (Throwable t) {
            degrade(t);
            return false;
        }
    }

    private void degrade(Throwable cause) {
        available.set(false);
        if (warned.compareAndSet(false, true)) {
            logger.warning("The item adapter failed (" + cause.getClass().getSimpleName() + ": "
                    + cause.getMessage() + "). Identification falls back to tags and MythicMobs. "
                    + "This is logged once.");
        }
    }
}
