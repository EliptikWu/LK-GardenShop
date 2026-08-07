package dev.lk.gardenshop.core.pricing;

import dev.lk.gardenshop.core.TestFixtures;
import dev.lk.gardenshop.core.config.PricingConfig;
import dev.lk.gardenshop.core.config.PricingMode;
import dev.lk.gardenshop.core.config.WeightTable;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.Variant;
import dev.lk.gardenshop.core.domain.WeightRange;
import dev.lk.gardenshop.core.registry.DropDefinition;
import dev.lk.gardenshop.core.registry.DropRegistry;
import dev.lk.gardenshop.core.registry.MythicTypeComposer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shape of the shipped economy, and prints it.
 *
 * <p>Run with {@code ./gradlew test -PshowTestOutput} to read the full calibration sheet
 * — the same figures {@code /gs prices} shows in game, but for the whole 180-type matrix
 * at once. Handy when changing multipliers in {@code pricing.yml}.
 *
 * <p>The assertions here are deliberately loose bounds rather than exact figures. Their
 * job is to catch a multiplier that turns the economy inside out — a Rainbow worth less
 * than a plain drop, or a top-end crop worth ten million — not to freeze the balance.
 */
class PriceSweepTest {

    /** Ranges parsed out of the real weights.yml, so the sweep reflects the shipped data. */
    private static final Pattern WEIGHT_ENTRY = Pattern.compile(
            "^\\s*(\\w+):\\s*\\{\\s*min:\\s*([\\d.]+),\\s*max:\\s*([\\d.]+)\\s*}\\s*$");

    private final PriceCalculator calculator = new PriceCalculator();
    private final PriceSweep sweep = new PriceSweep(calculator);

    private DropRegistry registry() {
        return MythicTypeComposer.withDefaultPattern()
                .buildRegistry(TestFixtures.allSpecies(), shippedWeights());
    }

