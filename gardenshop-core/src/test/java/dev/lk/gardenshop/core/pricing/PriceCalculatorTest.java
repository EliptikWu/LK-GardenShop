package dev.lk.gardenshop.core.pricing;

import dev.lk.gardenshop.core.TestFixtures;
import dev.lk.gardenshop.core.config.BandMode;
import dev.lk.gardenshop.core.config.MutationStacking;
import dev.lk.gardenshop.core.config.PricingConfig;
import dev.lk.gardenshop.core.config.PricingMode;
import dev.lk.gardenshop.core.config.WeightBandTable;
import dev.lk.gardenshop.core.domain.CropDrop;
import dev.lk.gardenshop.core.domain.Mutation;
import dev.lk.gardenshop.core.domain.PriceFactor;
import dev.lk.gardenshop.core.domain.PriceQuote;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.Variant;
import dev.lk.gardenshop.core.registry.DropDefinition;
import dev.lk.gardenshop.core.registry.DropRegistry;
import dev.lk.gardenshop.core.registry.MythicTypeComposer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceCalculatorTest {

    private final PriceCalculator calculator = new PriceCalculator();
    private final DropRegistry registry = MythicTypeComposer.withDefaultPattern()
            .buildRegistry(TestFixtures.allSpecies(), TestFixtures.noWeights());

    /** No mutations. Reads better than {@code EnumSet.noneOf(Mutation.class)} inline. */
    private static Set<Mutation> plain() {
        return EnumSet.noneOf(Mutation.class);
    }

    private static PricingConfig configWith(
            PricingMode mode,
            BigDecimal globalMultiplier,
            BigDecimal minPayout,
            double weightExponent,
            Map<String, Map<String, BigDecimal>> flatMoney) {
        return new PricingConfig(
                mode, globalMultiplier, minPayout, new BigDecimal("1E+40"),
                RoundingMode.HALF_UP, 2, TestFixtures.ratioBands(), Map.of(),
                TestFixtures.variantMultipliers(), TestFixtures.mutationMultipliers(),
                MutationStacking.ADDITIVE, weightExponent, flatMoney);
    }

    @Nested
    @DisplayName("across the whole 180-type matrix")
    class FullMatrix {

        @ParameterizedTest(name = "{0} mode")
        @EnumSource(PricingMode.class)
        @DisplayName("every drop type prices above zero at both ends of its weight range")
        void everyTypeIsSellable(PricingMode mode) {
            PricingConfig config = TestFixtures.pricing(mode);

            assertThat(registry.size()).isEqualTo(180);
            for (DropDefinition definition : registry.all()) {
                for (double weight : new double[]{
                        definition.weightRange().minKg(), definition.weightRange().maxKg()}) {
                    PriceQuote quote = calculator.quote(definition, weight, config);

                    assertThat(quote.unitPrice())
                            .as("%s at %.2fkg priced at zero", definition.mythicType(), weight)
                            .isGreaterThan(BigDecimal.ZERO);
                    assertThat(quote.bandId()).as("%s has no band", definition.mythicType()).isNotBlank();
                }
            }
        }

        @ParameterizedTest(name = "{0} mode")
        @EnumSource(PricingMode.class)
        @DisplayName("heavier is never cheaper, for any type")
        void monotonicInWeight(PricingMode mode) {
            PricingConfig config = TestFixtures.pricing(mode);

            for (DropDefinition definition : registry.all()) {
                BigDecimal previous = BigDecimal.ZERO;
                // Sweeps to twice the top of the range so it also covers weights
                // past the ladder's last rung, where clamping kicks in.
                for (double weight = 0.05; weight <= definition.weightRange().maxKg() * 2; weight += 0.05) {
                    BigDecimal price = calculator.quote(definition, weight, config).unitPrice();

                    assertThat(price)
                            .as("%s got cheaper at %.2fkg", definition.mythicType(), weight)
                            .isGreaterThanOrEqualTo(previous);
                    previous = price;
                }
            }
        }

        @ParameterizedTest(name = "{0} mode")
        @EnumSource(PricingMode.class)
        @DisplayName("prices honour the configured scale, so no fractional-cent drift")
        void respectsMoneyScale(PricingMode mode) {
            PricingConfig config = TestFixtures.pricing(mode);

            for (DropDefinition definition : registry.all()) {
                PriceQuote quote = calculator.quote(definition, definition.weightRange().midpoint(), config);
                assertThat(quote.unitPrice().scale()).isEqualTo(config.moneyScale());
            }
        }
    }

    @Nested
    @DisplayName("HYBRID mode")
    class Hybrid {

        private final PricingConfig config = TestFixtures.pricing(PricingMode.HYBRID);

        @Test
        @DisplayName("a reference-weight plain crop prices from its base value and band")
        void referenceWeight() {
            Species odre = TestFixtures.odre();
            // Ratio 1.0 sits inside the "normal" band, interpolating from the runt
            // edge: 0.6 + (1.0-0.85)/(1.30-0.85) x 0.4 = 0.7333 -> 18.0 x 0.7333.
            CropDrop drop = new CropDrop(odre, Variant.NORMAL, plain(), odre.baseWeightKg(), "growGardenNCDrop");

            PriceQuote quote = calculator.quote(drop, config);

            assertThat(quote.bandId()).isEqualTo("normal");
            assertThat(quote.unitPrice()).isEqualByComparingTo(new BigDecimal("13.20"));
        }

        @Test
        @DisplayName("variant and mutations multiply on top of the weight band")
        void variantAndMutationsStack() {
            Species odre = TestFixtures.odre();
            double weight = odre.baseWeightKg();

            BigDecimal plainPrice = calculator.quote(
                    new CropDrop(odre, Variant.NORMAL, plain(), weight, "t"), config).unitPrice();
            BigDecimal gold = calculator.quote(
                    new CropDrop(odre, Variant.GOLD, plain(), weight, "t"), config).unitPrice();
            BigDecimal goldIced = calculator.quote(
                    new CropDrop(odre, Variant.GOLD, EnumSet.of(Mutation.ICE), weight, "t"), config).unitPrice();

            // Weight is held constant, so the deltas are purely variant and mutation.
            assertThat(gold).isEqualByComparingTo(plainPrice.multiply(new BigDecimal("2.5")));
            assertThat(goldIced).isEqualByComparingTo(gold.multiply(new BigDecimal("2.4")));
        }

        @Test
        @DisplayName("the quote breaks down every factor for /gs value")
        void exposesBreakdown() {
            Species odre = TestFixtures.odre();
            CropDrop drop = new CropDrop(odre, Variant.RAINBOW,
                    EnumSet.of(Mutation.ICE, Mutation.RAIN, Mutation.LIGHTNING), 3.60, "t");

            PriceQuote quote = calculator.quote(drop, config);

            assertThat(quote.factors()).extracting(PriceFactor::key)
                    .containsExactly("base", "weight", "variant", "mutations", "global");
            assertThat(quote.factors())
                    .filteredOn(factor -> factor.key().equals("mutations"))
                    .singleElement()
                    .satisfies(factor -> {
                        assertThat(factor.label()).isEqualTo("ICE, RAIN, LIGHTNING");
                        // ADDITIVE: 1 + (2.4-1) + (1.8-1) + (3.0-1)
                        assertThat(factor.value()).isEqualByComparingTo(new BigDecimal("5.2"));
                    });
        }

        @Test
        @DisplayName("the same crop is worth far more the heavier it grows")
        void weightMatters() {
            Species mandragora = TestFixtures.mandragora();

            BigDecimal light = calculator.quote(
                    new CropDrop(mandragora, Variant.NORMAL, plain(), 0.40, "t"), config).unitPrice();
            BigDecimal heavy = calculator.quote(
                    new CropDrop(mandragora, Variant.NORMAL, plain(), 5.50, "t"), config).unitPrice();

            assertThat(heavy).isGreaterThan(light.multiply(new BigDecimal("10")));
        }
    }

    @Nested
    @DisplayName("FORMULA mode")
    class Formula {

        private final PricingConfig config = TestFixtures.pricing(PricingMode.FORMULA);

        @Test
        @DisplayName("with exponent 2, doubling the weight quadruples the price")
        void squaredWeight() {
            Species odre = TestFixtures.odre();
            double base = odre.baseWeightKg();

            BigDecimal atBase = calculator.quote(
                    new CropDrop(odre, Variant.NORMAL, plain(), base, "t"), config).unitPrice();
            BigDecimal atDouble = calculator.quote(
                    new CropDrop(odre, Variant.NORMAL, plain(), base * 2, "t"), config).unitPrice();
            BigDecimal atTriple = calculator.quote(
                    new CropDrop(odre, Variant.NORMAL, plain(), base * 3, "t"), config).unitPrice();

            assertThat(atBase).isEqualByComparingTo(odre.baseValue().setScale(2, RoundingMode.HALF_UP));
            assertThat(atDouble).isEqualByComparingTo(atBase.multiply(new BigDecimal("4")));
            assertThat(atTriple).isEqualByComparingTo(atBase.multiply(new BigDecimal("9")));
        }

        @Test
        @DisplayName("an absurd exponent stays finite instead of overflowing to infinity")
        void survivesOverflow() {
            PricingConfig steep = configWith(
                    PricingMode.FORMULA, BigDecimal.ONE, BigDecimal.ONE, 400.0, TestFixtures.flatMoney());

            PriceQuote quote = calculator.quote(
                    new CropDrop(TestFixtures.iceCotton(), Variant.RAINBOW, plain(), 900.0, "t"), steep);

            assertThat(quote.unitPrice()).isNotNull().isGreaterThan(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("BANDS mode")
    class Bands {

        private final PricingConfig config = TestFixtures.pricing(PricingMode.BANDS);

        @Test
        @DisplayName("pays the flat amount configured for the band the weight falls in")
        void flatPerBand() {
            Species odre = TestFixtures.odre();

            // 0.30kg -> ratio 0.63 -> runt; 2.50kg -> ratio 5.26 -> titanic.
            PriceQuote runt = calculator.quote(
                    new CropDrop(odre, Variant.NORMAL, plain(), 0.30, "t"), config);
            PriceQuote titanic = calculator.quote(
                    new CropDrop(odre, Variant.NORMAL, plain(), 2.50, "t"), config);

            assertThat(runt.bandId()).isEqualTo("runt");
            assertThat(runt.unitPrice()).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(titanic.bandId()).isEqualTo("titanic");
            assertThat(titanic.unitPrice()).isEqualByComparingTo(new BigDecimal("1100.00"));
        }

        @Test
        @DisplayName("variant and mutations are ignored - weight already encodes them")
        void ignoresVariantAndMutations() {
            Species odre = TestFixtures.odre();
            double weight = 1.00;

            BigDecimal plainPrice = calculator.quote(
                    new CropDrop(odre, Variant.NORMAL, plain(), weight, "t"), config).unitPrice();
            BigDecimal fancy = calculator.quote(
                    new CropDrop(odre, Variant.RAINBOW,
                            EnumSet.of(Mutation.ICE, Mutation.LIGHTNING), weight, "t"), config).unitPrice();

            assertThat(fancy).isEqualByComparingTo(plainPrice);
        }

        @Test
        @DisplayName("a band with no configured amount falls back to the crop's base value")
        void fallsBackToBaseValue() {
            PricingConfig noAmounts = configWith(
                    PricingMode.BANDS, BigDecimal.ONE, BigDecimal.ONE, 2.0, Map.of());

            PriceQuote quote = calculator.quote(
                    new CropDrop(TestFixtures.odre(), Variant.NORMAL, plain(), 0.475, "t"), noAmounts);

            assertThat(quote.unitPrice()).isEqualByComparingTo(new BigDecimal("18.00"));
        }
    }

    @Nested
    @DisplayName("payout guards")
    class Guards {

        @Test
        @DisplayName("min-payout floors the price so nothing ever sells for zero")
        void minPayoutFloor() {
            PricingConfig expensiveFloor = configWith(
                    PricingMode.HYBRID, BigDecimal.ONE, new BigDecimal("50"), 2.0, TestFixtures.flatMoney());

            // 0.05kg Ice Cotton is worth 5.40 on its own merits.
            PriceQuote quote = calculator.quote(
                    new CropDrop(TestFixtures.iceCotton(), Variant.NORMAL, plain(), 0.05, "t"), expensiveFloor);

            assertThat(quote.unitPrice()).isEqualByComparingTo(new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("a zero global multiplier still respects the payout floor")
        void zeroGlobalMultiplier() {
            PricingConfig free = configWith(
                    PricingMode.HYBRID, BigDecimal.ZERO, BigDecimal.ONE, 2.0, TestFixtures.flatMoney());

            PriceQuote quote = calculator.quote(
                    new CropDrop(TestFixtures.odre(), Variant.NORMAL, plain(), 0.50, "t"), free);

            assertThat(quote.unitPrice()).isEqualByComparingTo(new BigDecimal("1.00"));
        }

        @Test
        @DisplayName("a stack total is the unit price times the amount, exactly")
        void stackTotal() {
            PriceQuote quote = calculator.quote(
                    new CropDrop(TestFixtures.odre(), Variant.NORMAL, plain(), 0.475, "t"),
                    TestFixtures.pricing(PricingMode.HYBRID));

            assertThat(quote.total(0)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(quote.total(64)).isEqualByComparingTo(quote.unitPrice().multiply(new BigDecimal("64")));
        }
    }

    @Nested
    @DisplayName("strategy wiring")
    class Wiring {

        @Test
        @DisplayName("the calculator refuses to start with a mode left unhandled")
        void rejectsIncompleteStrategySet() {
            assertThatThrownBy(() -> new PriceCalculator(List.of(new HybridPricingStrategy())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no strategy registered");
        }

        @Test
        @DisplayName("an ABSOLUTE ladder prices on kilograms rather than ratios")
        void absoluteBands() {
            WeightBandTable absolute =
                    new WeightBandTable(BandMode.ABSOLUTE, false, TestFixtures.ratioBands().bands());
            PricingConfig config =
                    TestFixtures.pricing(PricingMode.HYBRID, absolute, MutationStacking.ADDITIVE);

            // 0.475kg is a "runt" in absolute terms even though it is Odre's
            // reference weight - which is exactly why RATIO is the shipped default.
            PriceQuote quote = calculator.quote(
                    new CropDrop(TestFixtures.odre(), Variant.NORMAL, plain(), 0.475, "t"), config);

            assertThat(quote.bandId()).isEqualTo("runt");
        }
    }
}
