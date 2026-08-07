package dev.lk.gardenshop.item;

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
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.WeightBand;
import dev.lk.gardenshop.core.domain.WeightRange;
import dev.lk.gardenshop.core.registry.MythicTypeComposer;
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
 * Refusing to trade when MythicMobs does not have the crops.
 *
 * <p>Not a lock — the source is public and the check is one call to delete. What it buys is a named
 * failure: without the pack, {@code crops.yml} prices 180 types that nothing in the world matches, so
 * every sale would otherwise answer "you have nothing to sell" to a player holding a full bag.
 */
class PackIntegrityTest {

    private PackIntegrity integrity;
    private MythicItems absentMythic;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        integrity = new PackIntegrity();
        // A mock server has no MythicMobs, which is also exactly the shape of a server that installed
        // the plugin and forgot the crop pack.
        absentMythic = MythicItems.detect(Logger.getLogger("test"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ConfigSnapshot snapshot(boolean requirePack) {
        WeightBandTable bands = new WeightBandTable(BandMode.RATIO, true, List.of(
                new WeightBand("normal", 9.00, BigDecimal.ONE, "&fNormal")));
        PricingConfig pricing = new PricingConfig(
                PricingMode.HYBRID, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("10000000"),
                RoundingMode.HALF_UP, 2, bands, Map.of(), Map.of(), Map.of(),
                MutationStacking.ADDITIVE, 2.0, Map.of());

        ItemSettings items = new ItemSettings(true, true, true,
                "&f&lWeight: &r%skg", "weight:", requirePack);

        return new ConfigSnapshot("en", pricing, items, SellingSettings.defaults(),
                EconomySettings.defaults(), GuiSettings.defaults(), ResourcePackSettings.defaults(),
                MythicTypeComposer.withDefaultPattern().buildRegistry(
                        List.of(new Species("odre", "Odre", "NC", new BigDecimal("18.0"), 0.475,
                                new WeightRange(0.30, 0.65))),
                        WeightTable.empty()),
                WeightTable.empty(), ValidationReport.empty(), System.currentTimeMillis());
    }

    @Test
    @DisplayName("before anything is verified, nothing is claimed to be satisfied")
    void startsUnknown() {
        // The initial value matters: it is what a sale sees if verification has not run yet, and
        // "satisfied" would be a guess in the one direction that lets money move.
        assertThat(integrity.latest().satisfied()).isFalse();
        assertThat(integrity.latest().declared()).isZero();
    }

    @Test
    @DisplayName("no MythicMobs means not satisfied, and says so rather than counting")
    void mythicAbsent() {
        PackIntegrity.Report report = integrity.verify(snapshot(true), absentMythic);

        assertThat(report.mythicHooked()).isFalse();
        assertThat(report.satisfied()).isFalse();
        assertThat(report.summary()).contains("MythicMobs is not hooked");
    }

    @Test
    @DisplayName("the report is stored, so a sale reads it without re-walking 30 types")
    void reportIsStored() {
        PackIntegrity.Report returned = integrity.verify(snapshot(true), absentMythic);

        assertThat(integrity.latest()).isEqualTo(returned);
    }

    @Test
    @DisplayName("every declared type missing reads as 'pack absent', not as a count")
    void packAbsentIsItsOwnCase() {
        PackIntegrity.Report report = integrity.verify(snapshot(true), absentMythic);

        // 30 of 30 missing is not "some drops are unavailable". Distinguishing it is the whole point:
        // one is a pack that needs fixing, the other is a pack that was never installed.
        assertThat(report.declared()).isEqualTo(30);
        assertThat(report.present()).isZero();
        assertThat(report.missing()).isEqualTo(30);
        assertThat(report.packAbsent()).isTrue();
    }

    @Test
    @DisplayName("a missing model is reported but does not stop trading")
    void missingModelDoesNotGate() {
        // An item that exists without a Model renders as the plain paper it is built on -- worth
        // saying, not worth refusing over. Another pack may legitimately style its items some other
        // way, and this plugin does not get to decide how other people build packs.
        PackIntegrity.Report withoutModels = new PackIntegrity.Report(true, 30, 30, 30);

        assertThat(withoutModels.satisfied()).isTrue();
        assertThat(withoutModels.summary()).contains("no Model");
    }

    @Test
    @DisplayName("a complete pack is satisfied and says so plainly")
    void completePack() {
        PackIntegrity.Report complete = new PackIntegrity.Report(true, 180, 180, 0);

        assertThat(complete.satisfied()).isTrue();
        assertThat(complete.packAbsent()).isFalse();
        assertThat(complete.summary()).isEqualTo("all 180 types present in the pack");
    }

    @Test
    @DisplayName("declaring no crops is never 'satisfied', however the numbers add up")
    void emptyRegistryIsNotSatisfied() {
        // 0 declared and 0 present would satisfy a naive missing == 0 check while the shop had
        // nothing to sell at all.
        assertThat(new PackIntegrity.Report(true, 0, 0, 0).satisfied()).isFalse();
    }
}
