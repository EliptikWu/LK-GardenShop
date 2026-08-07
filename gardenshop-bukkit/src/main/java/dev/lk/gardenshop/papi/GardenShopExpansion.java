package dev.lk.gardenshop.papi;

import dev.lk.gardenshop.config.ConfigService;
import dev.lk.gardenshop.economy.EconomyProvider;
import dev.lk.gardenshop.item.WeightStamper;
import dev.lk.gardenshop.sell.SellLine;
import dev.lk.gardenshop.sell.SellService;
import dev.lk.gardenshop.stats.PlayerStats;
import dev.lk.gardenshop.stats.StatsService;
import dev.lk.gardenshop.util.Text;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code %gardenshop_...%} placeholders, for scoreboards, holograms and action bars.
 *
 * <h2>Placeholders</h2>
 * <pre>
 * hand_value        formatted value of the held stack
 * hand_value_raw    same, unformatted, for arithmetic in other plugins
 * hand_unit         value of a single item from the held stack
 * hand_weight       weight in kg, 2 decimals
 * hand_band         weight band label (colour codes stripped)
 * hand_species      species id
 * hand_variant      NORMAL / GOLD / RAINBOW
 * hand_mutations    comma-separated, empty when unmutated
 * hand_favorite     true / false
 * inventory_value   formatted value of everything sellable, favorites excluded
 * inventory_items   how many items that covers
 * total_earned      lifetime earnings, formatted
 * items_sold        lifetime items sold
 * sales_count       lifetime number of sales
 * record_&lt;crop&gt;     heaviest ever sold of that crop, in kg
 * economy_provider  what payouts go through
 * currency          currency name
 * pricing_mode      HYBRID / BANDS / FORMULA
 * types / crops     size of the loaded registry
 * </pre>
 *
 * <p>Anything reading the held item or the inventory returns an empty string for an
 * offline player rather than a zero, so a scoreboard shows a blank instead of
 * asserting the player has nothing.
 *
 * <h2>Two things this class has to be careful about</h2>
 * <b>Threading.</b> Placeholder requests do not always arrive on the main thread — several
 * scoreboard and hologram plugins resolve them asynchronously. Appraising an item pins its
 * weight, which writes to the {@link org.bukkit.inventory.ItemStack}, and writing to a live
 * inventory off-thread corrupts it. So every item-reading placeholder refuses to run
 * anywhere but the main thread and returns an empty string instead.
 *
 * <p><b>Cost.</b> A scoreboard can ask for the same placeholder every tick, per player.
 * Appraising a whole inventory walks 36 slots and clones an {@code ItemMeta} for each, so
 * inventory totals are cached for a moment rather than recomputed 20 times a second.
 */
public final class GardenShopExpansion extends PlaceholderExpansion {

    private final Plugin plugin;
    private final ConfigService configs;
    private final SellService sell;
    private final StatsService stats;

    public GardenShopExpansion(Plugin plugin, ConfigService configs, SellService sell, StatsService stats) {
        this.plugin = plugin;
        this.configs = configs;
        this.sell = sell;
        this.stats = stats;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "gardenshop";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** Survives {@code /papi reload} — without this the expansion silently disappears. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (!configs.isLoaded()) {
            return "";
        }
        String key = params.toLowerCase(Locale.ROOT);

        // Server-wide values first: these need no player at all.
        switch (key) {
            case "economy_provider":
                return sell.provider().description();
            case "currency":
                return sell.provider().currencyName();
            case "pricing_mode":
                return configs.snapshot().pricing().mode().name();
            case "types":
                return Integer.toString(configs.snapshot().registry().size());
            case "crops":
                return Integer.toString(configs.snapshot().species().size());
            default:
                break;
        }

        if (player == null) {
            return "";
        }

        if (key.startsWith("record_")) {
            String speciesId = key.substring("record_".length());
            return WeightStamper.formatWeight(statsOf(player).recordWeight(speciesId));
        }

        switch (key) {
            case "total_earned":
                return sell.provider().format(statsOf(player).totalEarned());
            case "total_earned_raw":
                return statsOf(player).totalEarned().toPlainString();
            case "items_sold":
                return Long.toString(statsOf(player).itemsSold());
            case "sales_count":
                return Long.toString(statsOf(player).salesCount());
            default:
                break;
        }

        // Everything below needs a live inventory.
        if (!(player instanceof Player online)) {
            return "";
        }
        // Appraising pins an item's weight, which writes to the ItemStack. Doing that from
        // an async placeholder request would corrupt a live inventory, so refuse rather
        // than risk it — a blank on a scoreboard is a far better outcome.
        if (!Bukkit.isPrimaryThread()) {
            return "";
        }
        return onlineRequest(online, key);
    }

