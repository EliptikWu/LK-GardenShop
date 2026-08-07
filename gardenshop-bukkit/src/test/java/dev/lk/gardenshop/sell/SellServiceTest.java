package dev.lk.gardenshop.sell;

import dev.lk.gardenshop.core.ConfigSnapshot;
import dev.lk.gardenshop.core.config.BandMode;
import dev.lk.gardenshop.core.config.EconomySettings;
import dev.lk.gardenshop.core.config.GuiSettings;
import dev.lk.gardenshop.core.config.ItemSettings;
import dev.lk.gardenshop.core.config.MutationStacking;
import dev.lk.gardenshop.core.config.PricingConfig;
import dev.lk.gardenshop.core.config.PricingMode;
import dev.lk.gardenshop.core.config.ResourcePackSettings;
import dev.lk.gardenshop.core.config.SellingSettings;
import dev.lk.gardenshop.core.config.ValidationReport;
import dev.lk.gardenshop.core.config.WeightBandTable;
import dev.lk.gardenshop.core.config.WeightTable;
import dev.lk.gardenshop.core.domain.Mutation;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.Variant;
import dev.lk.gardenshop.core.domain.WeightBand;
import dev.lk.gardenshop.core.domain.WeightRange;
import dev.lk.gardenshop.core.pricing.PriceCalculator;
import dev.lk.gardenshop.core.registry.DropDefinition;
import dev.lk.gardenshop.core.registry.DropRegistry;
import dev.lk.gardenshop.core.registry.MythicTypeComposer;
import dev.lk.gardenshop.economy.DepositResult;
import dev.lk.gardenshop.economy.EconomyProvider;
import dev.lk.gardenshop.item.AdapterItems;
import dev.lk.gardenshop.item.HarvestResolver;
import dev.lk.gardenshop.item.ItemKeys;
import dev.lk.gardenshop.item.ItemTagService;
import dev.lk.gardenshop.item.LoreWeightParser;
import dev.lk.gardenshop.item.MythicItems;
import dev.lk.gardenshop.item.WeightStamper;
import dev.lk.gardenshop.stats.PlayerStats;
import dev.lk.gardenshop.stats.PlayerStatsRepository;
import dev.lk.gardenshop.stats.StatsService;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the part of this plugin that can genuinely lose someone's property: taking
 * items out of an inventory and paying for them.
 *
 * <p>Reading the code and being satisfied is not enough here — the refund path only
 * runs when a third-party economy plugin misbehaves, which is exactly the situation
 * nobody tests by hand.
 */
class SellServiceTest {

    private ServerMock server;
    private SellService sell;
    private CountingEconomy economy;
    private ItemTagService tags;
    private DropRegistry registry;
    private ConfigSnapshot snapshot;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin("LKGardenShopTest");

        ItemKeys keys = new ItemKeys(plugin);
        tags = new ItemTagService(keys);
        LoreWeightParser loreParser = new LoreWeightParser();
        WeightStamper stamper = new WeightStamper(tags, loreParser);

        // No MythicMobs on a mock server, and the adapter is left off deliberately, so
        // identification runs purely off the tags this plugin writes — which is the fallback
        // path worth exercising anyway.
        MythicItems mythic = MythicItems.detect(Logger.getLogger("test"));
        AdapterItems adapter = AdapterItems.disabled(Logger.getLogger("test"));
        HarvestResolver resolver = new HarvestResolver(tags, mythic, adapter, loreParser, stamper);

        snapshot = snapshot();
        registry = snapshot.registry();
        economy = new CountingEconomy();

