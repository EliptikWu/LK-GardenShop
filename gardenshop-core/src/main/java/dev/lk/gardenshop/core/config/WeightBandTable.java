package dev.lk.gardenshop.core.config;

import dev.lk.gardenshop.core.domain.WeightBand;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The weight ladder: an ascending list of bands that turns a weight into a value
 * multiplier. This is the piece the server owner tunes to decide how much a given
 * weight range is worth.
 *
 * <h2>Interpolation</h2>
 * With {@code interpolate = false} every weight inside a band earns exactly that
 * band's multiplier. That is simple to reason about but creates cliffs: at a
 * boundary of 1.30 → 2.20, a 1.31 kg crop is worth more than twice a 1.30 kg one,
 * and everything between 1.31 and 2.20 kg is worth the same. Players notice, and
 * it makes the middle of every band pointless.
 *
 * <p>With {@code interpolate = true} (the default) a band's declared multiplier is
 * what you earn <em>at its upper edge</em>, and weights in between are blended
 * linearly from the previous rung. The curve is then continuous and strictly
 * non-decreasing, so heavier is always at least as good — no cliffs to farm.
 *
 * <p>The first band is flat: everything below its upper edge earns its multiplier,
 * rather than being lerped down towards zero, so a runt crop is still worth
 * something.
 */
public record WeightBandTable(BandMode mode, boolean interpolate, List<WeightBand> bands) {

    public WeightBandTable {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(bands, "bands");

        List<String> problems = validate(bands);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid weight band table: " + String.join("; ", problems));
        }
        bands = List.copyOf(bands);
    }

    /**
     * Checks the structural rules without throwing, so the config loader can
     * collect every problem into one report instead of dying on the first.
     *
     * @return an empty list when {@code bands} is usable
     */
    public static List<String> validate(List<WeightBand> bands) {
        List<String> problems = new ArrayList<>();
        if (bands == null || bands.isEmpty()) {
            problems.add("at least one band is required");
            return problems;
        }

        double previousMax = 0.0;
        List<String> seenIds = new ArrayList<>();
        for (WeightBand band : bands) {
            if (seenIds.contains(band.id())) {
                problems.add("duplicate band id '" + band.id() + "'");
            }
            seenIds.add(band.id());

            if (band.max() <= previousMax) {
                problems.add("band '" + band.id() + "' max (" + band.max()
                        + ") must be greater than the previous band's max (" + previousMax
                        + ") — bands must be listed in ascending order");
            }
            previousMax = Math.max(previousMax, band.max());
        }
        return problems;
    }

    /**
     * Resolves a weight (or weight ratio, per {@link #mode()}) to its band and
     * earned multiplier.
     *
     * @param value ratio in {@link BandMode#RATIO}, kilograms in {@link BandMode#ABSOLUTE}
     */
    public BandMatch resolve(double value) {
        int index = indexFor(value);
        WeightBand band = bands.get(index);

        if (!interpolate || index == 0) {
            return new BandMatch(band, band.multiplier());
        }

        WeightBand previous = bands.get(index - 1);
        double lowerEdge = previous.max();
        double upperEdge = band.max();

        // Past the top of the ladder there is nothing to blend towards, so the
        // last rung's multiplier is the ceiling.
        if (value >= upperEdge) {
            return new BandMatch(band, band.multiplier());
        }

        double width = upperEdge - lowerEdge;
        // validate() guarantees strictly ascending bounds, so width > 0 here.
        double t = Math.clamp((value - lowerEdge) / width, 0.0, 1.0);

        BigDecimal from = previous.multiplier();
        BigDecimal to = band.multiplier();
        BigDecimal blended = from.add(
                to.subtract(from).multiply(BigDecimal.valueOf(t), MathContext.DECIMAL64),
                MathContext.DECIMAL64);

        return new BandMatch(band, blended);
    }

    /** Index of the first band whose upper bound covers {@code value}; the last band if none does. */
    private int indexFor(double value) {
        for (int i = 0; i < bands.size(); i++) {
            if (value <= bands.get(i).max()) {
                return i;
            }
        }
        return bands.size() - 1;
    }

    /** The value fed to {@link #resolve(double)} for a given harvest under this mode. */
    public double valueFor(double weightKg, double baseWeightKg) {
        return mode == BandMode.RATIO ? weightKg / baseWeightKg : weightKg;
    }
}
