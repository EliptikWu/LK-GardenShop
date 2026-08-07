package dev.lk.gardenshop.core.config;

import dev.lk.gardenshop.core.domain.Mutation;
import dev.lk.gardenshop.core.domain.Variant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything from {@code pricing.yml}, parsed and immutable. Swapped wholesale by
 * {@code /gs reload} — never mutated in place.
 *
 * @param mode               which strategy prices a drop
 * @param globalMultiplier   server-wide dial, handy for events or a soft economy reset
 * @param minPayout          floor applied per item so nothing ever sells for 0
 * @param maxPayoutPerSale   ceiling on one sell operation; a backstop against a
 *                           mis-typed multiplier minting infinite money
 * @param rounding           rounding applied to the per-item price
 * @param moneyScale         decimal places kept on the per-item price
 * @param bands              the shared weight ladder
 * @param perCropBands       per-species ladder overrides, keyed by species id
 * @param weightExponent     exponent on the weight ratio in {@link PricingMode#FORMULA}
 * @param flatMoney          {@link PricingMode#BANDS} payouts: species id (or
 *                           {@value #FLAT_MONEY_DEFAULT_KEY}) → band id → amount
 */
public record PricingConfig(
        PricingMode mode,
        BigDecimal globalMultiplier,
        BigDecimal minPayout,
        BigDecimal maxPayoutPerSale,
        RoundingMode rounding,
        int moneyScale,
        WeightBandTable bands,
        Map<String, WeightBandTable> perCropBands,
        Map<Variant, BigDecimal> variantMultipliers,
        Map<Mutation, BigDecimal> mutationMultipliers,
        MutationStacking mutationStacking,
        double weightExponent,
        Map<String, Map<String, BigDecimal>> flatMoney
) {

    /** Key under {@code flat-money} holding the payouts used when a species has no entry. */
    public static final String FLAT_MONEY_DEFAULT_KEY = "default";

    public PricingConfig {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(globalMultiplier, "globalMultiplier");
        Objects.requireNonNull(minPayout, "minPayout");
        Objects.requireNonNull(maxPayoutPerSale, "maxPayoutPerSale");
        Objects.requireNonNull(rounding, "rounding");
        Objects.requireNonNull(bands, "bands");
        Objects.requireNonNull(mutationStacking, "mutationStacking");

        if (globalMultiplier.signum() < 0) {
            throw new IllegalArgumentException("global-multiplier must be >= 0, got " + globalMultiplier);
        }
        if (minPayout.signum() < 0) {
            throw new IllegalArgumentException("min-payout must be >= 0, got " + minPayout);
        }
        if (maxPayoutPerSale.signum() <= 0) {
            throw new IllegalArgumentException("max-payout-per-sale must be > 0, got " + maxPayoutPerSale);
        }
        if (moneyScale < 0 || moneyScale > 8) {
            throw new IllegalArgumentException("money-scale must be between 0 and 8, got " + moneyScale);
        }
        if (!Double.isFinite(weightExponent) || weightExponent < 0.0) {
            throw new IllegalArgumentException("weight-exponent must be finite and >= 0, got " + weightExponent);
        }

        perCropBands = Map.copyOf(Objects.requireNonNullElseGet(perCropBands, Map::of));
        variantMultipliers = immutableEnumMap(Variant.class, variantMultipliers);
        mutationMultipliers = immutableEnumMap(Mutation.class, mutationMultipliers);
        flatMoney = deepCopy(flatMoney);
    }

    /** The ladder for a species: its own override when present, otherwise the shared one. */
    public WeightBandTable bandsFor(String speciesId) {
        WeightBandTable override = perCropBands.get(speciesId);
        return override != null ? override : bands;
    }

    /** Defaults to 1.0 for a variant with no configured multiplier. */
    public BigDecimal variantMultiplier(Variant variant) {
        return variantMultipliers.getOrDefault(variant, BigDecimal.ONE);
    }

    /** Defaults to 1.0 for a mutation with no configured multiplier. */
    public BigDecimal mutationMultiplier(Mutation mutation) {
        return mutationMultipliers.getOrDefault(mutation, BigDecimal.ONE);
    }

    /**
     * Flat payout for a species/band pair in {@link PricingMode#BANDS}, falling
     * back to the {@value #FLAT_MONEY_DEFAULT_KEY} table.
     */
    public Optional<BigDecimal> flatMoneyFor(String speciesId, String bandId) {
        Map<String, BigDecimal> perSpecies = flatMoney.get(speciesId);
        if (perSpecies != null && perSpecies.containsKey(bandId)) {
            return Optional.of(perSpecies.get(bandId));
        }
        Map<String, BigDecimal> defaults = flatMoney.get(FLAT_MONEY_DEFAULT_KEY);
        return defaults == null ? Optional.empty() : Optional.ofNullable(defaults.get(bandId));
    }

    /** An EnumMap keeps ordinal iteration order and rejects nulls, unlike Map.copyOf. */
    private static <E extends Enum<E>> Map<E, BigDecimal> immutableEnumMap(
            Class<E> type, Map<E, BigDecimal> source) {
        EnumMap<E, BigDecimal> copy = new EnumMap<>(type);
        if (source != null) {
            copy.putAll(source);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Map<String, BigDecimal>> deepCopy(
            Map<String, Map<String, BigDecimal>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, BigDecimal>> copy = new HashMap<>(source.size() * 2);
        source.forEach((key, amounts) -> copy.put(key, Map.copyOf(amounts)));
        return Map.copyOf(copy);
    }
}
