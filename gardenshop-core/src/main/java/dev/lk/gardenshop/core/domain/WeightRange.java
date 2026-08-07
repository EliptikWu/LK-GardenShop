package dev.lk.gardenshop.core.domain;

import java.util.random.RandomGenerator;

/**
 * Inclusive weight interval in kilograms, from which a concrete harvest weight
 * is rolled.
 *
 * @param minKg lower bound, inclusive; must be positive
 * @param maxKg upper bound, inclusive; must be >= {@code minKg}
 */
public record WeightRange(double minKg, double maxKg) {

    public WeightRange {
        if (!Double.isFinite(minKg) || minKg <= 0.0) {
            throw new IllegalArgumentException("minKg must be a positive finite number, got " + minKg);
        }
        if (!Double.isFinite(maxKg) || maxKg < minKg) {
            throw new IllegalArgumentException("maxKg (" + maxKg + ") must be >= minKg (" + minKg + ")");
        }
    }

    public double span() {
        return maxKg - minKg;
    }

    public double midpoint() {
        return (minKg + maxKg) / 2.0;
    }

    public boolean contains(double kg) {
        return kg >= minKg && kg <= maxKg;
    }

    public double clamp(double kg) {
        return Math.min(maxKg, Math.max(minKg, kg));
    }

    /**
     * Rolls a weight inside this range, rounded to two decimals so it matches
     * exactly what the player reads on the item's lore.
     */
    public double roll(RandomGenerator random) {
        double raw = minKg + random.nextDouble() * span();
        return clamp(Math.round(raw * 100.0) / 100.0);
    }

    /** Scales both bounds, e.g. to derive a Gold range from a Normal one. */
    public WeightRange scaled(double factor) {
        if (!Double.isFinite(factor) || factor <= 0.0) {
            throw new IllegalArgumentException("factor must be positive and finite, got " + factor);
        }
        return new WeightRange(minKg * factor, maxKg * factor);
    }
}