        StatsService stats = new StatsService(new NoOpStatsRepository(), Logger.getLogger("test"), false);
        sell = new SellService(() -> snapshot, resolver, tags,
                new PriceCalculator(), () -> economy, stats, Logger.getLogger("test"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------- fixtures

    private static Species odre() {
        return new Species("odre", "&fOdre", "NC",
                new BigDecimal("18.0"), 0.475, new WeightRange(0.30, 0.65));
    }

    private static ConfigSnapshot snapshot() {
        WeightBandTable bands = new WeightBandTable(BandMode.RATIO, true, List.of(
                new WeightBand("runt", 0.85, new BigDecimal("0.6"), "&7Runt"),
                new WeightBand("normal", 1.30, new BigDecimal("1.0"), "&fNormal"),
                new WeightBand("big", 2.20, new BigDecimal("2.2"), "&aBig"),
                new WeightBand("mythic", 9.00, new BigDecimal("30.0"), "&6Mythic")));

        Map<Variant, BigDecimal> variants = new EnumMap<>(Variant.class);
        variants.put(Variant.NORMAL, BigDecimal.ONE);
        variants.put(Variant.GOLD, new BigDecimal("2.5"));
        variants.put(Variant.RAINBOW, new BigDecimal("5.0"));

        Map<Mutation, BigDecimal> mutations = new EnumMap<>(Mutation.class);
        mutations.put(Mutation.ICE, new BigDecimal("2.4"));

        PricingConfig pricing = new PricingConfig(
                PricingMode.HYBRID, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("10000000"),
                RoundingMode.HALF_UP, 2, bands, Map.of(), variants, mutations,
                MutationStacking.ADDITIVE, 2.0, Map.of());

        DropRegistry registry = MythicTypeComposer.withDefaultPattern()
                .buildRegistry(List.of(odre()), WeightTable.empty());

        return new ConfigSnapshot("en", pricing, ItemSettings.defaults(), SellingSettings.defaults(),
                EconomySettings.defaults(), GuiSettings.defaults(), ResourcePackSettings.defaults(),
                registry, WeightTable.empty(), ValidationReport.empty(), System.currentTimeMillis());
    }

    /** A pre-stamped harvest, as it would exist after being generated in-world. */
    private ItemStack harvest(String mythicType, double weightKg, int amount) {
        DropDefinition definition = registry.find(mythicType).orElseThrow(
                () -> new IllegalArgumentException("unknown test type " + mythicType));
        ItemStack item = new ItemStack(Material.PAPER, amount);
        tags.writeIdentity(item, definition, weightKg);
        return item;
    }

    // ---------------------------------------------------------------------- tests

    @Nested
    @DisplayName("slot verification (the check that runs between pricing and removal)")
    class SlotVerification {

        private SellService.Candidate candidateFor(ItemStack item, int slot) {
            var definition = registry.find(
                    tags.readMythicType(item).orElseThrow()).orElseThrow();
            var quote = new PriceCalculator().quote(
                    definition, tags.readWeight(item).getAsDouble(), snapshot.pricing());
            return SellService.Candidate.of(slot, item, quote);
        }

        @Test
        @DisplayName("an untouched slot verifies")
        void unchangedPasses() {
            PlayerMock player = server.addPlayer();
            ItemStack item = harvest("growGardenNCDrop", 0.475, 4);
            player.getInventory().setItem(0, item);

            assertThat(SellService.stillMatches(player.getInventory(), candidateFor(item, 0))).isTrue();
        }

        @Test
        @DisplayName("a slot swapped for a DIFFERENT crop of the same size is rejected")
        void swappedIdentityFails() {
            PlayerMock player = server.addPlayer();
            ItemStack cheap = harvest("growGardenNCDrop", 0.30, 1);
            player.getInventory().setItem(0, cheap);
            SellService.Candidate quoted = candidateFor(cheap, 0);

            // Same slot, same stack size, wildly different value. Checking the amount alone
            // cannot tell these apart, which is precisely why identity is compared.
            player.getInventory().setItem(0, harvest("growGardenNCRainbowDropEnd", 3.80, 1));

            assertThat(SellService.stillMatches(player.getInventory(), quoted)).isFalse();
        }

        @Test
        @DisplayName("the same crop at a different weight is rejected too")
        void differentWeightFails() {
            PlayerMock player = server.addPlayer();
            ItemStack light = harvest("growGardenNCDrop", 0.30, 1);
            player.getInventory().setItem(0, light);
            SellService.Candidate quoted = candidateFor(light, 0);

            player.getInventory().setItem(0, harvest("growGardenNCDrop", 0.65, 1));

            assertThat(SellService.stillMatches(player.getInventory(), quoted))
                    .as("weight lives in persistent data, which isSimilar compares")
                    .isFalse();
        }

        @Test
        @DisplayName("a shrunken stack is rejected, a grown one is fine")
        void quantityIsChecked() {
            PlayerMock player = server.addPlayer();
            ItemStack item = harvest("growGardenNCDrop", 0.475, 8);
            player.getInventory().setItem(0, item);
            SellService.Candidate quoted = candidateFor(item, 0);

            ItemStack shrunk = item.clone();
            shrunk.setAmount(3);
            player.getInventory().setItem(0, shrunk);
            assertThat(SellService.stillMatches(player.getInventory(), quoted)).isFalse();

            ItemStack grown = item.clone();
            grown.setAmount(16);
            player.getInventory().setItem(0, grown);
            assertThat(SellService.stillMatches(player.getInventory(), quoted))
                    .as("more than quoted is safe: only the quoted amount is taken")
                    .isTrue();
        }

        @Test
        @DisplayName("an emptied slot is rejected")
        void emptiedSlotFails() {
            PlayerMock player = server.addPlayer();
            ItemStack item = harvest("growGardenNCDrop", 0.475, 2);
            player.getInventory().setItem(0, item);
            SellService.Candidate quoted = candidateFor(item, 0);

            player.getInventory().setItem(0, null);

            assertThat(SellService.stillMatches(player.getInventory(), quoted)).isFalse();
        }
    }

    @Test
    @DisplayName("selling the held stack pays out once and consumes the items")
    void sellHand() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(harvest("growGardenNCDrop", 0.475, 3));

        SellResult result = sell.sellHand(player);

        assertThat(result.isSold()).isTrue();
        assertThat(result.itemCount()).isEqualTo(3);
        // 18.0 base x 0.7333 band = 13.20 each.
        assertThat(result.total()).isEqualByComparingTo(new BigDecimal("39.60"));
        assertThat(economy.deposits).isEqualTo(1);
        assertThat(player.getInventory().getItemInMainHand().getType().isAir()).isTrue();
    }

    @Test
    @DisplayName("a bulk sale of many stacks makes exactly ONE deposit")
    void bulkSaleAggregatesIntoOneDeposit() {
        PlayerMock player = server.addPlayer();
        for (int slot = 0; slot < 20; slot++) {
            player.getInventory().setItem(slot, harvest("growGardenNCDrop", 0.50, 64));
        }

        SellResult result = sell.sellInventory(player);

        assertThat(result.isSold()).isTrue();
        assertThat(result.lines()).hasSize(20);
        assertThat(result.itemCount()).isEqualTo(20 * 64);
        // The whole point: 20 stacks must not become 20 economy transactions.
        assertThat(economy.deposits)
                .as("one deposit per sale, not one per stack")
                .isEqualTo(1);
        assertThat(economy.lastAmount).isEqualByComparingTo(result.total());
    }

    @Test
    @DisplayName("a failed payout leaves the inventory exactly as it was")
    void failedPayoutRefundsEverything() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, harvest("growGardenNCDrop", 0.40, 10));
        player.getInventory().setItem(1, harvest("growGardenNCGoldDrop", 1.40, 4));
        economy.failing = true;

