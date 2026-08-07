package dev.lk.gardenshop.core.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One rung of the weight ladder: everything up to {@code max} that is not caught
 * by an earlier band earns {@code multiplier}.
 *
 * <p>Whether {@code max} is a ratio (weight ÷ species base weight) or an absolute
 * kilogram figure is decided by the owning table's
 * {@link dev.lk.gardenshop.core.config.BandMode}.
 *
 * @param id         stable key, also used to look up flat money in BANDS mode
 * @param max        inclusive upper bound
 * @param multiplier value multiplier earned at this rung
 * @param label      display name, may contain colour codes
 */
public record WeightBand(String id, double max, BigDecimal multiplier, String label) {

    public WeightBand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(multiplier, "multiplier");
        Objects.requireNonNull(label, "label");

        if (id.isBlank()) {
            throw new IllegalArgumentException("band id must not be blank");
        }
        if (!Double.isFinite(max) || max <= 0.0) {
            throw new IllegalArgumentException("band '" + id + "' max must be > 0, got " + max);
        }
        if (multiplier.signum() < 0) {
            throw new IllegalArgumentException("band '" + id + "' multiplier must be >= 0, got " + multiplier);
        }
    }
}
