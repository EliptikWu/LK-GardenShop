package dev.lk.gardenshop.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MutationStackingTest {

    private static final List<BigDecimal> TRIPLE = List.of(
            new BigDecimal("2.0"), new BigDecimal("3.0"), new BigDecimal("4.0"));

    @Test
    @DisplayName("ADDITIVE sums the bonuses above 1: 1 + 1 + 2 + 3 = 7")
    void additive() {
        assertThat(MutationStacking.ADDITIVE.combine(TRIPLE))
                .isEqualByComparingTo(new BigDecimal("7.0"));
    }

    @Test
    @DisplayName("MULTIPLICATIVE compounds: 2 x 3 x 4 = 24")
    void multiplicative() {
        assertThat(MutationStacking.MULTIPLICATIVE.combine(TRIPLE))
                .isEqualByComparingTo(new BigDecimal("24.0"));
    }

    @Test
    @DisplayName("an unmutated crop gets a neutral multiplier under either rule")
    void emptyIsNeutral() {
        assertThat(MutationStacking.ADDITIVE.combine(List.of())).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(MutationStacking.MULTIPLICATIVE.combine(List.of())).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("a single mutation is just its own multiplier")
    void singleMutation() {
        List<BigDecimal> one = List.of(new BigDecimal("2.4"));

        assertThat(MutationStacking.ADDITIVE.combine(one)).isEqualByComparingTo(new BigDecimal("2.4"));
        assertThat(MutationStacking.MULTIPLICATIVE.combine(one)).isEqualByComparingTo(new BigDecimal("2.4"));
    }

    @Test
    @DisplayName("ADDITIVE clamps at zero, so penalty multipliers cannot make a crop cost money")
    void additiveNeverGoesNegative() {
        // Three mutations each configured at 0.1 would otherwise give 1 - 2.7 = -1.7.
        List<BigDecimal> penalties = List.of(
                new BigDecimal("0.1"), new BigDecimal("0.1"), new BigDecimal("0.1"));

        assertThat(MutationStacking.ADDITIVE.combine(penalties)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("ids parse case-insensitively and reject unknown values")
    void parsing() {
        assertThat(MutationStacking.byId("additive")).contains(MutationStacking.ADDITIVE);
        assertThat(MutationStacking.byId("  MULTIPLICATIVE ")).contains(MutationStacking.MULTIPLICATIVE);
        assertThat(MutationStacking.byId("exponential")).isEmpty();
        assertThat(MutationStacking.byId(null)).isEmpty();
    }
}
