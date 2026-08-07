package dev.lk.gardenshop.core.config;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a menu's buttons sit and what they look like, from {@code gui.yml}.
 *
 * <p>Layout lives here; wording lives in {@code messages.yml}. Splitting them that way
 * means a translator never has to touch slot numbers, and someone rearranging a menu never
 * has to re-type its text.
 *
 * @param rows      chest rows, 1–6
 * @param slots     button id → slot index
 * @param icons     button id → how it looks, in both pack and plain styles
 * @param filler    material for empty slots, or blank to leave them empty
 * @param glyph     codepoint of the backdrop glyph in the {@code minecraft:gui} font, or 0 for a
 *                  menu with no art of its own
 * @param glyphXOffset horizontal nudge in pixels applied before the glyph. −48 centres a 256-wide
 *                  canvas on the 176-wide window; tune it here rather than in the pack, because
 *                  changing the pack means re-zipping and a new hash
 */
public record MenuLayout(
        int rows,
        Map<String, Integer> slots,
        Map<String, IconSpec> icons,
        String filler,
        char glyph,
        int glyphXOffset
) {

    /** Chest inventories are nine wide. */
    public static final int COLUMNS = 9;
    public static final int MAX_ROWS = 6;

    /** {@code glyph} value meaning "this menu has no backdrop art". */
    public static final char NO_GLYPH = '\0';

    public MenuLayout {
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(icons, "icons");
        filler = filler == null ? "" : filler.trim();

        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException("rows must be between 1 and " + MAX_ROWS + ", got " + rows);
        }

        int size = rows * COLUMNS;
        for (Map.Entry<String, Integer> entry : slots.entrySet()) {
            int slot = entry.getValue();
            if (slot < 0 || slot >= size) {
                throw new IllegalArgumentException("button '" + entry.getKey() + "' sits at slot " + slot
                        + ", outside a " + rows + "-row menu (0-" + (size - 1) + ")");
            }
        }
        slots = Map.copyOf(slots);
        icons = Map.copyOf(icons);
    }

    public int size() {
        return rows * COLUMNS;
    }

    public Optional<Integer> slot(String button) {
        return Optional.ofNullable(slots.get(button));
    }

    /**
     * How a button looks, falling back to a plain vanilla item when {@code gui.yml} says nothing
     * about it. A button with no entry still renders — as {@code fallback} — rather than vanishing.
     */
    public IconSpec icon(String button, String fallback) {
        IconSpec configured = icons.get(button);
        return configured == null || configured.isBlank() ? IconSpec.of(fallback) : configured;
    }

    public boolean hasFiller() {
        return !filler.isEmpty();
    }

    /** Whether this menu draws a backdrop from the resource pack. */
    public boolean hasGlyph() {
        return glyph != NO_GLYPH;
    }
}
