package dev.lk.gardenshop.core;

import dev.lk.gardenshop.core.config.MutationStacking;
import dev.lk.gardenshop.core.config.PricingConfig;
import dev.lk.gardenshop.core.config.PricingMode;
import dev.lk.gardenshop.core.config.ValidationReport;
import dev.lk.gardenshop.core.config.WeightTable;
import dev.lk.gardenshop.core.domain.Mutation;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.Variant;
import dev.lk.gardenshop.core.domain.WeightRange;
import dev.lk.gardenshop.core.registry.DropDefinition;
import dev.lk.gardenshop.core.registry.DropRegistry;
import dev.lk.gardenshop.core.registry.MythicTypeComposer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigValidatorTest {

    private final ConfigValidator validator = new ConfigValidator();
    private final MythicTypeComposer composer = MythicTypeComposer.withDefaultPattern();

    /**
     * A weight table covering every derived type, so the "no weight range" warning
     * stays quiet and tests can focus on the rule under examination.
     */
    private WeightTable fullWeights() {
        DropRegistry bootstrap = composer.buildRegistry(TestFixtures.allSpecies(), WeightTable.empty());
        Map<String, WeightRange> ranges = new HashMap<>();
        for (DropDefinition definition : bootstrap.all()) {
            double growth = 1.0 + definition.mutations().size() + definition.variant().ordinal();
            ranges.put(definition.mythicType(), definition.species().baseRange().scaled(growth));
        }
        return new WeightTable(ranges);
    }

    private DropRegistry fullRegistry() {
        return composer.buildRegistry(TestFixtures.allSpecies(), fullWeights());
    }

    @Test
    @DisplayName("the shipped defaults validate without errors")
    void defaultsAreValid() {
        ValidationReport report = validator.validate(fullRegistry(), TestFixtures.pricing(PricingMode.HYBRID));

        assertThat(report.hasErrors()).isFalse();
        assertThat(report.errors()).isEmpty();
    }

    @Test
    @DisplayName("declaring no crops is an error, since nothing would be sellable")
    void emptyRegistryIsAnError() {
        ValidationReport report =
                validator.validate(DropRegistry.empty(), TestFixtures.pricing(PricingMode.HYBRID));

        assertThat(report.hasErrors()).isTrue();
        assertThat(report.errors()).anyMatch(issue -> issue.message().contains("no crops declared"));
    }

    @Test
    @DisplayName("a per-crop band override naming an unknown crop is an error")
    void unknownPerCropOverride() {
        PricingConfig config = new PricingConfig(
                PricingMode.HYBRID, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("10000000"),
                RoundingMode.HALF_UP, 2, TestFixtures.ratioBands(),
                Map.of("tomato", TestFixtures.ratioBands()),
                TestFixtures.variantMultipliers(), TestFixtures.mutationMultipliers(),
                MutationStacking.ADDITIVE, 2.0, TestFixtures.flatMoney());

        ValidationReport report = validator.validate(fullRegistry(), config);

        assertThat(report.errors())
                .anyMatch(issue -> issue.path().equals("per-crop-bands.tomato"));
    }

    @Test
    @DisplayName("a min-payout above the per-sale cap is an error, not a silent contradiction")
    void impossiblePayoutWindow() {
        PricingConfig config = new PricingConfig(
                PricingMode.HYBRID, BigDecimal.ONE, new BigDecimal("500"), new BigDecimal("100"),
                RoundingMode.HALF_UP, 2, TestFixtures.ratioBands(), Map.of(),
                TestFixtures.variantMultipliers(), TestFixtures.mutationMultipliers(),
                MutationStacking.ADDITIVE, 2.0, TestFixtures.flatMoney());

        ValidationReport report = validator.validate(fullRegistry(), config);

        assertThat(report.errors()).anyMatch(issue -> issue.path().equals("min-payout"));
    }

    @Test
    @DisplayName("a missing variant multiplier warns rather than silently pricing at 1.0")
    void missingMultiplierWarns() {
        Map<Variant, BigDecimal> partial = new EnumMap<>(Variant.class);
        partial.put(Variant.NORMAL, BigDecimal.ONE);
        // GOLD and RAINBOW deliberately absent.

        PricingConfig config = new PricingConfig(
                PricingMode.HYBRID, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("10000000"),
                RoundingMode.HALF_UP, 2, TestFixtures.ratioBands(), Map.of(),
                partial, TestFixtures.mutationMultipliers(),
                MutationStacking.ADDITIVE, 2.0, TestFixtures.flatMoney());

        ValidationReport report = validator.validate(fullRegistry(), config);

        assertThat(report.hasErrors()).as("a missing multiplier is recoverable").isFalse();
        assertThat(report.warnings())
                .anyMatch(issue -> issue.path().equals("variants.GOLD"))
                .anyMatch(issue -> issue.path().equals("variants.RAINBOW"));
    }

    @Test
    @DisplayName("BANDS mode warns about bands with no configured amount")
    void bandsModeWarnsAboutGaps() {
        PricingConfig config = new PricingConfig(
                PricingMode.BANDS, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("10000000"),
                RoundingMode.HALF_UP, 2, TestFixtures.ratioBands(), Map.of(),
                TestFixtures.variantMultipliers(), TestFixtures.mutationMultipliers(),
                MutationStacking.ADDITIVE, 2.0, Map.of());

        ValidationReport report = validator.validate(fullRegistry(), config);

        assertThat(report.hasErrors()).isFalse();
        assertThat(report.warnings()).anyMatch(issue -> issue.message().contains("BANDS mode has no amount"));
    }

    @Test
    @DisplayName("the same gaps stay quiet in HYBRID mode, where the amounts are unused")
    void otherModesIgnoreFlatMoneyGaps() {
        PricingConfig config = new PricingConfig(
                PricingMode.HYBRID, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("10000000"),
                RoundingMode.HALF_UP, 2, TestFixtures.ratioBands(), Map.of(),
                TestFixtures.variantMultipliers(), TestFixtures.mutationMultipliers(),
                MutationStacking.ADDITIVE, 2.0, Map.of());

        ValidationReport report = validator.validate(fullRegistry(), config);

        assertThat(report.warnings()).noneMatch(issue -> issue.message().contains("BANDS mode"));
    }

    @Test
    @DisplayName("mutated drops with no weight entry warn, since they would roll far too light")
    void missingWeightRangesWarn() {
        DropRegistry withoutWeights = composer.buildRegistry(TestFixtures.allSpecies(), WeightTable.empty());

        ValidationReport report =
                validator.validate(withoutWeights, TestFixtures.pricing(PricingMode.HYBRID));

        assertThat(report.hasErrors()).isFalse();
        assertThat(report.warnings())
                .anyMatch(issue -> issue.file().equals(ConfigValidator.WEIGHTS_FILE)
                        && issue.message().contains("no weight range"));
    }

    @Test
    @DisplayName("a duplicated mythic-token is caught when the registry is built")
    void duplicateTokenIsRejected() {
        Species odre = TestFixtures.odre();
        Species clone = new Species("odre_copy", "Odre Copy", odre.mythicToken(),
                odre.baseValue(), odre.baseWeightKg(), odre.baseRange());

        assertThatThrownBy(() -> composer.buildRegistry(List.of(odre, clone), WeightTable.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mythic-token");
    }

    @Test
    @DisplayName("issues render with file and path so the owner knows where to look")
    void issuesAreActionable() {
        ValidationReport.Issue issue = new ValidationReport.Issue(
                ValidationReport.Severity.ERROR, "pricing.yml", "weight-bands.bands[2].max", "must be ascending");

        assertThat(issue.toString()).contains("pricing.yml").contains("weight-bands.bands[2].max");
    }

    @Test
    @DisplayName("reports merge, so per-file loaders can each contribute their own issues")
    void reportsMerge() {
        ValidationReport first = ValidationReport.builder().error("a.yml", "x", "boom").build();
        ValidationReport second = ValidationReport.builder().warning("b.yml", "y", "hmm").build();

        ValidationReport merged = first.merge(second);

        assertThat(merged.issues()).hasSize(2);
        assertThat(merged.hasErrors()).isTrue();
        assertThat(merged.warnings()).hasSize(1);
    }

    @Test
    @DisplayName("mutation coverage is checked too")
    void missingMutationMultiplierWarns() {
        Map<Mutation, BigDecimal> partial = new EnumMap<>(Mutation.class);
        partial.put(Mutation.RAIN, new BigDecimal("1.8"));

        PricingConfig config = new PricingConfig(
                PricingMode.HYBRID, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("10000000"),
                RoundingMode.HALF_UP, 2, TestFixtures.ratioBands(), Map.of(),
                TestFixtures.variantMultipliers(), partial,
                MutationStacking.ADDITIVE, 2.0, TestFixtures.flatMoney());

        ValidationReport report = validator.validate(fullRegistry(), config);

        assertThat(report.warnings()).anyMatch(issue -> issue.path().equals("mutations.LIGHTNING"));
    }
}
