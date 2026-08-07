package dev.lk.gardenshop.core;

import dev.lk.gardenshop.core.config.PricingConfig;
import dev.lk.gardenshop.core.config.PricingMode;
import dev.lk.gardenshop.core.config.ValidationReport;
import dev.lk.gardenshop.core.config.WeightBandTable;
import dev.lk.gardenshop.core.domain.Mutation;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.Variant;
import dev.lk.gardenshop.core.domain.WeightBand;
import dev.lk.gardenshop.core.registry.DropDefinition;
import dev.lk.gardenshop.core.registry.DropRegistry;

import java.util.HashSet;
import java.util.Set;

/**
 * Cross-file checks that no single record can make on its own.
 *
 * <p>Each record validates its own invariants in its constructor, and the YAML
 * loader validates individual values as it reads them. What is left — and what
 * lives here — are the relationships <em>between</em> files: does a per-crop band
 * override name a crop that exists, does every band have a payout in BANDS mode,
 * does the weights file mention types the pack does not have.
 *
 * <p>Everything is reported, nothing is thrown. A single {@code /gs reload} should
 * tell the owner about all their mistakes at once.
 */
public final class ConfigValidator {

    public static final String PRICING_FILE = "pricing.yml";
    public static final String CROPS_FILE = "crops.yml";
    public static final String WEIGHTS_FILE = "weights.yml";

    /**
     * @param registry the freshly derived type matrix
     * @param pricing  the freshly parsed pricing config
     * @return issues to merge into the load report
     */
    public ValidationReport validate(DropRegistry registry, PricingConfig pricing) {
        ValidationReport.Builder report = ValidationReport.builder();

        validateRegistry(registry, report);
        validatePayoutRange(pricing, report);
        validateMultiplierCoverage(pricing, report);
        validatePerCropOverrides(registry, pricing, report);
        validateFlatMoney(registry, pricing, report);
        validateFormulaExponent(pricing, report);
        validateWeightCoverage(registry, report);

        return report.build();
    }

    private void validateRegistry(DropRegistry registry, ValidationReport.Builder report) {
        if (registry.isEmpty()) {
            report.error(CROPS_FILE, "crops",
                    "no crops declared — nothing would be sellable. Declare at least one crop.");
        }
    }

    private void validatePayoutRange(PricingConfig pricing, ValidationReport.Builder report) {
        if (pricing.minPayout().compareTo(pricing.maxPayoutPerSale()) > 0) {
            report.error(PRICING_FILE, "min-payout",
                    "min-payout (" + pricing.minPayout() + ") is above max-payout-per-sale ("
                            + pricing.maxPayoutPerSale() + "), so every sale would be capped below its floor");
        }
    }

    private void validateMultiplierCoverage(PricingConfig pricing, ValidationReport.Builder report) {
        // Missing entries silently default to 1.0, which reads as "this variant is
        // worthless" rather than "I forgot to configure it" — worth flagging.
        for (Variant variant : Variant.values()) {
            if (!pricing.variantMultipliers().containsKey(variant)) {
                report.warning(PRICING_FILE, "variants." + variant.name(),
                        "no multiplier configured, defaulting to 1.0");
            }
        }
        for (Mutation mutation : Mutation.values()) {
            if (!pricing.mutationMultipliers().containsKey(mutation)) {
                report.warning(PRICING_FILE, "mutations." + mutation.name(),
                        "no multiplier configured, defaulting to 1.0");
            }
        }
    }

    private void validatePerCropOverrides(DropRegistry registry, PricingConfig pricing,
                                          ValidationReport.Builder report) {
        Set<String> knownSpecies = speciesIds(registry);
        for (String speciesId : pricing.perCropBands().keySet()) {
            if (!knownSpecies.contains(speciesId)) {
                report.error(PRICING_FILE, "per-crop-bands." + speciesId,
                        "no crop with id '" + speciesId + "' is declared in " + CROPS_FILE);
            }
        }
    }

    private void validateFlatMoney(DropRegistry registry, PricingConfig pricing,
                                   ValidationReport.Builder report) {
        Set<String> knownSpecies = speciesIds(registry);

        for (String key : pricing.flatMoney().keySet()) {
            if (!PricingConfig.FLAT_MONEY_DEFAULT_KEY.equals(key) && !knownSpecies.contains(key)) {
                report.warning(PRICING_FILE, "flat-money." + key,
                        "no crop with id '" + key + "' is declared in " + CROPS_FILE + " — entry ignored");
            }
        }

        // Only BANDS mode reads these amounts; complaining about gaps in the other
        // modes would just be noise.
        if (pricing.mode() != PricingMode.BANDS) {
            return;
        }

        for (Species species : registry.species()) {
            WeightBandTable table = pricing.bandsFor(species.id());
            for (WeightBand band : table.bands()) {
                if (pricing.flatMoneyFor(species.id(), band.id()).isEmpty()) {
                    report.warning(PRICING_FILE,
                            "flat-money." + PricingConfig.FLAT_MONEY_DEFAULT_KEY + "." + band.id(),
                            "BANDS mode has no amount for band '" + band.id() + "' (crop '" + species.id()
                                    + "'), falling back to that crop's base-value of " + species.baseValue());
                }
            }
        }
    }

    private void validateFormulaExponent(PricingConfig pricing, ValidationReport.Builder report) {
        if (pricing.mode() == PricingMode.FORMULA && pricing.weightExponent() == 0.0) {
            report.warning(PRICING_FILE, "weight-exponent",
                    "an exponent of 0 makes every weight worth the same, which defeats FORMULA mode");
        }
    }

    private void validateWeightCoverage(DropRegistry registry, ValidationReport.Builder report) {
        int missing = 0;
        String firstMissing = null;
        for (DropDefinition definition : registry.all()) {
            if (definition.weightRange().equals(definition.species().baseRange())
                    && !definition.mutations().isEmpty()) {
                // A mutated drop reusing the plain base range means weights.yml has
                // no entry for it, so it will roll far too light for its tier.
                missing++;
                if (firstMissing == null) {
                    firstMissing = definition.mythicType();
                }
            }
        }
        if (missing > 0) {
            report.warning(WEIGHTS_FILE, "weights",
                    missing + " mutated drop type(s) have no weight range and fall back to their crop's base"
                            + " range (first: " + firstMissing + "). Regenerate weights.yml with"
                            + " scripts/gen-weights.ps1 after changing the pack.");
        }
    }

    private static Set<String> speciesIds(DropRegistry registry) {
        Set<String> ids = new HashSet<>();
        for (Species species : registry.species()) {
            ids.add(species.id());
        }
        return ids;
    }
}
