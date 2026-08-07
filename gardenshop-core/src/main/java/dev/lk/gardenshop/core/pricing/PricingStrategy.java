package dev.lk.gardenshop.core.pricing;

import dev.lk.gardenshop.core.config.PricingConfig;
import dev.lk.gardenshop.core.config.PricingMode;
import dev.lk.gardenshop.core.domain.CropDrop;
import dev.lk.gardenshop.core.domain.PriceQuote;

/**
 * Turns a weighed harvest into a per-item price.
 *
 * <p>Implementations must be stateless and side-effect free: the same drop and
 * config always produce the same quote. That is what lets {@code /gs value}
 * promise a number the subsequent sale will honour.
 */
public interface PricingStrategy {

    PricingMode mode();

    PriceQuote quote(CropDrop drop, PricingConfig config);
}