    @Test
    @DisplayName("the sweep covers every one of the 180 drop types")
    void coversEverything() {
        List<PriceSweep.TypeRow> rows = sweep.all(registry(), TestFixtures.pricing(PricingMode.HYBRID));

        assertThat(rows).hasSize(180);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.atMin().unitPrice()).isPositive();
            assertThat(row.atMax().unitPrice()).isGreaterThanOrEqualTo(row.atMin().unitPrice());
        });
    }

    @Test
    @DisplayName("every crop summarises with a sane cheap-to-dear span")
    void overviewIsSane() {
        PricingConfig pricing = TestFixtures.pricing(PricingMode.HYBRID);
        List<PriceSweep.CropRow> rows = sweep.overview(registry(), pricing);

        assertThat(rows).hasSize(6);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.cheapest()).as("%s cheapest", row.species().id()).isPositive();
            assertThat(row.dearest()).as("%s dearest", row.species().id())
                    .isGreaterThan(row.cheapest());
            assertThat(row.plainMid()).as("%s typical", row.species().id()).isPositive();

            // A top-end crop should be a real prize but not a jackpot that breaks the
            // server economy in one harvest. These bounds are wide on purpose.
            assertThat(row.multiple().doubleValue())
                    .as("%s dearest/cheapest multiple", row.species().id())
                    .isBetween(50.0, 5000.0);
        });
    }

    @Test
    @DisplayName("chasing variants and mutations always pays, never backfires")
    void progressionIsMonotonic() {
        PricingConfig pricing = TestFixtures.pricing(PricingMode.HYBRID);
        DropRegistry registry = registry();

        for (Species species : registry.species()) {
            BigDecimal plain = topPriceOf(registry, pricing, species.id(), Variant.NORMAL);
            BigDecimal gold = topPriceOf(registry, pricing, species.id(), Variant.GOLD);
            BigDecimal rainbow = topPriceOf(registry, pricing, species.id(), Variant.RAINBOW);

            assertThat(gold).as("%s: Gold must beat Normal", species.id()).isGreaterThan(plain);
            assertThat(rainbow).as("%s: Rainbow must beat Gold", species.id()).isGreaterThan(gold);
        }
    }

    @Test
    @DisplayName("no crop is so much better than the others that the rest are pointless")
    void cropsStayCompetitive() {
        PricingConfig pricing = TestFixtures.pricing(PricingMode.HYBRID);
        List<PriceSweep.CropRow> rows = sweep.overview(registry(), pricing);

        BigDecimal weakest = rows.stream().map(PriceSweep.CropRow::plainMid)
                .min(BigDecimal::compareTo).orElseThrow();
        BigDecimal strongest = rows.stream().map(PriceSweep.CropRow::plainMid)
                .max(BigDecimal::compareTo).orElseThrow();

        // Mandragora is meant to be the premium crop, but Ice Cotton should still be
        // worth planting. An order of magnitude between the everyday cases is plenty.
        assertThat(strongest.doubleValue() / weakest.doubleValue())
                .as("gap between the weakest and strongest typical crop")
                .isLessThan(15.0);
    }

    @Test
    @DisplayName("prints the calibration sheet (./gradlew test -PshowTestOutput)")
    void printCalibrationSheet() {
        PricingConfig pricing = TestFixtures.pricing(PricingMode.HYBRID);
        DropRegistry registry = registry();

        System.out.println();
        System.out.printf(Locale.ROOT, "=== LKGardenShop calibration sheet (%s mode, %d types) ===%n",
                pricing.mode(), registry.size());
        System.out.println();
        System.out.printf(Locale.ROOT, "%-14s %10s %12s %12s %10s%n",
                "CROP", "TYPICAL", "CHEAPEST", "DEAREST", "MULTIPLE");
        System.out.println("-".repeat(62));

        for (PriceSweep.CropRow row : sweep.overview(registry, pricing)) {
            System.out.printf(Locale.ROOT, "%-14s %10s %12s %12s %9sx%n",
                    row.species().id(),
                    row.plainMid().toPlainString(),
                    row.cheapest().toPlainString(),
                    row.dearest().toPlainString(),
                    row.multiple().setScale(0, java.math.RoundingMode.HALF_UP).toPlainString());
        }

        for (Species species : registry.species()) {
            System.out.println();
            System.out.printf(Locale.ROOT, "--- %s (base %s, ref %.3fkg) ---%n",
                    species.id(), species.baseValue().toPlainString(), species.baseWeightKg());
            System.out.printf(Locale.ROOT, "  %-8s %-20s %13s %-9s %11s %11s%n",
                    "VARIANT", "MUTATIONS", "WEIGHT kg", "BAND", "AT MIN", "AT MAX");

            for (PriceSweep.TypeRow row : sweep.forCrop(species.id(), registry, pricing)) {
                System.out.printf(Locale.ROOT, "  %-8s %-20s %5.2f-%-7.2f %-9s %11s %11s%n",
                        row.variant().name(),
                        row.definition().mutations().isEmpty() ? "-" : row.definition().mutations().toString()
                                .replaceAll("[\\[\\] ]", ""),
                        row.definition().weightRange().minKg(),
                        row.definition().weightRange().maxKg(),
                        row.atMax().bandId(),
                        row.atMin().unitPrice().toPlainString(),
                        row.atMax().unitPrice().toPlainString());
            }
        }
        System.out.println();
    }

    // ------------------------------------------------------------------- utilities

    private BigDecimal topPriceOf(DropRegistry registry, PricingConfig pricing,
                                  String speciesId, Variant variant) {
        return sweep.forCrop(speciesId, registry, pricing).stream()
                .filter(row -> row.variant() == variant)
                .map(row -> row.atMax().unitPrice())
                .max(BigDecimal::compareTo)
                .orElseThrow();
    }

    /**
     * Reads the shipped {@code weights.yml} out of the bukkit module's resources.
     *
     * <p>Parsed with a regex rather than a YAML library because this module deliberately
     * has no dependencies, and the generated file's one-line-per-entry shape is fixed by
     * {@code scripts/gen-weights.ps1}.
     */
    private static WeightTable shippedWeights() {
        java.nio.file.Path path = java.nio.file.Path.of(
                "..", "gardenshop-bukkit", "src", "main", "resources", "weights.yml");
        if (!java.nio.file.Files.exists(path)) {
            // Falling back keeps the test meaningful if the modules are ever split apart.
            return WeightTable.empty();
        }

        Map<String, WeightRange> ranges = new HashMap<>();
        try (InputStream stream = java.nio.file.Files.newInputStream(path);
             Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8)) {
            while (scanner.hasNextLine()) {
                Matcher matcher = WEIGHT_ENTRY.matcher(scanner.nextLine());
                if (matcher.matches()) {
                    ranges.put(matcher.group(1), new WeightRange(
                            Double.parseDouble(matcher.group(2)),
                            Double.parseDouble(matcher.group(3))));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(ranges).as("weights.yml should hold all 180 ranges").hasSize(180);
        return new WeightTable(ranges);
    }

    @Test
    @DisplayName("the shipped weights.yml is complete and monotonic across variants")
    void shippedWeightsAreCoherent() {
        DropRegistry registry = registry();

        for (Species species : registry.species()) {
            double plainMax = 0;
            double rainbowMax = 0;
            for (DropDefinition definition : registry.all()) {
                if (!definition.species().id().equals(species.id())) {
                    continue;
                }
                if (definition.variant() == Variant.NORMAL) {
                    plainMax = Math.max(plainMax, definition.weightRange().maxKg());
                } else if (definition.variant() == Variant.RAINBOW) {
                    rainbowMax = Math.max(rainbowMax, definition.weightRange().maxKg());
                }
            }
            assertThat(rainbowMax)
                    .as("%s: a Rainbow drop should out-weigh every plain one", species.id())
                    .isGreaterThan(plainMax);
        }
    }
}
