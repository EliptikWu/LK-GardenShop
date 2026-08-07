package dev.lk.gardenshop.core.config;

import dev.lk.gardenshop.core.TestFixtures;
import dev.lk.gardenshop.core.domain.WeightBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeightBandTableTest {

    private static WeightBandTable bands(boolean interpolate) {
        return new WeightBandTable(BandMode.RATIO, interpolate, TestFixtures.ratioBands().bands());
    }

    @Nested
    @DisplayName("band selection")
    class Selection {

        @Test
        @DisplayName("a weight lands in the first band whose upper bound covers it")
        void picksTheRightBand() {
            WeightBandTable table = bands(false);

            assertThat(table.resolve(0.10).bandId()).isEqualTo("runt");
            assertThat(table.resolve(0.85).bandId()).as("upper bound is inclusive").isEqualTo("runt");
            assertThat(table.resolve(0.86).bandId()).isEqualTo("normal");
            assertThat(table.resolve(1.30).bandId()).isEqualTo("normal");
            assertThat(table.resolve(2.20).bandId()).isEqualTo("big");
            assertThat(table.resolve(8.46).bandId()).as("heaviest reachable ratio").isEqualTo("mythic");
        }

        @Test
        @DisplayName("anything past the top of the ladder clamps to the last band")
        void clampsAboveTheLadder() {
            WeightBandTable table = bands(true);
            BandMatch beyond = table.resolve(50_000.0);

            assertThat(beyond.bandId()).isEqualTo("mythic");
            assertThat(beyond.multiplier()).isEqualByComparingTo(new BigDecimal("30.0"));
        }

        @Test
        @DisplayName("RATIO divides by base weight, ABSOLUTE takes kilograms as-is")
        void modeDecidesTheInput() {
            WeightBandTable ratio = new WeightBandTable(BandMode.RATIO, false, TestFixtures.ratioBands().bands());
            WeightBandTable absolute = new WeightBandTable(BandMode.ABSOLUTE, false, TestFixtures.ratioBands().bands());

            assertThat(ratio.valueFor(0.95, 0.475)).isEqualTo(2.0);
            assertThat(absolute.valueFor(0.95, 0.475)).isEqualTo(0.95);
        }
    }

    @Nested
    @DisplayName("without interpolation")
    class Flat {

        @Test
        @DisplayName("every weight in a band earns exactly that band's multiplier")
        void flatWithinBand() {
            WeightBandTable table = bands(false);

            assertThat(table.resolve(0.90).multiplier()).isEqualByComparingTo(new BigDecimal("1.0"));
            assertThat(table.resolve(1.29).multiplier()).isEqualByComparingTo(new BigDecimal("1.0"));
        }

        @Test
        @DisplayName("boundaries are cliffs — this is the behaviour interpolation exists to fix")
        void cliffsAtBoundaries() {
            WeightBandTable table = bands(false);

            BigDecimal justBelow = table.resolve(1.30).multiplier();
            BigDecimal justAbove = table.resolve(1.31).multiplier();

            // 0.01 more weight more than doubles the payout.
            assertThat(justAbove).isGreaterThan(justBelow.multiply(new BigDecimal("2")));
        }
    }

    @Nested
    @DisplayName("with interpolation")
    class Interpolated {

        @Test
        @DisplayName("the first band is flat, so runts are still worth something")
        void firstBandIsFlat() {
            WeightBandTable table = bands(true);

            assertThat(table.resolve(0.01).multiplier()).isEqualByComparingTo(new BigDecimal("0.6"));
            assertThat(table.resolve(0.85).multiplier()).isEqualByComparingTo(new BigDecimal("0.6"));
        }

        @Test
        @DisplayName("a band's declared multiplier is earned at its upper edge")
        void declaredMultiplierAtUpperEdge() {
            WeightBandTable table = bands(true);

            assertThat(table.resolve(1.30).multiplier()).isEqualByComparingTo(new BigDecimal("1.0"));
            assertThat(table.resolve(2.20).multiplier()).isEqualByComparingTo(new BigDecimal("2.2"));
            assertThat(table.resolve(9.00).multiplier()).isEqualByComparingTo(new BigDecimal("30.0"));
        }

        @Test
        @DisplayName("the curve is continuous: crossing a boundary is not a jump")
        void continuousAcrossBoundaries() {
            WeightBandTable table = bands(true);

            for (double edge : new double[]{1.30, 2.20, 4.00, 6.50}) {
                BigDecimal at = table.resolve(edge).multiplier();
                BigDecimal justAfter = table.resolve(edge + 1e-6).multiplier();

                assertThat(justAfter.subtract(at).abs())
                        .as("discontinuity at ratio %s", edge)
                        .isLessThan(new BigDecimal("0.001"));
            }
        }

        @Test
        @DisplayName("heavier is never worth less, anywhere on the ladder")
        void monotonicallyNonDecreasing() {
            WeightBandTable table = bands(true);
            BigDecimal previous = BigDecimal.ZERO;

            for (double ratio = 0.01; ratio <= 12.0; ratio += 0.01) {
                BigDecimal current = table.resolve(ratio).multiplier();
                assertThat(current)
                        .as("multiplier dropped at ratio %.2f", ratio)
                        .isGreaterThanOrEqualTo(previous);
                previous = current;
            }
        }

        @Test
        @DisplayName("mid-band weights beat low-band weights, so the middle of a range matters")
        void middleOfBandIsRewarded() {
            WeightBandTable table = bands(true);

            assertThat(table.resolve(1.99).multiplier())
                    .isGreaterThan(table.resolve(1.51).multiplier());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("bands must be listed in ascending order")
        void rejectsDescendingBands() {
            List<WeightBand> descending = List.of(
                    new WeightBand("big", 2.0, BigDecimal.ONE, "Big"),
                    new WeightBand("small", 1.0, BigDecimal.ONE, "Small"));

            assertThat(WeightBandTable.validate(descending))
                    .anyMatch(problem -> problem.contains("ascending"));
            assertThatThrownBy(() -> new WeightBandTable(BandMode.RATIO, true, descending))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("duplicate band ids are rejected")
        void rejectsDuplicateIds() {
            List<WeightBand> duplicated = List.of(
                    new WeightBand("same", 1.0, BigDecimal.ONE, "A"),
                    new WeightBand("same", 2.0, BigDecimal.ONE, "B"));

            assertThat(WeightBandTable.validate(duplicated))
                    .anyMatch(problem -> problem.contains("duplicate"));
        }

        @Test
        @DisplayName("an empty ladder is rejected")
        void rejectsEmpty() {
            assertThat(WeightBandTable.validate(List.of()))
                    .anyMatch(problem -> problem.contains("at least one band"));
        }

        @Test
        @DisplayName("validate() reports without throwing, so a reload can list every problem")
        void reportsInsteadOfThrowing() {
            assertThat(WeightBandTable.validate(TestFixtures.ratioBands().bands())).isEmpty();
        }
    }
}
