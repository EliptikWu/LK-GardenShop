package dev.lk.gardenshop.core.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * What one item of a given {@link CropDrop} is worth, plus the factors that got
 * it there so {@code /gs value} can show its work.
 *
 * <p>{@code unitPrice} is already rounded and floored at the configured minimum
 * payout. Rounding happens per item rather than per sale so the number a player
 * sees in an appraisal is exactly what each item contributes to a bulk sale.
 */
public record PriceQuote(
        CropDrop drop,
        BigDecimal unitPrice,
        String bandId,
        String bandLabel,
        List<PriceFactor> factors
) {

    public PriceQuote {
        Objects.requireNonNull(drop, "drop");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(bandId, "bandId");
        Objects.requireNonNull(bandLabel, "bandLabel");
        Objects.requireNonNull(factors, "factors");

        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative, got " + unitPrice);
        }
        factors = List.copyOf(factors);
    }

    /** Total for {@code amount} identical items. */
    public BigDecimal total(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative, got " + amount);
        }
        return unitPrice.multiply(BigDecimal.valueOf(amount));
    }
}
