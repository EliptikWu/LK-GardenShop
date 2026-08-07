package dev.lk.gardenshop.core.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A crop species as declared in {@code crops.yml}.
 *
 * @param id           config key, e.g. {@code odre}
 * @param displayName  name shown to players, may contain colour codes
 * @param mythicToken  fragment spliced into Mythic type names. Beware: Odre's
 *                     token is {@code NC}, not {@code Odre}
 * @param baseValue    money a reference-weight, unmutated, NORMAL crop is worth
 * @param baseWeightKg reference weight the price curve pivots around. Ratio band
 *                     mode divides the actual weight by this, so it must be > 0
 * @param baseRange    weight range of the plain NORMAL drop; used as the fallback
 *                     roll range when {@code weights.yml} has no entry for a type
 * @param extraIds     adapter ids from <em>other</em> item plugins ({@code ia:ns:id},
 *                     {@code nx:id}, {@code ce:id}) that should sell as this crop's
 *                     plain drop. Optional and normally empty; lowercased here because
 *                     the ids they are matched against are compared case-insensitively
 */
public record Species(
        String id,
        String displayName,
        String mythicToken,
        BigDecimal baseValue,
        double baseWeightKg,
        WeightRange baseRange,
        List<String> extraIds
) {

    /** A crop with no foreign item ids, which is the ordinary case. */
    public Species(String id, String displayName, String mythicToken, BigDecimal baseValue,
                   double baseWeightKg, WeightRange baseRange) {
        this(id, displayName, mythicToken, baseValue, baseWeightKg, baseRange, List.of());
    }

    public Species {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(mythicToken, "mythicToken");
        Objects.requireNonNull(baseValue, "baseValue");
        Objects.requireNonNull(baseRange, "baseRange");

        if (id.isBlank()) {
            throw new IllegalArgumentException("species id must not be blank");
        }
        if (mythicToken.isBlank()) {
            throw new IllegalArgumentException("species '" + id + "' has a blank mythic-token");
        }
        if (baseValue.signum() <= 0) {
            throw new IllegalArgumentException("species '" + id + "' base-value must be > 0, got " + baseValue);
        }
        // Ratio band mode and the formula strategy both divide by this.
        if (!Double.isFinite(baseWeightKg) || baseWeightKg <= 0.0) {
            throw new IllegalArgumentException(
                    "species '" + id + "' base-weight must be > 0, got " + baseWeightKg);
        }

        extraIds = extraIds == null ? List.of() : extraIds.stream()
                .filter(Objects::nonNull)
                .map(extra -> extra.trim().toLowerCase(Locale.ROOT))
                .filter(extra -> !extra.isEmpty())
                .distinct()
                .toList();
    }

    /** How heavy this harvest is relative to the species reference weight. */
    public double weightRatio(double weightKg) {
        return weightKg / baseWeightKg;
    }
}
