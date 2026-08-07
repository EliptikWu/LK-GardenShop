package dev.lk.gardenshop.item;

import dev.lk.gardenshop.core.ConfigSnapshot;
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
import dev.lk.gardenshop.core.config.BandMode;
import dev.lk.gardenshop.core.config.WeightTable;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.Variant;
import dev.lk.gardenshop.core.domain.WeightBand;
import dev.lk.gardenshop.core.domain.WeightRange;
import dev.lk.gardenshop.core.registry.AdapterSources;
import dev.lk.gardenshop.core.registry.DropRegistry;
import dev.lk.gardenshop.core.registry.MythicTypeComposer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The adapter as the third identification route, and its behaviour when it is not there.
 *
 * <p>What matters is the <em>order</em>. This route decides which crop a player is paid for, so it
 * must never win over a source that knows better, and it must never take the plugin down when the
 * bundled library has a bad day.
 */
class AdapterIdentificationTest {

    private static final String PLAIN_ODRE = "growGardenNCDrop";
    private static final String GOLD_ODRE = "growGardenNCGoldDrop";

    private Plugin plugin;
    private ItemTagService tags;
    private WeightStamper stamper;
    private LoreWeightParser loreParser;
    private ConfigSnapshot snapshot;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("LKGardenShopTest");
        tags = new ItemTagService(new ItemKeys(plugin));
        loreParser = new LoreWeightParser();
        stamper = new WeightStamper(tags, loreParser);
        snapshot = snapshot(odre("ia:mygarden:tomato"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------- fixtures

    private static Species odre(String... extraIds) {
        return new Species("odre", "&fOdre", "NC", new BigDecimal("18.0"), 0.475,
                new WeightRange(0.30, 0.65), List.of(extraIds));
    }

    private static ConfigSnapshot snapshot(Species crop) {
        WeightBandTable bands = new WeightBandTable(BandMode.RATIO, true, List.of(
                new WeightBand("normal", 1.30, BigDecimal.ONE, "&fNormal"),
                new WeightBand("big", 9.00, new BigDecimal("2.2"), "&aBig")));
        PricingConfig pricing = new PricingConfig(
                PricingMode.HYBRID, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("10000000"),
                RoundingMode.HALF_UP, 2, bands, Map.of(), Map.of(), Map.of(),
                MutationStacking.ADDITIVE, 2.0, Map.of());
        DropRegistry registry = MythicTypeComposer.withDefaultPattern()
                .buildRegistry(List.of(crop), WeightTable.empty());

        return new ConfigSnapshot("en", pricing, ItemSettings.defaults(), SellingSettings.defaults(),
                EconomySettings.defaults(), GuiSettings.defaults(), ResourcePackSettings.defaults(),
                registry, WeightTable.empty(), ValidationReport.empty(), System.currentTimeMillis());
    }

    private HarvestResolver resolverSeeing(String... adapterIds) {
        return new HarvestResolver(tags, MythicItems.detect(Logger.getLogger("test")),
                AdapterItems.stub(List.of(adapterIds)), loreParser, stamper);
    }

    // ------------------------------------------------------------------- the route

    @Test
    @DisplayName("an untagged item is identified from its adapter id")
    void adapterIdentifiesAnUntaggedItem() {
        // No tag, no MythicMobs on a mock server: without the adapter this item is unsellable.
        ItemStack item = new ItemStack(Material.PAPER);

        assertThat(resolverSeeing("mc:paper", "mythic:" + GOLD_ODRE).resolve(item, snapshot))
                .isPresent()
                .get()
                .satisfies(harvest -> {
                    assertThat(harvest.definition().variant()).isEqualTo(Variant.GOLD);
                    assertThat(harvest.newlyPinned())
                            .as("an item identified this way still gets a weight pinned to it")
                            .isTrue();
                });
    }

    @Test
    @DisplayName("every id is tried, so a useless one first does not hide the real one")
    void allIdsAreTried() {
        // Our crops are Mythic items built on paper, so 'mc:paper' is a true answer about them and
        // may well come back first. Stopping at the first id would make identification depend on
        // the library's internal ordering.
        ItemStack item = new ItemStack(Material.PAPER);

        assertThat(resolverSeeing("mc:paper").resolve(item, snapshot))
                .as("the vanilla id alone must not resolve to a crop")
                .isEmpty();
        assertThat(resolverSeeing("mc:paper", "ia:mygarden:tomato").resolve(item, snapshot))
                .isPresent();
    }

    @Test
    @DisplayName("our own tag beats the adapter, even when the adapter names another crop")
    void tagWinsOverAdapter() {
        ItemStack item = new ItemStack(Material.PAPER);
        tags.writeIdentity(item, snapshot.registry().find(PLAIN_ODRE).orElseThrow(), 0.5);

        // The tag is the only source that cannot be wrong about an item we wrote it on. If the
        // adapter could override it, a mis-declared extra-id would re-price existing harvests.
        assertThat(resolverSeeing("mythic:" + GOLD_ODRE).resolve(item, snapshot))
                .isPresent()
                .get()
                .satisfies(harvest -> {
                    assertThat(harvest.definition().mythicType()).isEqualTo(PLAIN_ODRE);
                    assertThat(harvest.weightKg()).isEqualTo(0.5);
                    assertThat(harvest.newlyPinned()).isFalse();
                });
    }

    @Test
    @DisplayName("an id that matches nothing leaves the item alone")
    void unknownIdsResolveToNothing() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);

        assertThat(resolverSeeing("mc:diamond_sword", "ia:other:thing").resolve(item, snapshot))
                .isEmpty();
        // And nothing was written to it on the way past.
        assertThat(tags.readWeight(item)).isEmpty();
    }

