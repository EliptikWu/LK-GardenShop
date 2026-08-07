package dev.lk.gardenshop.core.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What an adapter id's prefix is allowed to mean. */
class AdapterSourcesTest {

    @Test
    @DisplayName("a vanilla prefix is recognised in every spelling the library uses")
    void vanillaPrefixes() {
        // This set is the difference between binding one custom tomato and making every sheet of
        // paper on the server sellable, so it is worth pinning rather than trusting.
        assertThat(AdapterSources.isVanilla("mc:paper")).isTrue();
        assertThat(AdapterSources.isVanilla("minecraft:paper")).isTrue();
        assertThat(AdapterSources.isVanilla("vanilla:paper")).isTrue();
        assertThat(AdapterSources.isVanilla("MC:PAPER")).isTrue();
        assertThat(AdapterSources.isVanilla("  mc:paper  ")).isTrue();

        assertThat(AdapterSources.isVanilla("ia:mygarden:tomato")).isFalse();
        assertThat(AdapterSources.isVanilla("mythic:growGardenNCDrop")).isFalse();
        assertThat(AdapterSources.isVanilla("mcrucible:thing"))
                .as("a prefix that merely starts with 'mc' is not the vanilla one")
                .isFalse();
    }

    @Test
    @DisplayName("a Mythic prefix yields the type name; anything else yields nothing")
    void mythicTypeExtraction() {
        assertThat(AdapterSources.mythicType("mythic:growGardenNCDrop")).contains("growgardenncdrop");
        assertThat(AdapterSources.mythicType("mMobs:Thing")).contains("thing");
        assertThat(AdapterSources.mythicType("mythicC:Thing")).contains("thing");
        assertThat(AdapterSources.mythicType("crucible:Thing")).contains("thing");

        assertThat(AdapterSources.mythicType("mc:growGardenNCDrop")).isEmpty();
        assertThat(AdapterSources.mythicType("ia:ns:id")).isEmpty();
        assertThat(AdapterSources.mythicType("growGardenNCDrop")).isEmpty();
        assertThat(AdapterSources.mythicType("mythic:")).isEmpty();
        assertThat(AdapterSources.mythicType(":thing")).isEmpty();
        assertThat(AdapterSources.mythicType(null)).isEmpty();
    }

    @Test
    @DisplayName("the prefix of a three-segment id is only its first segment")
    void prefixStopsAtTheFirstColon() {
        // ItemsAdder ids carry a namespace, so 'ia:mygarden:tomato' has two colons and the second
        // one is part of the id, not a separator.
        assertThat(AdapterSources.prefixOf("ia:mygarden:tomato")).contains("ia");
        assertThat(AdapterSources.prefixOf("bare")).isEmpty();
        assertThat(AdapterSources.prefixOf(":leading")).isEmpty();
        assertThat(AdapterSources.prefixOf(null)).isEmpty();
    }

    @Test
    @DisplayName("the two prefix families do not overlap")
    void familiesAreDisjoint() {
        // An id cannot be both "names a Mythic type" and "is a plain material": the first is
        // resolved automatically and the second is never bindable, so an overlap would make one
        // rule silently win over the other.
        assertThat(AdapterSources.MYTHIC).doesNotContainAnyElementsOf(AdapterSources.VANILLA);
    }
}
