package dev.lk.gardenshop.sell;

import dev.lk.gardenshop.core.ConfigSnapshot;
import dev.lk.gardenshop.core.domain.PriceQuote;
import dev.lk.gardenshop.core.pricing.PriceCalculator;
import dev.lk.gardenshop.economy.DepositResult;
import dev.lk.gardenshop.economy.EconomyProvider;
import dev.lk.gardenshop.item.HarvestResolver;
import dev.lk.gardenshop.item.ItemTagService;
import dev.lk.gardenshop.item.PackIntegrity;
import dev.lk.gardenshop.stats.StatsService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Appraises and sells harvests.
 *
 * <h2>The ordering that matters</h2>
 * A sale touches two systems that can each fail — an inventory and a third-party
 * economy plugin — and getting the order wrong is how sell plugins duplicate money or
 * eat crops. The sequence here is deliberate:
 *
 * <ol>
 *   <li>price everything and total it up, touching nothing;</li>
 *   <li>re-verify every slot still holds what was priced, and bail out whole if not;</li>
 *   <li>remove the items;</li>
 *   <li>make <b>one</b> aggregated deposit;</li>
 *   <li>if that deposit fails, put the items back.</li>
 * </ol>
 *
 * Removing before depositing is the right way round: taking items out of an inventory
 * already verified on the main thread cannot realistically fail, whereas a deposit
 * can be rejected by any of a dozen economy plugins. And because steps 3–5 never yield
 * the tick, there is no window in which the player holds neither the crops nor the money.
 *
 * <p>One deposit per sale rather than one per stack is not just tidiness: a 36-slot
 * bulk sale would otherwise fire 36 transactions through EssentialsX, each with its own
 * file write and event chain.
 */
public final class SellService {

    /**
     * The live configuration, fetched per operation rather than captured, so a
     * {@code /gs reload} takes effect immediately and this class holds no state that
     * could go stale.
     */
    private final Supplier<ConfigSnapshot> configs;
    private final HarvestResolver resolver;
    private final ItemTagService tags;
    private final PriceCalculator calculator;
    private final Supplier<EconomyProvider> economy;
    private final StatsService stats;
    private final PackIntegrity packIntegrity;
    private final Logger logger;

    /** Last successful sale per player, for the cooldown. */
    private final Map<UUID, Long> lastSale = new ConcurrentHashMap<>();

    public SellService(Supplier<ConfigSnapshot> configs, HarvestResolver resolver, ItemTagService tags,
                       PriceCalculator calculator, Supplier<EconomyProvider> economy, StatsService stats,
                       PackIntegrity packIntegrity, Logger logger) {
        this.configs = configs;
        this.resolver = resolver;
        this.tags = tags;
        this.calculator = calculator;
        this.economy = economy;
        this.stats = stats;
        this.packIntegrity = packIntegrity;
        this.logger = logger;
    }

    /**
     * Whether the crop pack is missing and the owner asked us to refuse in that case.
     *
     * <p>Reads a stored report rather than re-checking: verification walks every declared type
     * against Mythic's registry, which is fine once at enable and wrong once per sale.
     */
    public boolean blockedByMissingPack() {
        return configs.get().items().requireCropPack() && !packIntegrity.latest().satisfied();
    }

    public EconomyProvider provider() {
        return economy.get();
    }

    // ------------------------------------------------------------------ appraisal

    /**
     * Prices the player's held stack without selling it.
     *
     * <p>Note this still pins the item's weight if it did not have one — otherwise the
     * quote could differ from the sale that follows, which would make the appraisal a
     * lie. See {@link HarvestResolver}.
     */
    public Optional<SellLine> appraiseHand(Player player) {
        ConfigSnapshot snapshot = configs.get();
        ItemStack hand = player.getInventory().getItemInMainHand();

        return resolver.resolve(hand, snapshot).map(harvest -> {
            PriceQuote quote = calculator.quote(harvest.toDrop(), snapshot.pricing());
            return SellLine.of(player.getInventory().getHeldItemSlot(), hand.getAmount(), quote);
        });
    }

    /** Total value of every sellable item in the inventory, favorites excluded. */
    public List<SellLine> appraiseInventory(Player player) {
        return collect(player, configs.get()).candidates().stream()
                .map(Candidate::line)
                .toList();
    }