    // ------------------------------------------------------------- absence and failure

    @Test
    @DisplayName("a disabled adapter reports nothing and identification carries on without it")
    void disabledAdapterIsSilent() {
        AdapterItems disabled = AdapterItems.disabled(Logger.getLogger("test"));

        assertThat(disabled.isAvailable()).isFalse();
        assertThat(disabled.idsOf(new ItemStack(Material.PAPER))).isEmpty();

        HarvestResolver resolver = new HarvestResolver(tags,
                MythicItems.detect(Logger.getLogger("test")), disabled, loreParser, stamper);
        ItemStack tagged = new ItemStack(Material.PAPER);
        tags.writeIdentity(tagged, snapshot.registry().find(PLAIN_ODRE).orElseThrow(), 0.5);

        assertThat(resolver.resolve(tagged, snapshot))
                .as("the tag path must not depend on the adapter being up")
                .isPresent();
    }

    @Test
    @DisplayName("the bundled library really starts, and identifies a vanilla item")
    void theRealLibraryWorks() {
        // A mock server has none of the item plugins installed, which is also the shape of a server
        // that has just this plugin -- so the vanilla source is the one thing that must still work,
        // and 'mc:paper' is what our crops are built on.
        AdapterItems adapter = AdapterItems.init(plugin);

        assertThat(adapter.isAvailable())
                .as("if this fails the bundled library is dead weight and the third route is fiction")
                .isTrue();
        assertThat(adapter.idsOf(new ItemStack(Material.PAPER))).contains("mc:paper");
        assertThat(adapter.sources())
                .as("with no item plugins installed, vanilla is the only source")
                .containsExactly("mc");
    }

    @Test
    @DisplayName("core and the bundled library agree on which prefixes are vanilla")
    void vanillaPrefixesAgree() {
        // The same knowledge lives in two places and has to: core cannot import the library
        // (PackageBoundaryTest forbids it), so AdapterSources keeps its own copy of the vanilla
        // prefix list. If they drifted, /gs adapter bind would refuse an id the library happily
        // reports -- or worse, accept 'mc:paper' and make every sheet of paper sellable.
        for (String vanilla : List.of("mc:paper", "minecraft:paper", "vanilla:paper")) {
            assertThat(AdapterSources.isVanilla(vanilla))
                    .as("core says %s is vanilla", vanilla)
                    .isTrue();
            assertThat(dev.lk.itembridge.ItemBridge.isVanilla(vanilla))
                    .as("the library says %s is vanilla", vanilla)
                    .isTrue();
        }
        assertThat(AdapterSources.isVanilla("nx:tomato")).isFalse();
        assertThat(dev.lk.itembridge.ItemBridge.isVanilla("nx:tomato")).isFalse();
    }

    @Test
    @DisplayName("initialising twice is harmless, unlike the third-party library this replaced")
    void initIsRepeatable() {
        // Worth pinning: the GPL library this replaced kept static state for the whole JVM and
        // refused a second initialisation, which made every test that touched it order-dependent.
        assertThat(AdapterItems.init(plugin).isAvailable()).isTrue();
        assertThat(AdapterItems.init(plugin).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("air and null are never handed to the library")
    void airIsIgnored() {
        AdapterItems stub = AdapterItems.stub(List.of("mc:paper"));

        assertThat(stub.idsOf(null)).isEmpty();
        assertThat(stub.idsOf(new ItemStack(Material.AIR))).isEmpty();
        assertThat(stub.idsOf(new ItemStack(Material.PAPER))).containsExactly("mc:paper");
    }
}
