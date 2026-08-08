package dev.lk.gardenshop.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic behind positioning the menu backdrop.
 *
 * <p>Worth testing precisely because a wrong offset is not an error anyone sees in a log — the art
 * just sits a few pixels off, and nobody can tell whether the cause is this code, the {@code ascent}
 * in the pack, or the texture itself.
 */
class GlyphOffsetTest {

    /** Re-derives the pixel width of an offset string from the font's declared advances. */
    private static int widthOf(String offset) {
        int total = 0;
        for (char c : offset.toCharArray()) {
            assertThat(GlyphOffset.isOffsetGlyph(c))
                    .as("'%s' (U+%04X) is not one of the pack's advance glyphs", c, (int) c)
                    .isTrue();
            if (c >= GlyphOffset.NEGATIVE_BASE) {
                total -= 1 << (c - GlyphOffset.NEGATIVE_BASE);
            } else {
                total += 1 << (c - GlyphOffset.POSITIVE_BASE);
            }
        }
        return total;
    }

    @Test
    @DisplayName("no offset produces no characters")
    void zeroIsEmpty() {
        assertThat(GlyphOffset.of(0)).isEmpty();
    }

    @ParameterizedTest(name = "{0} px")
    @ValueSource(ints = {1, 2, 3, 4, 7, 8, 15, 16, 40, 48, 100, 128, 176, 255,
            -1, -2, -7, -13, -40, -48, -128, -255})
    @DisplayName("every offset round-trips to exactly the pixels asked for")
    void roundTrips(int pixels) {
        assertThat(widthOf(GlyphOffset.of(pixels))).isEqualTo(pixels);
    }

    @Test
    @DisplayName("both shop offsets compose back to exactly the pixels they name")
    void shopOffsetsComposeExactly() {
        // The composition is the part that must be exact. Whatever number the art is nudged to, the
        // advance characters written into the title have to add up to precisely it -- a pixel lost
        // here would move the art without anybody having asked.
        assertThat(widthOf(GlyphOffset.of(GuiSettings.GLYPH_X_MEASURED)))
                .isEqualTo(GuiSettings.GLYPH_X_MEASURED);
        assertThat(widthOf(GlyphOffset.of(GuiSettings.GLYPH_X_ALIGNED)))
                .isEqualTo(GuiSettings.GLYPH_X_ALIGNED);
    }

    @Test
    @DisplayName("binary decomposition, so an offset is never more characters than it needs")
    void isMinimal() {
        // 7 = 4+2+1 is three glyphs; a naive unary encoding would be seven.
        assertThat(GlyphOffset.of(7)).hasSize(3);
        assertThat(GlyphOffset.of(8)).hasSize(1);
        assertThat(GlyphOffset.of(128)).hasSize(1);
        // 255 sets all eight bits, which is the worst case.
        assertThat(GlyphOffset.of(255)).hasSize(GlyphOffset.STEPS);
        assertThat(GlyphOffset.of(-255)).hasSize(GlyphOffset.STEPS);
    }

    @Test
    @DisplayName("largest step first, so the output reads the way a human would write it")
    void largestStepFirst() {
        String seven = GlyphOffset.of(7);
        assertThat((int) seven.charAt(0)).isEqualTo(GlyphOffset.POSITIVE_BASE + 2); // +4
        assertThat((int) seven.charAt(1)).isEqualTo(GlyphOffset.POSITIVE_BASE + 1); // +2
        assertThat((int) seven.charAt(2)).isEqualTo(GlyphOffset.POSITIVE_BASE);     // +1
    }

    @Test
    @DisplayName("an absurd offset clamps instead of producing nonsense")
    void clampsBeyondRange() {
        // Only reachable by a typo in config; ±255 already dwarfs any chest window.
        assertThat(widthOf(GlyphOffset.of(10_000))).isEqualTo(GlyphOffset.MAX_PIXELS);
        assertThat(widthOf(GlyphOffset.of(-10_000))).isEqualTo(-GlyphOffset.MAX_PIXELS);
    }

    @Test
    @DisplayName("positive and negative glyph ranges do not overlap")
    void rangesAreDistinct() {
        // An overlap would make some offsets silently mean their own negation.
        assertThat(GlyphOffset.POSITIVE_BASE + GlyphOffset.STEPS)
                .isLessThanOrEqualTo(GlyphOffset.NEGATIVE_BASE);
    }
}
