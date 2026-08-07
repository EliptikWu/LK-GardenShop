package dev.lk.gardenshop.core.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One named contribution to a price, kept structured rather than pre-formatted so
 * the presentation layer owns all wording and colour.
 *
 * @param key   stable identifier: {@code base}, {@code weight}, {@code variant},
 *              {@code mutations}, {@code global}, {@code flat}
 * @param label human-readable detail, e.g. a band label or a mutation list; may
 *              be empty when the key says everything
 * @param value the multiplier (or, for {@code base} and {@code flat}, an amount)
 */
public record PriceFactor(String key, String label, BigDecimal value) {

    public PriceFactor {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(value, "value");
    }

    public static PriceFactor of(String key, BigDecimal value) {
        return new PriceFactor(key, "", value);
    }
}
