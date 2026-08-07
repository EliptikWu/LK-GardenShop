package dev.lk.gardenshop.core;

import dev.lk.gardenshop.core.config.BandMode;
import dev.lk.gardenshop.core.config.MutationStacking;
import dev.lk.gardenshop.core.config.PricingConfig;
import dev.lk.gardenshop.core.config.PricingMode;
import dev.lk.gardenshop.core.config.WeightBandTable;
import dev.lk.gardenshop.core.config.WeightTable;
import dev.lk.gardenshop.core.domain.Mutation;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.Variant;
import dev.lk.gardenshop.core.domain.WeightBand;
import dev.lk.gardenshop.core.domain.WeightRange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The six species and the default pricing table, mirroring what the plugin ships
 * in {@code crops.yml} and {@code pricing.yml}. Keeping them in lockstep means a
 * balance change to the shipped defaults has to face these tests.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static Species odre() {
        return new Species("odre", "Odre", "NC",
                new BigDecimal("18.0"), 0.475, new WeightRange(0.30, 0.65));
    }

    public static Species chilli() {
        return new Species("chilli", "Chilli", "Chilli",
                new BigDecimal("12.0"), 0.165, new WeightRange(0.08, 0.25));
    }

    public static Species blueBeet() {
        return new Species("blue_beet", "Blue Beet", "BlueBeet",
                new BigDecimal("22.0"), 0.350, new WeightRange(0.20, 0.50));
    }

    public static Species iceCotton() {
        return new Species("ice_cotton", "Ice Cotton", "IceCotton",
                new BigDecimal("9.0"), 0.100, new WeightRange(0.05, 0.15));
    }

    public static Species mandragora() {
        return new Species("mandragora", "Mandragora", "Mandragora",
                new BigDecimal("65.0"), 0.650, new WeightRange(0.40, 0.90));
    }

    public static Species carrotCross() {
        return new Species("carrot_cross", "Carrot Cross", "CarrotCross",
                new BigDecimal("15.0"), 0.235, new WeightRange(0.12, 0.35));
    }

    /** All six, in the order {@code crops.yml} declares them. */
    public static List<Species> allSpecies() {
        return List.of(odre(), chilli(), blueBeet(), iceCotton(), mandragora(), carrotCross());
    }

    /**
     * The shipped ratio ladder. The top band stops at 9.0 rather than some huge
     * sentinel on purpose: with interpolation on, a band spanning to 999 would
     * leave every reachable weight pinned near the <em>previous</em> rung's
     * multiplier, since nothing gets close to the far edge. 9.0 sits just above
     * the heaviest reachable ratio (Mandragora tops out at 8.46).
     */
    public static WeightBandTable ratioBands() {
        return new WeightBandTable(BandMode.RATIO, true, List.of(
                new WeightBand("runt", 0.85, new BigDecimal("0.6"), "Runt"),
                new WeightBand("normal", 1.30, new BigDecimal("1.0"), "Normal"),
                new WeightBand("big", 2.20, new BigDecimal("2.2"), "Big"),
                new WeightBand("huge", 4.00, new BigDecimal("6.0"), "Huge"),
                new WeightBand("titanic", 6.50, new BigDecimal("14.0"), "Titanic"),
                new WeightBand("mythic", 9.00, new BigDecimal("30.0"), "Mythic")));
    }

    public static Map<Variant, BigDecimal> variantMultipliers() {
        EnumMap<Variant, BigDecimal> multipliers = new EnumMap<>(Variant.class);
        multipliers.put(Variant.NORMAL, BigDecimal.ONE);
        multipliers.put(Variant.GOLD, new BigDecimal("2.5"));
        multipliers.put(Variant.RAINBOW, new BigDecimal("5.0"));
        return multipliers;
    }

    public static Map<Mutation, BigDecimal> mutationMultipliers() {
        EnumMap<Mutation, BigDecimal> multipliers = new EnumMap<>(Mutation.class);
        multipliers.put(Mutation.RAIN, new BigDecimal("1.8"));
        multipliers.put(Mutation.ICE, new BigDecimal("2.4"));
        multipliers.put(Mutation.LIGHTNING, new BigDecimal("3.0"));
        multipliers.put(Mutation.NETHER, new BigDecimal("4.0"));
        multipliers.put(Mutation.END, new BigDecimal("6.0"));
        return multipliers;
    }

    public static Map<String, Map<String, BigDecimal>> flatMoney() {
        return Map.of(PricingConfig.FLAT_MONEY_DEFAULT_KEY, Map.of(
                "runt", new BigDecimal("8"),
                "normal", new BigDecimal("25"),
                "big", new BigDecimal("90"),
                "huge", new BigDecimal("320"),
                "titanic", new BigDecimal("1100"),
                "mythic", new BigDecimal("4000")));
    }

    public static PricingConfig pricing(PricingMode mode) {
        return pricing(mode, ratioBands(), MutationStacking.ADDITIVE);
    }

    public static PricingConfig pricing(PricingMode mode, WeightBandTable bands, MutationStacking stacking) {
        return new PricingConfig(
                mode,
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("10000000"),
                RoundingMode.HALF_UP,
                2,
                bands,
                Map.of(),
                variantMultipliers(),
                mutationMultipliers(),
                stacking,
                2.0,
                flatMoney());
    }

    /** An empty weight table, so every type falls back to its species base range. */
    public static WeightTable noWeights() {
        return WeightTable.empty();
    }
}