        SellResult result = sell.sellInventory(player);

        assertThat(result.outcome()).isEqualTo(SellResult.Outcome.DEPOSIT_FAILED);
        assertThat(economy.deposits).isEqualTo(1);

        int returned = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir()) {
                returned += item.getAmount();
            }
        }
        assertThat(returned).as("every crop must come back when the payout fails").isEqualTo(14);
    }

    @Test
    @DisplayName("favorited crops survive a bulk sale")
    void bulkSaleSkipsFavorites() {
        PlayerMock player = server.addPlayer();
        ItemStack keeper = harvest("growGardenNCRainbowDrop", 3.60, 1);
        tags.toggleFavorite(keeper);
        player.getInventory().setItem(0, keeper);
        player.getInventory().setItem(1, harvest("growGardenNCDrop", 0.40, 8));

        SellResult result = sell.sellInventory(player);

        assertThat(result.isSold()).isTrue();
        assertThat(result.itemCount()).isEqualTo(8);
        assertThat(result.skippedFavorites()).isEqualTo(1);
        assertThat(tags.isFavorite(player.getInventory().getItem(0))).isTrue();
        assertSlotEmpty(player, 1);
    }

    /** Bukkit implementations disagree on null vs AIR for a cleared slot; accept either. */
    private static void assertSlotEmpty(PlayerMock player, int slot) {
        ItemStack item = player.getInventory().getItem(slot);
        assertThat(item == null || item.getType().isAir())
                .as("slot %s should be empty but held %s", slot, item)
                .isTrue();
    }

    @Test
    @DisplayName("selling the item in your hand ignores the favorite flag - you asked for it")
    void sellHandIgnoresFavorite() {
        PlayerMock player = server.addPlayer();
        ItemStack held = harvest("growGardenNCDrop", 0.475, 1);
        tags.toggleFavorite(held);
        player.getInventory().setItemInMainHand(held);

        assertThat(sell.sellHand(player).isSold()).isTrue();
    }

    @Test
    @DisplayName("a bag of nothing sellable takes no items and makes no deposit")
    void nothingToSell() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));

        SellResult result = sell.sellInventory(player);

        assertThat(result.outcome()).isEqualTo(SellResult.Outcome.NOTHING_TO_SELL);
        assertThat(economy.deposits).isZero();
        assertThat(player.getInventory().getItem(0)).isNotNull()
                .extracting(ItemStack::getType).isEqualTo(Material.DIAMOND);
    }

    @Test
    @DisplayName("an unavailable economy refuses before touching the inventory")
    void unavailableEconomyTakesNothing() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, harvest("growGardenNCDrop", 0.40, 5));
        economy.available = false;

        SellResult result = sell.sellInventory(player);

        assertThat(result.outcome()).isEqualTo(SellResult.Outcome.ECONOMY_UNAVAILABLE);
        assertThat(player.getInventory().getItem(0)).isNotNull();
        assertThat(player.getInventory().getItem(0).getAmount()).isEqualTo(5);
    }

    @Test
    @DisplayName("the weight in PDC wins over anything the lore claims")
    void pdcBeatsLore() {
        PlayerMock player = server.addPlayer();
        ItemStack item = harvest("growGardenNCDrop", 0.475, 1);
        // Forge the lore, as a player with an anvil or a creative NBT editor might.
        item.editMeta(meta -> meta.lore(List.of(net.kyori.adventure.text.Component.text("Weight: 999.00kg"))));
        player.getInventory().setItemInMainHand(item);

        SellResult result = sell.sellHand(player);

        assertThat(result.total())
                .as("a forged lore must not change the payout")
                .isEqualByComparingTo(new BigDecimal("13.20"));
    }

    @Test
    @DisplayName("appraising quotes the same figure the sale then pays")
    void appraisalMatchesSale() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(harvest("growGardenNCGoldDropIce", 1.90, 2));

        BigDecimal quoted = sell.appraiseHand(player).orElseThrow().lineTotal();
        SellResult result = sell.sellHand(player);

        assertThat(result.total()).isEqualByComparingTo(quoted);
    }

    // -------------------------------------------------------------------- doubles

    /** Counts deposits and can be told to fail, which is the whole point. */
    private static final class CountingEconomy implements EconomyProvider {

        private int deposits;
        private BigDecimal lastAmount = BigDecimal.ZERO;
        private boolean failing;
        private boolean available = true;

        @Override
        public String id() {
            return "counting";
        }

        @Override
        public String description() {
            return "test double";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public DepositResult deposit(OfflinePlayer player, BigDecimal amount) {
            deposits++;
            lastAmount = amount;
            return failing ? DepositResult.failure("test failure") : DepositResult.success(amount);
        }

        @Override
        public String format(BigDecimal amount) {
            return amount.toPlainString();
        }

        @Override
        public String currencyName() {
            return "coins";
        }
    }

    private static final class NoOpStatsRepository implements PlayerStatsRepository {

        @Override
        public Map<UUID, PlayerStats> loadAll() {
            return Map.of();
        }

        @Override
        public void saveAll(Collection<PlayerStats> stats) {
            // Statistics are irrelevant to what these tests assert.
        }
    }
}
