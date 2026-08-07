package dev.lk.gardenshop.core.config;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * How multiple mutations on one crop combine.
 *
 * <p>Grow a Garden itself never stacks mutations, so it has no answer here. This
 * pack does — {@code growGardenNCDropIceRainLightning} carries three at once —
 * which makes the combination rule a balance decision rather than a fact to copy.
 */
public enum MutationStacking {

    /**
     * {@code 1 + Σ(mᵢ − 1)}: each extra mutation adds its bonus above 1.
     * With ×2, ×3 and ×4 that is 1 + 1 + 2 + 3 = <b>7</b>.
     */
    ADDITIVE {
        @Override
        public BigDecimal combine(Collection<BigDecimal> multipliers) {
            BigDecimal total = BigDecimal.ONE;
            for (BigDecimal multiplier : multipliers) {
                total = total.add(multiplier.subtract(BigDecimal.ONE), MathContext.DECIMAL64);
            }
            // A mutation configured below 1.0 could otherwise drive the price
            // negative once several stack.
            return total.signum() < 0 ? BigDecimal.ZERO : total;
        }
    },

    /**
     * {@code Π mᵢ}: multipliers compound.
     * With ×2, ×3 and ×4 that is <b>24</b> — far more explosive than ADDITIVE.
     */
    MULTIPLICATIVE {
        @Override
        public BigDecimal combine(Collection<BigDecimal> multipliers) {
            BigDecimal total = BigDecimal.ONE;
            for (BigDecimal multiplier : multipliers) {
                total = total.multiply(multiplier, MathContext.DECIMAL64);
            }
            return total;
        }
    };

    /** Returns {@link BigDecimal#ONE} for an empty collection. */
    public abstract BigDecimal combine(Collection<BigDecimal> multipliers);

    public static Optional<MutationStacking> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalised = id.trim().toUpperCase(Locale.ROOT);
        for (MutationStacking stacking : values()) {
            if (stacking.name().equals(normalised)) {
                return Optional.of(stacking);
            }
        }
        return Optional.empty();
    }
}
