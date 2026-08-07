package dev.lk.gardenshop.core.pricing;

import dev.lk.gardenshop.core.config.PricingConfig;
import dev.lk.gardenshop.core.domain.CropDrop;
import dev.lk.gardenshop.core.domain.Mutation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** Arithmetic shared by the pricing strategies. */
final class Prices {

    private Prices() {
    }

    /**
     * Rounds to the configured scale and applies the minimum payout floor.
     *
     * <p>Rounding happens per item, not per sale, so the figure a player is quoted
     * for one crop is exactly what that crop contributes to a bulk sale — no
     * off-by-a-cent surprises between {@code /gs value} and {@code /gs sell all}.
     */
    static BigDecimal finalise(BigDecimal raw, PricingConfig config) {
        BigDecimal rounded = raw.setScale(config.moneyScale(), config.rounding());
        if (rounded.signum() < 0) {
            rounded = BigDecimal.ZERO.setScale(config.moneyScale());
        }
        BigDecimal floor = config.minPayout().setScale(config.moneyScale(), config.rounding());
        return rounded.max(floor);
    }

    /** Combines the drop's mutation multipliers per the configured stacking rule. */
    static BigDecimal mutationMultiplier(CropDrop drop, PricingConfig config) {
        if (!drop.hasMutations()) {
            return BigDecimal.ONE;
        }
        List<BigDecimal> multipliers = new ArrayList<>(drop.mutations().size());
        for (Mutation mutation : drop.mutations()) {
            multipliers.add(config.mutationMultiplier(mutation));
        }
        return config.mutationStacking().combine(multipliers);
    }

    /**
     * Mutation names for display, in the pack's canonical order. Returns the enum
     * names; the presentation layer is free to localise them.
     */
    static String mutationLabel(CropDrop drop) {
        if (!drop.hasMutations()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (Mutation mutation : drop.mutations()) {
            joiner.add(mutation.name());
        }
        return joiner.toString();
    }

    /**
     * {@code base ^ exponent}, guarded against overflow.
     *
     * <p>A mis-typed exponent (or a base weight of 0.01 against a 5 kg harvest) can
     * push {@link Math#pow} to infinity. Clamping to {@link Double#MAX_VALUE} keeps
     * the arithmetic finite; {@code max-payout-per-sale} is what actually stops the
     * absurd number from reaching a balance.
     */
    static BigDecimal power(double base, double exponent) {
        double result = Math.pow(base, exponent);
        if (!Double.isFinite(result)) {
            return BigDecimal.valueOf(Double.MAX_VALUE);
        }
        if (result < 0.0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(result);
    }
}
