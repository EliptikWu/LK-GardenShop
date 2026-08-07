package dev.lk.gardenshop.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that keeps the plugin usable without a resource pack.
 *
 * <p>Getting this wrong has a specific, ugly symptom: a player without the pack sees the shop
 * backdrop as a missing-character box and every custom button as an identical blank sheet of paper.
 * The truth table below is what prevents that.
 */
class MenuStyleTest {

    private static final UUID WITH_PACK = UUID.randomUUID();
    private static final UUID WITHOUT_PACK = UUID.randomUUID();

    private static final Predicate<UUID> LOADED = Set.of(WITH_PACK)::contains;

    @Test
    @DisplayName("installed: false forces PLAIN, whatever the style says")
    void notInstalledOverridesEverything() {
        // The single switch for "this server ships no pack". It has to beat the style, or an
        // owner who set it would still get broken art.
        for (MenuStyle configured : MenuStyle.values()) {
            assertThat(MenuStyle.effective(false, configured))
                    .as("style %s with no pack installed", configured)
                    .isEqualTo(MenuStyle.PLAIN);
        }
    }

    @Test
    @DisplayName("with the pack installed, the configured style is honoured as-is")
    void installedHonoursConfig() {
        for (MenuStyle configured : MenuStyle.values()) {
            assertThat(MenuStyle.effective(true, configured)).isEqualTo(configured);
        }
    }

    @Test
    @DisplayName("STYLED draws art for everyone, PLAIN for nobody")
    void absoluteStyles() {
        assertThat(MenuStyle.STYLED.plainFor(WITH_PACK, LOADED)).isFalse();
        assertThat(MenuStyle.STYLED.plainFor(WITHOUT_PACK, LOADED))
                .as("STYLED assumes everyone has the pack, even those who do not")
                .isFalse();

        assertThat(MenuStyle.PLAIN.plainFor(WITH_PACK, LOADED)).isTrue();
        assertThat(MenuStyle.PLAIN.plainFor(WITHOUT_PACK, LOADED)).isTrue();
    }

    @Test
    @DisplayName("AUTO decides per player, from whether they actually loaded our pack")
    void autoIsPerPlayer() {
        assertThat(MenuStyle.AUTO.plainFor(WITH_PACK, LOADED)).isFalse();
        assertThat(MenuStyle.AUTO.plainFor(WITHOUT_PACK, LOADED)).isTrue();
    }

    @Test
    @DisplayName("an unrecognised style falls back to STYLED rather than refusing to load")
    void unknownIdIsLenient() {
        assertThat(MenuStyle.byId("nonsense")).isEqualTo(MenuStyle.STYLED);
        assertThat(MenuStyle.byId("")).isEqualTo(MenuStyle.STYLED);
        assertThat(MenuStyle.byId(null)).isEqualTo(MenuStyle.STYLED);
    }

    @Test
    @DisplayName("strict parsing exists so the loader can report a typo instead of guessing")
    void strictParseRejectsUnknown() {
        assertThat(MenuStyle.parse("styled")).contains(MenuStyle.STYLED);
        assertThat(MenuStyle.parse("  PLAIN ")).contains(MenuStyle.PLAIN);
        assertThat(MenuStyle.parse("auto")).contains(MenuStyle.AUTO);
        assertThat(MenuStyle.parse("stylish")).isEmpty();
        assertThat(MenuStyle.parse(null)).isEmpty();
    }
}