    private String onlineRequest(Player online, String key) {
        EconomyProvider provider = sell.provider();

        if (key.startsWith("inventory_")) {
            InventoryTotals totals = cachedTotals(online);
            return switch (key) {
                case "inventory_value" -> provider.format(totals.total());
                case "inventory_value_raw" -> totals.total().toPlainString();
                case "inventory_items" -> Integer.toString(totals.items());
                case "inventory_stacks" -> Integer.toString(totals.stacks());
                default -> "";
            };
        }

        if (!key.startsWith("hand_")) {
            return "";
        }

        Optional<SellLine> hand = sell.appraiseHand(online);
        if (hand.isEmpty()) {
            return "";
        }
        SellLine line = hand.get();
        var drop = line.quote().drop();

        return switch (key) {
            case "hand_value" -> provider.format(line.lineTotal());
            case "hand_value_raw" -> line.lineTotal().toPlainString();
            case "hand_unit" -> provider.format(line.quote().unitPrice());
            case "hand_unit_raw" -> line.quote().unitPrice().toPlainString();
            case "hand_weight" -> WeightStamper.formatWeight(drop.weightKg());
            // Stripped, because a scoreboard has its own colours and legacy codes
            // would leak through as literal text in some contexts.
            case "hand_band" -> Text.stripLegacy(line.quote().bandLabel());
            case "hand_band_id" -> line.quote().bandId();
            case "hand_species" -> drop.species().id();
            case "hand_crop" -> Text.stripLegacy(drop.species().displayName());
            case "hand_variant" -> drop.variant().name();
            case "hand_mutations" -> drop.mutations().stream()
                    .map(Enum::name)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            case "hand_amount" -> Integer.toString(line.amount());
            default -> "";
        };
    }

    private PlayerStats statsOf(OfflinePlayer player) {
        return stats.of(player.getUniqueId());
    }

    // ---------------------------------------------------------- inventory total cache

    private record InventoryTotals(BigDecimal total, int items, int stacks, long computedAtNanos) {

        boolean isFresh(long nowNanos) {
            return nowNanos - computedAtNanos < CACHE_TTL_NANOS;
        }
    }

    /**
     * Long enough to collapse a per-tick scoreboard refresh into one appraisal, short
     * enough that a player selling a crop sees the number move.
     */
    private static final long CACHE_TTL_NANOS = 500_000_000L;

    private final Map<UUID, InventoryTotals> totalsCache = new HashMap<>();

    private InventoryTotals cachedTotals(Player online) {
        long now = System.nanoTime();
        InventoryTotals cached = totalsCache.get(online.getUniqueId());
        if (cached != null && cached.isFresh(now)) {
            return cached;
        }

        List<SellLine> lines = sell.appraiseInventory(online);
        BigDecimal total = BigDecimal.ZERO;
        int items = 0;
        for (SellLine line : lines) {
            total = total.add(line.lineTotal());
            items += line.amount();
        }

        InventoryTotals fresh = new InventoryTotals(total, items, lines.size(), now);
        // Only ever touched on the main thread, guarded by the check in onRequest, so a
        // plain HashMap is correct here. Entries for players who left are dropped
        // opportunistically rather than with a listener.
        if (totalsCache.size() > 256) {
            totalsCache.entrySet().removeIf(entry -> !entry.getValue().isFresh(now));
        }
        totalsCache.put(online.getUniqueId(), fresh);
        return fresh;
    }
}
