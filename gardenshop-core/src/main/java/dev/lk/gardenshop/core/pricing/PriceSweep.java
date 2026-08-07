package dev.lk.gardenshop.core.pricing;

import dev.lk.gardenshop.core.config.PricingConfig;
import dev.lk.gardenshop.core.domain.PriceQuote;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.Variant;
import dev.lk.gardenshop.core.registry.DropDefinition;
import dev.lk.gardenshop.core.registry.DropRegistry;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Prices every drop type at both ends of its weight range, so the whole economy can be
 * read at a glance.
 *
 * <p>This exists because balancing a 180-entry price matrix by editing multipliers and
 * then hunting for crops in-game does not work. What an owner actually needs to know is
 * "is the cheapest thing in my garden worth 8 and the dearest worth 40,000, and am I
 * happy with that?" — and that question is only answerable by looking at all of it.
 *
 * <p>Pure computation over an immutable config, so it is safe to run from a command
 * handler: 360 quotes is microseconds of work, no I/O.
 */
public final class PriceSweep {

    private final PriceCalculator calculator;

    public PriceSweep(PriceCalculator calculator) {
        this.calculator = Objects.requireNonNull(calculator, "calculator");
    }

    /**
     * One drop type, priced at the lightest and heaviest weight it can roll.
     *
     * @param atMin quote at the bottom of the range
     * @param atMax quote at the top of the range
     */
    public record TypeRow(DropDefinition definition, PriceQuote atMin, PriceQuote atMax) {

        public String mythicType() {
            return definition.mythicType();
        }

        public Variant variant() {
            return definition.variant();
        }

        /** How much heavier-is-better is worth within this one type. */
        public BigDecimal spread() {
            if (atMin.unitPrice().signum() == 0) {
                return BigDecimal.ZERO;
            }
            return atMax.unitPrice().divide(atMin.unitPrice(), MathContext.DECIMAL32);
        }
    }

    /**
     * A whole crop summarised.
     *
     * @param plainMid  what a typical, unmutated, average-weight one is worth — the
     *                  figure that decides how a new player experiences the crop
     * @param cheapest  the least any of its 30 types can be worth
     * @param dearest   the most any of them can be worth
     * @param multiple  {@code dearest / cheapest}: how far chasing weight, variants and
     *                  mutations actually gets a player
     */
    public record CropRow(
            Species species,
            BigDecimal plainMid,
            BigDecimal cheapest,
            String cheapestType,
            BigDecimal dearest,
            String dearestType,
            BigDecimal multiple
    ) {
    }

    /** Every type of one crop, ordered by variant then by top-end value. */
    public List<TypeRow> forCrop(String speciesId, DropRegistry registry, PricingConfig pricing) {
        List<TypeRow> rows = new ArrayList<>();
        for (DropDefinition definition : registry.all()) {
            if (definition.species().id().equals(speciesId)) {
                rows.add(rowFor(definition, pricing));
            }
        }
        rows.sort(Comparator
                .comparing((TypeRow row) -> row.variant().ordinal())
                .thenComparing(row -> row.atMax().unitPrice()));
        return rows;
    }

    /** Every type, for a full 180-row contrast sheet. */
    public List<TypeRow> all(DropRegistry registry, PricingConfig pricing) {
        List<TypeRow> rows = new ArrayList<>(registry.size());
        for (DropDefinition definition : registry.all()) {
            rows.add(rowFor(definition, pricing));
        }
        return rows;
    }

    /** One row per crop, in the order {@code crops.yml} declares them. */
    public List<CropRow> overview(DropRegistry registry, PricingConfig pricing) {
        Map<String, List<TypeRow>> byCrop = new LinkedHashMap<>();
        for (DropDefinition definition : registry.all()) {
            byCrop.computeIfAbsent(definition.species().id(), key -> new ArrayList<>())
                    .add(rowFor(definition, pricing));
        }

        List<CropRow> summary = new ArrayList<>(byCrop.size());
        byCrop.forEach((speciesId, rows) -> summary.add(summarise(rows, pricing)));
        return summary;
    }

    private CropRow summarise(List<TypeRow> rows, PricingConfig pricing) {
        Species species = rows.getFirst().definition().species();

        BigDecimal cheapest = null;
        String cheapestType = "";
        BigDecimal dearest = null;
        String dearestType = "";

        for (TypeRow row : rows) {
            // The cheapest a type can be is at its minimum weight, the dearest at its
            // maximum, so only one end of each row can ever be the extreme.
            if (cheapest == null || row.atMin().unitPrice().compareTo(cheapest) < 0) {
                cheapest = row.atMin().unitPrice();
                cheapestType = row.mythicType();
            }
            if (dearest == null || row.atMax().unitPrice().compareTo(dearest) > 0) {
                dearest = row.atMax().unitPrice();
                dearestType = row.mythicType();
            }
        }

        // The plain unmutated drop at its average weight: the everyday case.
        BigDecimal plainMid = rows.stream()
                .filter(row -> row.variant() == Variant.NORMAL && row.definition().mutations().isEmpty())
                .findFirst()
                .map(row -> calculator.quote(row.definition(),
                        row.definition().weightRange().midpoint(), pricing).unitPrice())
                .orElse(BigDecimal.ZERO);

        BigDecimal multiple = cheapest == null || cheapest.signum() == 0
                ? BigDecimal.ZERO
                : dearest.divide(cheapest, MathContext.DECIMAL32);

        return new CropRow(species, plainMid, cheapest, cheapestType, dearest, dearestType, multiple);
    }

    private TypeRow rowFor(DropDefinition definition, PricingConfig pricing) {
        return new TypeRow(definition,
                calculator.quote(definition, definition.weightRange().minKg(), pricing),
                calculator.quote(definition, definition.weightRange().maxKg(), pricing));
    }
}
