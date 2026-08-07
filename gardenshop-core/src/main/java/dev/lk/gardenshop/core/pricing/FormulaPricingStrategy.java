package dev.lk.gardenshop.core.pricing;

import dev.lk.gardenshop.core.config.BandMatch;
import dev.lk.gardenshop.core.config.PricingConfig;
import dev.lk.gardenshop.core.config.PricingMode;
import dev.lk.gardenshop.core.config.WeightBandTable;
import dev.lk.gardenshop.core.domain.CropDrop;
import dev.lk.gardenshop.core.domain.PriceFactor;
import dev.lk.gardenshop.core.domain.PriceQuote;
import dev.lk.gardenshop.core.domain.Species;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * Grow a Garden's own curve:
 *
 * <pre>unit = baseValue × (weight ÷ baseWeight)^exponent × variant × mutations × global</pre>
 *
 * <p>With the default exponent of 2, weight dominates everything: twice the weight
 * is four times the price, three times is nine. Continuous by construction, so
 * there are no band edges to game — but also no per-range dial to hand-tune, which
 * is why {@link HybridPricingStrategy} is the default.
 *
 * <p>The weight band is still resolved, purely so {@code /gs value} can show a
 * size label. It does not affect the price in this mode.
 */
public final class FormulaPricingStrategy implements PricingStrategy {

    @Override
    public PricingMode mode() {
        return PricingMode.FORMULA;
    }

    @Override
    public PriceQuote quote(CropDrop drop, PricingConfig config) {
        Species species = drop.species();
        WeightBandTable table = config.bandsFor(species.id());
        BandMatch match = table.resolve(table.valueFor(drop.weightKg(), species.baseWeightKg()));

        BigDecimal base = species.baseValue();
        BigDecimal weightFactor = Prices.power(drop.weightRatio(), config.weightExponent());
        BigDecimal variantMultiplier = config.variantMultiplier(drop.variant());
        BigDecimal mutationMultiplier = Prices.mutationMultiplier(drop, config);
        BigDecimal globalMultiplier = config.globalMultiplier();

        BigDecimal raw = base
                .multiply(weightFactor, MathContext.DECIMAL64)
                .multiply(variantMultiplier, MathContext.DECIMAL64)
                .multiply(mutationMultiplier, MathContext.DECIMAL64)
                .multiply(globalMultiplier, MathContext.DECIMAL64);

        List<PriceFactor> factors = List.of(
                new PriceFactor("base", species.displayName(), base),
                new PriceFactor("weight", formatRatio(drop.weightRatio(), config.weightExponent()), weightFactor),
                new PriceFactor("variant", drop.variant().name(), variantMultiplier),
                new PriceFactor("mutations", Prices.mutationLabel(drop), mutationMultiplier),
                new PriceFactor("global", "", globalMultiplier));

        return new PriceQuote(drop, Prices.finalise(raw, config), match.bandId(), match.bandLabel(), factors);
    }

    private static String formatRatio(double ratio, double exponent) {
        return String.format(java.util.Locale.ROOT, "%.2f^%.2f", ratio, exponent);
    }
}
