package dev.lk.gardenshop.core.registry;

import dev.lk.gardenshop.core.TestFixtures;
import dev.lk.gardenshop.core.config.WeightTable;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.Variant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The third identification route: resolving an item from an adapter id.
 *
 * <p>The stakes are asymmetric here. A miss makes an item unsellable, which a player reports within
 * the hour. A <em>wrong hit</em> pays out for the wrong crop — silently, at the wrong price, and
 * nobody notices until the economy is skewed. Most of what follows guards the second kind.
 */
class AdapterIdLookupTest {

    /** The plain drop of Odre: NORMAL variant, no mutations, token 'NC'. */
    private static final String PLAIN_ODRE = "growGardenNCDrop";

    private static DropRegistry registryWith(Species... species) {
        return MythicTypeComposer.withDefaultPattern()
                .buildRegistry(List.of(species), WeightTable.empty());
    }

    private static Species odreWithExtras(String... extraIds) {
        Species odre = TestFixtures.odre();
        return new Species(odre.id(), odre.displayName(), odre.mythicToken(), odre.baseValue(),
                odre.baseWeightKg(), odre.baseRange(), List.of(extraIds));
    }

    // ------------------------------------------------------- ids from Mythic sources

    @Test
    @DisplayName("a Mythic-sourced id resolves without being declared anywhere")
    void mythicPrefixResolves() {
        DropRegistry registry = registryWith(TestFixtures.odre());

        // The part after the prefix IS the Mythic type name, which the registry already indexes.
        // That is the whole reason this route needs no configuration for the shipped pack.
        for (String id : List.of("mythic:" + PLAIN_ODRE, "mythicmobs:" + PLAIN_ODRE,
                "mmobs:" + PLAIN_ODRE, "mythicm:" + PLAIN_ODRE,
                "crucible:" + PLAIN_ODRE, "mythicc:" + PLAIN_ODRE,
                "mcrucible:" + PLAIN_ODRE, "mythiccrucible:" + PLAIN_ODRE)) {
            assertThat(registry.findByAdapterId(id))
                    .as("adapter alias in '%s'", id)
                    .isPresent();
        }
    }

    @Test
    @DisplayName("case does not matter, because Mythic is inconsistent about it")
    void lookupIsCaseInsensitive() {
        DropRegistry registry = registryWith(TestFixtures.odre());

        assertThat(registry.findByAdapterId("MYTHIC:GROWGARDENNCDROP")).isPresent();
        assertThat(registry.findByAdapterId("Mythic:growgardenncdrop")).isPresent();
    }

    @Test
    @DisplayName("a NON-Mythic prefix is never stripped, so 'mc:paper' cannot become a crop")
    void otherPrefixesAreNotStripped() {
        DropRegistry registry = registryWith(TestFixtures.odre());

        // This is the guard the allowlist exists for. Our crops are Mythic items built on paper, so
        // every one of them also answers to 'mc:paper'; a rule that stripped any prefix would let
        // the vanilla id of a blank sheet of paper resolve to a harvest.
        assertThat(registry.findByAdapterId("mc:" + PLAIN_ODRE)).isEmpty();
        assertThat(registry.findByAdapterId("ia:" + PLAIN_ODRE)).isEmpty();
        assertThat(registry.findByAdapterId("nx:" + PLAIN_ODRE)).isEmpty();
        assertThat(registry.findByAdapterId("mc:paper")).isEmpty();
    }

    @Test
    @DisplayName("malformed ids miss quietly instead of throwing in the sell loop")
    void malformedIdsMiss() {
        DropRegistry registry = registryWith(TestFixtures.odre());

        assertThat(registry.findByAdapterId(null)).isEmpty();
        assertThat(registry.findByAdapterId("")).isEmpty();
        assertThat(registry.findByAdapterId("   ")).isEmpty();
        assertThat(registry.findByAdapterId(PLAIN_ODRE)).as("no prefix at all").isEmpty();
        assertThat(registry.findByAdapterId("mythic:")).as("prefix with nothing after it").isEmpty();
        assertThat(registry.findByAdapterId(":" + PLAIN_ODRE)).as("empty prefix").isEmpty();
        assertThat(registry.findByAdapterId("mythic:no_such_crop")).isEmpty();
    }

