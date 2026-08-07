package dev.lk.gardenshop.core.config;

import java.util.Locale;
import java.util.Optional;

/** How a {@link WeightBandTable}'s bounds should be read. */
public enum BandMode {

    /**
     * Bounds are weight ÷ species base weight. Preferred, because every crop in
     * the pack tops out at roughly 8× its base weight, so one shared ladder fits
     * Ice Cotton (0.05–0.80 kg) and Mandragora (0.40–5.50 kg) alike.
     */
    RATIO,

    /** Bounds are literal kilograms. Needs a per-crop table to be meaningful. */
    ABSOLUTE;

    public static Optional<BandMode> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalised = id.trim().toUpperCase(Locale.ROOT);
        for (BandMode mode : values()) {
            if (mode.name().equals(normalised)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
