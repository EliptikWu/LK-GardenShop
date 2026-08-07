package dev.lk.gardenshop.core.config;

import java.util.Locale;
import java.util.Optional;

/** Which pricing strategy is in charge. Switchable from YAML without recompiling. */
public enum PricingMode {

    /**
     * base × weightBand × variant × mutations × global.
     * The weight band does the shaping; variant and mutations layer on top.
     */
    HYBRID,

    /**
     * A flat amount per weight band, from {@code pricing.yml → flat-money}.
     * Variant and mutation multipliers are ignored on purpose: Gold and Rainbow
     * drops already roll heavier, so they land in richer bands on their own.
     */
    BANDS,

    /**
     * base × (weight ÷ baseWeight)^exponent × variant × mutations × global.
     * Continuous curve, faithful to Grow a Garden's own formula.
     */
    FORMULA;

    public static Optional<PricingMode> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalised = id.trim().toUpperCase(Locale.ROOT);
        for (PricingMode mode : values()) {
            if (mode.name().equals(normalised)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
