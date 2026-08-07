package dev.lk.gardenshop.core.config;

/**
 * Turns a pixel offset into the characters that produce it.
 *
 * <p>The resource pack's {@code gui.json} declares sixteen zero-width glyphs whose only effect is to
 * advance the text cursor: {@code U+E9B0}–{@code U+E9B7} by +1, +2, +4 … +128 pixels and
 * {@code U+E9B8}–{@code U+E9BF} by the same amounts negative. Any offset in ±255 is therefore a
 * short string of them, chosen by binary decomposition — 7 px is {@code +4 +2 +1}, three characters.
 *
 * <p>This is what makes the menu backdrop positionable from {@code config.yml}: moving it sideways
 * changes the string the plugin builds, not the pack, so it needs no re-zip and no new SHA-1.
 *
 * <p>Lives in the core module and not in the Bukkit layer because it is pure arithmetic over
 * codepoints, and being here means it can be tested without a server.
 */
public final class GlyphOffset {

    /** {@code U+E9B0} is +1 px; each following codepoint doubles, through {@code U+E9B7} = +128. */
    public static final char POSITIVE_BASE = 0xE9B0;

    /** {@code U+E9B8} is −1 px; each following codepoint doubles, through {@code U+E9BF} = −128. */
    public static final char NEGATIVE_BASE = 0xE9B8;

    /** Eight powers of two, 1 through 128, so ±255 is the widest representable offset. */
    public static final int STEPS = 8;
    public static final int MAX_PIXELS = (1 << STEPS) - 1;

    private GlyphOffset() {
    }

    /**
     * The shortest character sequence advancing the cursor by {@code pixels}.
     *
     * @param pixels signed offset; clamped to ±{@value #MAX_PIXELS}, which is far wider than any
     *               chest window and so only reachable by a typo in config
     * @return the characters, or an empty string for no offset
     */
    public static String of(int pixels) {
        if (pixels == 0) {
            return "";
        }

        boolean negative = pixels < 0;
        int magnitude = Math.min(Math.abs(pixels), MAX_PIXELS);
        char base = negative ? NEGATIVE_BASE : POSITIVE_BASE;

        StringBuilder out = new StringBuilder(STEPS);
        // Largest step first, so the result reads the way a human would write it.
        for (int step = STEPS - 1; step >= 0; step--) {
            int weight = 1 << step;
            if (magnitude >= weight) {
                magnitude -= weight;
                out.append((char) (base + step));
            }
        }
        return out.toString();
    }

    /** Whether a character is one of the pack's advance glyphs. */
    public static boolean isOffsetGlyph(char c) {
        return c >= POSITIVE_BASE && c < NEGATIVE_BASE + STEPS;
    }
}
