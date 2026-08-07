package dev.lk.gardenshop.core.registry;

import dev.lk.gardenshop.core.TestFixtures;
import dev.lk.gardenshop.core.domain.Mutation;
import dev.lk.gardenshop.core.domain.Variant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MythicTypeComposerTest {

    private final MythicTypeComposer composer = MythicTypeComposer.withDefaultPattern();

    @Test
    @DisplayName("derives exactly the drop types the Mythic pack declares")
    void matchesThePack() {
        // Skipped, not failed, when the extract is absent. The crop pack is not published with this
        // repository, so neither is the list of type names taken from it -- and a clean clone must
        // build green rather than fail on a fixture nobody outside can regenerate. Whoever has the
        // pack runs scripts/gen-expected-types.ps1 and gets the assertion back.
        Set<String> expected = expectedTypes().orElseGet(() -> {
            Assumptions.abort("expected-drop-types.txt is not present — run "
                    + "scripts/gen-expected-types.ps1 against your crop pack to enable this check");
            return Set.of();
        });
        Set<String> derived = composer
                .buildRegistry(TestFixtures.allSpecies(), TestFixtures.noWeights())
                .types();

        // Two-sided so neither a missing nor a spurious type slips through.
        assertThat(derived)
                .as("types the composer derives but the pack does not declare")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(derived).hasSize(180);
    }

    @Test
    @DisplayName("6 species x 3 variants x 10 mutation states = 180 definitions")
    void matrixSize() {
        DropRegistry registry = composer.buildRegistry(TestFixtures.allSpecies(), TestFixtures.noWeights());

        assertThat(MythicTypeComposer.mutationStates()).hasSize(10);
        assertThat(Variant.values()).hasSize(3);
        assertThat(registry.size()).isEqualTo(6 * 3 * 10);
    }

    @Test
    @DisplayName("mutation states are the weather power set plus each dimensional mutation alone")
    void mutationStates() {
        var states = MythicTypeComposer.mutationStates();

        assertThat(states.getFirst()).as("the plain, unmutated state comes first").isEmpty();
        assertThat(states).contains(EnumSet.of(Mutation.ICE, Mutation.RAIN, Mutation.LIGHTNING));
        assertThat(states).contains(EnumSet.of(Mutation.NETHER));
        assertThat(states).contains(EnumSet.of(Mutation.END));

        // Dimensional mutations never stack with anything.
        assertThat(states).noneMatch(state ->
                state.size() > 1 && state.stream().anyMatch(m -> !m.isStackable()));
    }

    @Test
    @DisplayName("stacked mutation tokens follow the pack's ice-rain-lightning order")
    void mutationTokenOrder() {
        // Insertion order is deliberately scrambled: the token must come from the
        // enum's ordinal order, not from how the set was built.
        Set<Mutation> scrambled = new LinkedHashSet<>();
        scrambled.add(Mutation.LIGHTNING);
        scrambled.add(Mutation.ICE);
        scrambled.add(Mutation.RAIN);

        assertThat(MythicTypeComposer.mutationToken(scrambled)).isEqualTo("IceRainLightning");
        assertThat(MythicTypeComposer.mutationToken(EnumSet.of(Mutation.RAIN, Mutation.LIGHTNING)))
                .isEqualTo("RainLightning");
        assertThat(MythicTypeComposer.mutationToken(EnumSet.of(Mutation.ICE, Mutation.RAIN)))
                .isEqualTo("IceRain");
        assertThat(MythicTypeComposer.mutationToken(EnumSet.noneOf(Mutation.class))).isEmpty();
    }

    @Test
    @DisplayName("NORMAL contributes no token, so plain drops are ...Drop not ...NormalDrop")
    void normalVariantIsInvisible() {
        assertThat(composer.compose("NC", Variant.NORMAL, EnumSet.noneOf(Mutation.class)))
                .isEqualTo("growGardenNCDrop");
        assertThat(composer.compose("Chilli", Variant.RAINBOW, EnumSet.of(Mutation.ICE, Mutation.RAIN)))
                .isEqualTo("growGardenChilliRainbowDropIceRain");
        assertThat(composer.compose("BlueBeet", Variant.GOLD, EnumSet.of(Mutation.NETHER)))
                .isEqualTo("growGardenBlueBeetGoldDropNether");
    }

    @Test
    @DisplayName("a pattern missing a placeholder is rejected at construction")
    void patternMustBeComplete() {
        assertThatThrownBy(() -> new MythicTypeComposer("growGarden{crop}Drop"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{variant}");
    }

    /** @return empty when the extract is not on the classpath, which is the case in a fresh clone */
    private static Optional<Set<String>> expectedTypes() {
        try (InputStream stream = MythicTypeComposerTest.class.getClassLoader()
                .getResourceAsStream("expected-drop-types.txt")) {
            if (stream == null) {
                return Optional.empty();
            }
            Set<String> types = new LinkedHashSet<>();
            try (Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8)) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        types.add(line);
                    }
                }
            }
            return Optional.of(types);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