    /**
     * How many harvest items a bulk sale would leave behind because they are favorited.
     *
     * <p>Read-only, unlike appraisal: it never pins a weight, because a favorited crop is
     * one the player is explicitly not selling.
     */
    public int countFavoritedHarvests(Player player) {
        ConfigSnapshot snapshot = configs.get();
        int favorited = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir()
                    && tags.isFavorite(item)
                    && resolver.isHarvest(item, snapshot)) {
                favorited += item.getAmount();
            }
        }
        return favorited;
    }

    // -------------------------------------------------------------------- selling

    public SellResult sellHand(Player player) {
        if (blockedByMissingPack()) {
            return SellResult.cropPackMissing(packIntegrity.latest().summary());
        }
        ConfigSnapshot snapshot = configs.get();
        ItemStack hand = player.getInventory().getItemInMainHand();
        int slot = player.getInventory().getHeldItemSlot();

        Optional<HarvestResolver.Harvest> harvest = resolver.resolve(hand, snapshot);
        if (harvest.isEmpty()) {
            return SellResult.nothingToSell(0);
        }
        // Selling what you are explicitly holding ignores the favorite flag: the
        // player named the item, so the safety net is not needed and would be baffling.
        PriceQuote quote = calculator.quote(harvest.get().toDrop(), snapshot.pricing());
        Candidate candidate = Candidate.of(slot, hand, quote);

        return execute(player, snapshot, List.of(candidate), 0, 0);
    }

    public SellResult sellInventory(Player player) {
        if (blockedByMissingPack()) {
            return SellResult.cropPackMissing(packIntegrity.latest().summary());
        }
        ConfigSnapshot snapshot = configs.get();
        Collected collected = collect(player, snapshot);

        if (collected.candidates().isEmpty()) {
            return SellResult.nothingToSell(collected.skippedFavorites());
        }
        return execute(player, snapshot, collected.candidates(),
                collected.skippedFavorites(), collected.leftOverLimit());
    }

    // ------------------------------------------------------------------ internals

    /**
     * A priced stack together with a snapshot of the item it was priced from.
     *
     * <p>The snapshot is what makes step 2 of the sale meaningful. Checking only the stack
     * size would let a sale that was quoted against one item be settled against whatever
     * happens to be in that slot at removal time — the two are indistinguishable by
     * amount alone.
     */
    record Candidate(SellLine line, ItemStack expected) {

        static Candidate of(int slot, ItemStack item, PriceQuote quote) {
            return new Candidate(SellLine.of(slot, item.getAmount(), quote), item.clone());
        }

        int slot() {
            return line.slot();
        }

        int amount() {
            return line.amount();
        }
    }

    /**
     * Whether a slot still holds the item this candidate was priced from, in at least the
     * quantity quoted.
     *
     * <p>{@link ItemStack#isSimilar} compares everything except stack size — material,
     * display name, lore and the persistent data holding the weight — which is exactly the
     * question being asked. Checking only the amount would let a sale quoted against one
     * crop be settled against a different one of the same size.
     *
     * <p>Package-private so it can be tested directly: the window it guards is a single
     * tick wide and cannot be reached from outside this class.
     */
    static boolean stillMatches(PlayerInventory inventory, Candidate candidate) {
        ItemStack current = inventory.getItem(candidate.slot());
        return current != null
                && !current.getType().isAir()
                && current.getAmount() >= candidate.amount()
                && current.isSimilar(candidate.expected());
    }

    private record Collected(List<Candidate> candidates, int skippedFavorites, int leftOverLimit) {
    }

    /** Walks the storage slots, pricing what is sellable and honouring the favorite flag. */
    private Collected collect(Player player, ConfigSnapshot snapshot) {
        PlayerInventory inventory = player.getInventory();
        boolean skipFavorites = snapshot.selling().skipFavorites();
        int limit = snapshot.selling().maxItemsPerBulkSale();

        List<Candidate> candidates = new ArrayList<>();
        int skippedFavorites = 0;
        int taken = 0;
        int leftOverLimit = 0;

        // getStorageContents() covers the 36 main slots and excludes armour and the
        // off-hand, which is what a player means by "my inventory". Only its length is
        // used: every read below goes through getItem(slot) so there is one source of
        // truth, and so resolve() can pin a weight onto the live stack rather than onto
        // the copy getStorageContents() hands back.
        int storageSize = inventory.getStorageContents().length;
        for (int slot = 0; slot < storageSize; slot++) {
            ItemStack live = inventory.getItem(slot);
            if (live == null || live.getType().isAir()) {
                continue;
            }
            if (!resolver.isHarvest(live, snapshot)) {
                continue;
            }
            if (skipFavorites && tags.isFavorite(live)) {
                skippedFavorites += live.getAmount();
                continue;
            }
            if (taken + live.getAmount() > limit) {
                leftOverLimit += live.getAmount();
                continue;
            }

            Optional<HarvestResolver.Harvest> harvest = resolver.resolve(live, snapshot);
            if (harvest.isEmpty()) {
                continue;
            }

            PriceQuote quote = calculator.quote(harvest.get().toDrop(), snapshot.pricing());
            // Cloned AFTER resolve(), so the snapshot includes any weight just pinned and
            // therefore still matches the live stack at verification time.
            candidates.add(Candidate.of(slot, live, quote));
            taken += live.getAmount();
        }
        return new Collected(candidates, skippedFavorites, leftOverLimit);
    }

    private SellResult execute(Player player, ConfigSnapshot snapshot, List<Candidate> candidates,
                               int skippedFavorites, int leftOverLimit) {
        EconomyProvider provider = economy.get();
        if (!provider.isAvailable()) {
            return SellResult.failure(SellResult.Outcome.ECONOMY_UNAVAILABLE, provider.description());
        }

        long remainingCooldown = remainingCooldownSeconds(player, snapshot);
        if (remainingCooldown > 0) {
            return SellResult.failure(SellResult.Outcome.ON_COOLDOWN, Long.toString(remainingCooldown));
        }

        List<SellLine> lines = new ArrayList<>(candidates.size());
        BigDecimal total = BigDecimal.ZERO;
        int itemCount = 0;
        for (Candidate candidate : candidates) {
            lines.add(candidate.line());
            total = total.add(candidate.line().lineTotal());
            itemCount += candidate.amount();
        }

        // A safety valve against a mis-typed multiplier minting a fortune. Clamping and
        // saying so beats refusing the sale outright, which would leave a player unable
        // to empty their bag at all.
        boolean capped = false;
        BigDecimal cap = snapshot.pricing().maxPayoutPerSale();
        if (total.compareTo(cap) > 0) {
            logger.warning("Capped a sale by " + player.getName() + " from " + total.toPlainString()
                    + " to max-payout-per-sale (" + cap.toPlainString() + "). Review pricing.yml.");
            total = cap;
            capped = true;
        }

        PlayerInventory inventory = player.getInventory();

        // Step 2: verify identity AND quantity before touching anything, so an aborted
        // sale leaves no trace.
        for (Candidate candidate : candidates) {
            if (!stillMatches(inventory, candidate)) {
                return SellResult.failure(SellResult.Outcome.INVENTORY_CHANGED,
                        "slot " + candidate.slot());
            }
        }

        // Step 3: remove, keeping clones so step 5 can hand them back byte-identical.
        List<ItemStack> removed = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            ItemStack current = inventory.getItem(candidate.slot());
            if (current == null) {
                continue;
            }
            ItemStack taken = current.clone();
            taken.setAmount(candidate.amount());
            removed.add(taken);

            if (current.getAmount() == candidate.amount()) {
                inventory.setItem(candidate.slot(), null);
            } else {
                current.setAmount(current.getAmount() - candidate.amount());
                inventory.setItem(candidate.slot(), current);
            }
        }

        // Step 4: exactly one deposit, regardless of how many stacks were sold.
        DepositResult deposit = provider.deposit(player, total);
        if (!deposit.success()) {
            // Step 5.
            restore(player, removed);
            logger.warning("Payout to " + player.getName() + " failed (" + deposit.error()
                    + "); their items were returned.");
            return SellResult.failure(SellResult.Outcome.DEPOSIT_FAILED, deposit.error());
        }

        lastSale.put(player.getUniqueId(), System.currentTimeMillis());
        recordStats(player, lines, total, itemCount);

        return SellResult.sold(lines, total, itemCount, skippedFavorites, leftOverLimit, capped);
    }

    /** Puts items back, dropping at the player's feet only if the inventory truly cannot hold them. */
    private void restore(Player player, List<ItemStack> removed) {
        if (removed.isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> leftover =
                player.getInventory().addItem(removed.toArray(new ItemStack[0]));
        for (ItemStack overflow : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    private void recordStats(Player player, List<SellLine> lines, BigDecimal total, int itemCount) {
        Map<String, Double> heaviest = new HashMap<>();
        for (SellLine line : lines) {
            heaviest.merge(line.speciesId(), line.weightKg(), Math::max);
        }
        stats.recordSale(player.getUniqueId(), total, itemCount, heaviest);
    }

    /** @return seconds still to wait, or 0 when the player may sell */
    public long remainingCooldownSeconds(Player player, ConfigSnapshot snapshot) {
        int cooldown = snapshot.selling().cooldownSeconds();
        if (cooldown <= 0) {
            return 0;
        }
        Long last = lastSale.get(player.getUniqueId());
        if (last == null) {
            return 0;
        }
        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        return Math.max(0, cooldown - elapsed);
    }

    /** Drops the cooldown entry for a player who has left, so the map cannot grow forever. */
    public void forget(UUID uuid) {
        lastSale.remove(uuid);
    }

    /**
     * Guard for callers that could plausibly run off-thread. Inventory access and Vault
     * deposits both require the main thread.
     */
    public boolean onMainThread() {
        return Bukkit.isPrimaryThread();
    }
}
