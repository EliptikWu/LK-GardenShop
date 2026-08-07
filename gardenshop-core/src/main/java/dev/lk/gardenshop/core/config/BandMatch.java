package dev.lk.gardenshop.core.config;

import dev.lk.gardenshop.core.domain.WeightBand;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The band a weight landed in, plus the multiplier actually earned — which is not
 * necessarily {@code band.multiplier()}, since interpolation blends between rungs.
 */
public record BandMatch(WeightBand band, BigDecimal multiplier) {

    public BandMatch {
        Objects.requireNonNull(band, "band");
        Objects.requireNonNull(multiplier, "multiplier");
    }

    public String bandId() {
        return band.id();
    }

    public String bandLabel() {
        return band.label();
    }
}
