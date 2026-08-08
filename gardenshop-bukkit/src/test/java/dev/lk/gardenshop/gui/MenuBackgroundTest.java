package dev.lk.gardenshop.gui;

import dev.lk.gardenshop.core.config.GlyphOffset;
import dev.lk.gardenshop.core.config.GuiSettings;
import dev.lk.gardenshop.core.config.IconSpec;
import dev.lk.gardenshop.core.config.MenuLayout;
import dev.lk.gardenshop.util.Text;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the shop backdrop gets into a menu's title.
 *
 * <p>The pixel arithmetic here is the kind that produces no error message when it is wrong — the art
 * simply sits somewhere unintended, or a title renders off-screen — so it is pinned down rather than
 * eyeballed.
 */
class MenuBackgroundTest {

    /** The window a 6-row chest occupies, in GUI pixels. */
    private static final int WINDOW_WIDTH = 176;

    /** Where a title's cursor starts, from vanilla's {@code titleLabelX}. */
    private static final int TITLE_X = 8;

    /**
     * The advance of the shop glyph.
     *
     * <p>Minecraft measures a bitmap glyph from its <em>content</em>, not its canvas:
     * {@code advance = (rightmostOpaqueColumn + 1) * scale + 1}. The art in
     * {@code garden_shop_gui.png} reaches column 253 and renders 1:1, so 255.
     */
    private static final int GLYPH_ADVANCE = 255;

    /** The x of the first column of slot cells drawn in the art. */
    private static final int ART_FIRST_COLUMN = 47;

    /** Where a window's first slot actually is, from vanilla's slot layout. */
    private static final int WINDOW_FIRST_SLOT_X = 8;

    private static MenuLayout withGlyph() {
        return new MenuLayout(6, Map.of(GuiSettings.BUTTON_SELL_ALL, 40),
                Map.<String, IconSpec>of(), "", GuiSettings.GLYPH_SHOP,
                GuiSettings.GLYPH_X_ALIGNED);
    }

    private static MenuLayout withoutGlyph() {
        return new MenuLayout(3, Map.of(GuiSettings.BUTTON_CONFIRM, 11),
                Map.<String, IconSpec>of(), "GRAY_STAINED_GLASS_PANE", MenuLayout.NO_GLYPH, 0);
    }

    private static final Component LABEL = Component.text("Garden Shop");

    @Test
    @DisplayName("a styled menu with artwork emits the glyph in the pack's font")
    void styledEmitsTheGlyph() {
        Component title = MenuBackground.title(withGlyph(), LABEL, false);

        assertThat(Text.plain(title))
                .as("the backdrop codepoint must be in the title, or nothing draws")
                .contains(String.valueOf(GuiSettings.GLYPH_SHOP));
        assertThat(title.style().font())
                .as("the glyph is meaningless in any other font")
                .isEqualTo(Key.key("minecraft", "gui"));
    }

    @Test
    @DisplayName("the artwork is white, or the client tints it dark grey")
    void artworkIsWhite() {
        Component title = MenuBackground.title(withGlyph(), LABEL, false);

        // Minecraft multiplies a bitmap glyph by the current text colour, and an inventory title
        // inherits #404040. Without this the shop front renders washed out and muddy -- which looks
        // like a badly exported PNG, so the pack gets blamed for a bug that lives here.
        assertThat(title.style().color())
                .as("an untinted bitmap needs pure white, which multiplies by one")
                .isEqualTo(NamedTextColor.WHITE);
    }

    @Test
    @DisplayName("the label is NOT appended after the glyph - it would land off-screen")
    void labelIsNotAppendedAfterArt() {
        // The bug this guards: a bitmap glyph advances the cursor by its own width. Appending the
        // label puts it at TITLE_X + offset + advance, which is past the window's right edge, so
        // the title silently vanished while the art looked fine.
        int labelStart = TITLE_X + GuiSettings.GLYPH_X_ALIGNED + GLYPH_ADVANCE;
        assertThat(labelStart)
                .as("if this were inside the window, appending the label would be safe")
                .isGreaterThan(WINDOW_WIDTH);

        assertThat(Text.plain(MenuBackground.title(withGlyph(), LABEL, false)))
                .doesNotContain("Garden Shop");
    }

    @Test
    @DisplayName("the offset lands the art's drawn slot cells on the window's real ones")
    void offsetAlignsTheDrawnGrid() {
        // Not centring, which is what this used to do -- alignment. The art draws its own cells, so
        // the only correct offset is the one that puts the first drawn column on the first real
        // slot. Off by a pixel and every item in the menu sits off-centre in its own cell, which
        // reads as sloppy art rather than as arithmetic.
        int canvasLeftEdge = TITLE_X + GuiSettings.GLYPH_X_ALIGNED;

        assertThat(canvasLeftEdge + ART_FIRST_COLUMN)
                .as("the art's first drawn column must land on the window's first slot")
                .isEqualTo(WINDOW_FIRST_SLOT_X);
    }

    @Test
    @DisplayName("the offset glyphs come before the artwork, or the nudge does nothing")
    void offsetPrecedesTheGlyph() {
        String plain = Text.plain(MenuBackground.title(withGlyph(), LABEL, false));

        int glyphAt = plain.indexOf(GuiSettings.GLYPH_SHOP);
        assertThat(glyphAt).as("glyph missing").isNotNegative();
        assertThat(glyphAt).as("advance characters must precede the art").isPositive();

        for (int i = 0; i < glyphAt; i++) {
            assertThat(GlyphOffset.isOffsetGlyph(plain.charAt(i)))
                    .as("character %d before the art is not an advance glyph", i)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a plain render is the label alone, with no private-use codepoints")
    void plainIsJustTheLabel() {
        Component title = MenuBackground.title(withGlyph(), LABEL, true);

        assertThat(Text.plain(title)).isEqualTo("Garden Shop");
        // A leaked glyph here is exactly the missing-character box the plain style exists to avoid.
        assertThat(Text.plain(title).chars())
                .noneMatch(c -> c >= 0xE000 && c <= 0xF8FF);
    }

    @Test
    @DisplayName("a menu with no artwork keeps its text title in either style")
    void noGlyphAlwaysShowsText() {
        assertThat(Text.plain(MenuBackground.title(withoutGlyph(), LABEL, false)))
                .isEqualTo("Garden Shop");
        assertThat(Text.plain(MenuBackground.title(withoutGlyph(), LABEL, true)))
                .isEqualTo("Garden Shop");
    }

    @Test
    @DisplayName("artwork is never italic, which inventory titles otherwise inherit")
    void artIsNotItalic() {
        Component title = MenuBackground.title(withGlyph(), LABEL, false);

        assertThat(title.style().decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC))
                .isEqualTo(net.kyori.adventure.text.format.TextDecoration.State.FALSE);
    }
}