    // --------------------------------------------------------------- ids from crops.yml

    @Test
    @DisplayName("an extra-id from another plugin resolves to that crop's PLAIN drop")
    void extraIdsResolveToThePlainDrop() {
        DropRegistry registry = registryWith(odreWithExtras("ia:mygarden:tomato", "nx:tomato"));

        assertThat(registry.findByAdapterId("ia:mygarden:tomato"))
                .as("a three-segment ItemsAdder id must match whole, not by prefix stripping")
                .isPresent()
                .get()
                .satisfies(drop -> {
                    assertThat(drop.species().id()).isEqualTo("odre");
                    // An item from another plugin carries no variant or mutation information, so
                    // anything richer than the plain drop would be invented data.
                    assertThat(drop.variant()).isEqualTo(Variant.NORMAL);
                    assertThat(drop.mutations()).isEmpty();
                });
        assertThat(registry.findByAdapterId("nx:tomato")).isPresent();
    }

    @Test
    @DisplayName("extra-ids are normalised, so stray case and whitespace still match")
    void extraIdsAreNormalised() {
        DropRegistry registry = registryWith(odreWithExtras("  IA:MyGarden:Tomato  "));

        assertThat(registry.findByAdapterId("ia:mygarden:tomato")).isPresent();
    }

    @Test
    @DisplayName("an extra-id cannot shadow one of the pack's own drops")
    void extraIdCannotShadowARealDrop() {
        // Aliases are consulted before the prefix rule, so without this check listing a real Mythic
        // id under the wrong crop would re-point that drop and pay out the wrong plant's price.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registryWith(TestFixtures.odre(), chilliWithExtras("mythic:" + PLAIN_ODRE)))
                .withMessageContaining("already names one of this pack's own drops");
    }

    @Test
    @DisplayName("the same extra-id under two crops is rejected rather than silently picking one")
    void duplicateExtraIdIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registryWith(odreWithExtras("ia:x:y"), chilliWithExtras("ia:x:y")))
                .withMessageContaining("declared under two different crops");
    }

    @Test
    @DisplayName("the same extra-id twice under ONE crop is harmless")
    void duplicateWithinOneCropIsFine() {
        DropRegistry registry = registryWith(odreWithExtras("ia:x:y", "ia:x:y", "IA:X:Y"));

        assertThat(registry.findByAdapterId("ia:x:y")).isPresent();
    }

    @Test
    @DisplayName("an alias pointing at a type that does not exist is a build error, not a miss")
    void aliasToUnknownTypeThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DropRegistry(
                        registryWith(TestFixtures.odre()).all(), Map.of("ia:x:y", "nonexistent")))
                .withMessageContaining("unknown drop type");
    }

    // ------------------------------------------------------------------------ regression

    @Test
    @DisplayName("plain type lookup is untouched by any of this")
    void typeLookupStillWorks() {
        DropRegistry registry = registryWith(odreWithExtras("ia:x:y"));

        assertThat(registry.find(PLAIN_ODRE)).isPresent();
        assertThat(registry.size()).isEqualTo(30);
        assertThat(registry.findByAdapterId("ia:x:y").orElseThrow().mythicType())
                .isEqualTo(registry.find(PLAIN_ODRE).orElseThrow().mythicType());
    }

    private static Species chilliWithExtras(String... extraIds) {
        Species chilli = TestFixtures.chilli();
        return new Species(chilli.id(), chilli.displayName(), chilli.mythicToken(),
                chilli.baseValue(), chilli.baseWeightKg(), chilli.baseRange(), List.of(extraIds));
    }
}
