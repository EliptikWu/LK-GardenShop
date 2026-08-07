package dev.lk.gardenshop.gui;

import dev.lk.gardenshop.core.config.GlyphOffset;
import dev.lk.gardenshop.core.config.MenuLayout;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Builds a menu's title so that the resource pack's artwork appears as its backdrop.
 *
 * <h2>Why the title</h2>
 * There is no vanilla way to give one container its own background texture. But a glyph placed in an
 * inventory's <b>title</b> is drawn <em>behind the items and in front of the container background</em>
 * — exactly the layer a backdrop needs — and it costs no client mod, no NMS and no packet work. So
 * the pack declares the artwork as a font glyph and this class writes it into the title.
 *
 * <p>The horizontal position comes from {@link GlyphOffset}: a run of zero-width advance characters
 * before the glyph. Keeping that in the plugin rather than in the pack means nudging the art sideways
 * is a config edit and a {@code /gs reload}, not a re-zip and a new SHA-1 for every client to
 * re-download.
 *
 * <h2>The fallback is not decoration</h2>
 * When the menu is drawn plain — no pack on this server, or this player declined it — the title is
 * the label alone. Emitting the glyph anyway would show a missing-character box where the shop front
 * should be, which is worse than a plain chest.
 */
public final class MenuBackground {

    /** The pack's font. Its glyphs are meaningless in any other font. */
    private static final Key GUI_FONT = Key.key("minecraft", "gui");

    private MenuBackground() {
    }

    /**
     * The title for a menu.
     *
     * <h2>The label is dropped when there is artwork, and that is not a shortcut</h2>
     * A bitmap glyph advances the text cursor by its own rendered width. Minecraft computes that
     * from the art's content, not the canvas: {@code shop_gui.png} has pixels out to column 230, so
     * its advance is 232 px. The title cursor starts at x=8, the centring nudge takes it to −40, and
     * after the glyph it sits at <b>192 px</b> — past the right edge of a 176 px window. Anything
     * appended there is drawn off-screen.
     *
     * <p>Rewinding with another 232 px of negative space would put the label back, but the label
     * would then sit <em>on top of</em> the artwork, and the artwork already carries its own signage
     * — the shop texture has "SHOP" painted on the sign. So a menu with a backdrop shows the
     * backdrop, and a menu without one shows its text.
     *
     * @param layout the menu's layout, carrying its glyph and horizontal nudge
     * @param label  the human-readable name, used when there is no artwork to speak for it
     * @param plain  whether this render has no pack art available
     */
    public static Component title(MenuLayout layout, Component label, boolean plain) {
        if (plain || !layout.hasGlyph()) {
            return label;
        }
        return glyph(layout);
    }

    /**
     * The backdrop glyph on its own, positioned.
     *
     * <p>Italics are switched off explicitly: the artwork is a glyph like any other, and a slanted
     * shop front is a surprising way to discover that inventory titles inherit styling.
     */
    private static Component glyph(MenuLayout layout) {
        String positioned = GlyphOffset.of(layout.glyphXOffset()) + layout.glyph();
        return Component.text(positioned)
                .font(GUI_FONT)
                .decoration(TextDecoration.ITALIC, false);
    }
}
