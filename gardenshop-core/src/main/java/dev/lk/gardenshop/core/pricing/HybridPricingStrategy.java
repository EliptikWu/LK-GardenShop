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
 * The default strategy:
 *
 * <pre>unit = baseValue × weightBand × variant × mutations × global</pre>
 *
 * <p>The weight band supplies the shape of the curve — the whole point of the
 * feature — while variant and mutation multipliers layer independent bonuses on
 * top. Note this double-counts growth to a degree: a Rainbow drop both rolls
 * heavier (landing in a richer band) <em>and</em> earns the Rainbow multiplier.
 * That is intentional, it is what makes chasing variants feel worthwhile, but it
 * means the variant multipliers want to be gentler than Grow a Garden's own ×25.
 */
public final class HybridPricingStrategy implements PricingStrategy {

    @Override
    public PricingMode mode() {
        return PricingMode.HYBRID;
    }

    @Override
    public PriceQuote quote(CropDrop drop, PricingConfig config) {
        Species species = drop.species();
        WeightBandTable table = config.bandsFor(species.id());
        double bandInput = table.valueFor(drop.weightKg(), species.baseWeightKg());
        BandMatch match = table.resolve(bandInput);

        BigDecimal base = species.baseValue();
        BigDecimal bandMultiplier = match.multiplier();
        BigDecimal variantMultiplier = config.variantMultiplier(drop.variant());
        BigDecimal mutationMultiplier = Prices.mutationMultiplier(drop, config);
        BigDecimal globalMultiplier = config.globalMultiplier();

        BigDecimal raw = base
                .multiply(bandMultiplier, MathContext.DECIMAL64)
                .multiply(variantMultiplier, MathContext.DECIMAL64)
                .multiply(mutationMultiplier, MathContext.DECIMAL64)
                .multiply(globalMultiplier, MathContext.DECIMAL64);

        List<PriceFactor> factors = List.of(
                new PriceFactor("base", species.displayName(), base),
                new PriceFactor("weight", match.bandLabel(), bandMultiplier),
                new PriceFactor("variant", drop.variant().name(), variantMultiplier),
                new PriceFactor("mutations", Prices.mutationLabel(drop), mutationMultiplier),
                new PriceFactor("global", "", globalMultiplier));

        return new PriceQuote(drop, Prices.finalise(raw, config), match.bandId(), match.bandLabel(), factors);
    }
}
