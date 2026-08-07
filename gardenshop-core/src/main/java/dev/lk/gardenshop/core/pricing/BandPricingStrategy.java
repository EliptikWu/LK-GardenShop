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
 * Flat money per weight range — the most literal reading of "this weight bracket
 * pays this much":
 *
 * <pre>unit = flatMoney(species, band) × global</pre>
 *
 * <p>Variant and mutation multipliers are deliberately ignored. Gold and Rainbow
 * drops already roll two to five times heavier than plain ones, so they climb into
 * richer bands on their own; multiplying again would stack the same bonus twice.
 *
 * <p>Band interpolation is also bypassed: a flat amount per bracket is the point.
 * That does mean hard edges at the boundaries — a crop 0.01 kg over a threshold is
 * worth a step more — which is the trade-off this mode accepts in exchange for
 * being trivial to reason about.
 *
 * <p>A band with no configured amount falls back to the species' base value, and
 * the config validator raises that as a warning rather than letting it pass silently.
 */
public final class BandPricingStrategy implements PricingStrategy {

    @Override
    public PricingMode mode() {
        return PricingMode.BANDS;
    }

    @Override
    public PriceQuote quote(CropDrop drop, PricingConfig config) {
        Species species = drop.species();
        WeightBandTable table = config.bandsFor(species.id());
        BandMatch match = table.resolve(table.valueFor(drop.weightKg(), species.baseWeightKg()));

        BigDecimal flat = config.flatMoneyFor(species.id(), match.bandId())
                .orElse(species.baseValue());
        BigDecimal globalMultiplier = config.globalMultiplier();
        BigDecimal raw = flat.multiply(globalMultiplier, MathContext.DECIMAL64);

        List<PriceFactor> factors = List.of(
                new PriceFactor("flat", match.bandLabel(), flat),
                new PriceFactor("global", "", globalMultiplier));

        return new PriceQuote(drop, Prices.finalise(raw, config), match.bandId(), match.bandLabel(), factors);
    }
}
