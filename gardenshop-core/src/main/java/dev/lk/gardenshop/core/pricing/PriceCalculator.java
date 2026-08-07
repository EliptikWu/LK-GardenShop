package dev.lk.gardenshop.core.pricing;

import dev.lk.gardenshop.core.config.PricingConfig;
import dev.lk.gardenshop.core.config.PricingMode;
import dev.lk.gardenshop.core.domain.CropDrop;
import dev.lk.gardenshop.core.domain.PriceQuote;
import dev.lk.gardenshop.core.registry.DropDefinition;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Front door to pricing: picks the strategy named by
 * {@link PricingConfig#mode()} and delegates.
 *
 * <p>Stateless and thread-safe, so a single instance can be shared and can outlive
 * any number of {@code /gs reload} calls — the config travels as a parameter rather
 * than being captured.
 */
public final class PriceCalculator {

    private final Map<PricingMode, PricingStrategy> strategies;

    public PriceCalculator() {
        this(List.of(new HybridPricingStrategy(), new BandPricingStrategy(), new FormulaPricingStrategy()));
    }

    public PriceCalculator(Collection<PricingStrategy> strategies) {
        Objects.requireNonNull(strategies, "strategies");
        EnumMap<PricingMode, PricingStrategy> byMode = new EnumMap<>(PricingMode.class);
        for (PricingStrategy strategy : strategies) {
            PricingStrategy previous = byMode.put(strategy.mode(), strategy);
            if (previous != null) {
                throw new IllegalArgumentException("two strategies registered for mode " + strategy.mode());
            }
        }
        for (PricingMode mode : PricingMode.values()) {
            if (!byMode.containsKey(mode)) {
                throw new IllegalArgumentException("no strategy registered for mode " + mode);
            }
        }
        this.strategies = Map.copyOf(byMode);
    }

    public PriceQuote quote(CropDrop drop, PricingConfig config) {
        Objects.requireNonNull(drop, "drop");
        Objects.requireNonNull(config, "config");
        return strategies.get(config.mode()).quote(drop, config);
    }

    /** Convenience for the common "I have a definition and a weight" path. */
    public PriceQuote quote(DropDefinition definition, double weightKg, PricingConfig config) {
        Objects.requireNonNull(definition, "definition");
        return quote(definition.withWeight(weightKg), config);
    }
}
