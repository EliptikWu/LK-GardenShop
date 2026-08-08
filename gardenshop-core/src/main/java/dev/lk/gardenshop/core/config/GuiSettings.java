package dev.lk.gardenshop.core.config;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Every menu's layout and how they are drawn, from the bundled {@code gui.yml}.
 *
 * <p>Not extracted to the plugin folder: slot positions are tied to the backdrop texture, so moving
 * a button without redrawing the art just puts it in the wrong place. It is still read from the
 * folder if a file is dropped there by hand.
 *
 * @param enabled            whether {@code /gs} opens the menu at all; with this off the plugin is
 *                           command-only
 * @param style              whether pack art is used - see {@link MenuStyle}
 * @param confirmBulkSale    require the confirmation screen before a bulk sale. The one safety net
 *                           between a full inventory and an irreversible sale
 * @param closeMenusOnReload shut open menus during {@code /gs reload}, since the prices they are
 *                           displaying have just been replaced
 * @param sellMenu           the main shop screen, the one with the backdrop art
 * @param confirmMenu        the "are you sure" screen
 * @param priceBook          the crop list and per-crop price pages
 */
public record GuiSettings(
        boolean enabled,
        MenuStyle style,
        boolean confirmBulkSale,
        boolean closeMenusOnReload,
        MenuLayout sellMenu,
        MenuLayout confirmMenu,
        MenuLayout priceBook
) {

    // Button ids, shared by the loader, the layouts and the menus themselves.
    public static final String BUTTON_HELD = "held";
    public static final String BUTTON_SELL_HAND = "sell-hand";
    public static final String BUTTON_SELL_ALL = "sell-all";
    public static final String BUTTON_PRICES = "prices";
    public static final String BUTTON_STATS = "stats";
    public static final String BUTTON_CONFIRM = "confirm";
    public static final String BUTTON_CANCEL = "cancel";
    public static final String BUTTON_SUMMARY = "summary";
    public static final String BUTTON_BACK = "back";
    public static final String BUTTON_PREVIOUS_PAGE = "previous-page";
    public static final String BUTTON_NEXT_PAGE = "next-page";

    /**
     * Not a button: the look of a drop type the pack has drawn nothing for yet.
     *
     * <p>Listed with the buttons because it is configured the same way, in {@code price-book.icons}.
     */
    public static final String BUTTON_UNAVAILABLE = "unavailable";

    /**
     * Codepoint of the shop backdrop, as declared in the pack's
     * {@code assets/minecraft/font/gui.json}.
     *
     * <p>Written as a numeric constant rather than a character literal on purpose: a private-use
     * codepoint pasted into source is invisible in every editor and is the first thing an encoding
     * mishap destroys.
     */
    public static final char GLYPH_SHOP = 0xE901;

    /**
     * The same stand with its panel left empty, for the price book.
     *
     * <p>A separate drawing rather than the same one: the sell menu's panel has three shelves drawn
     * on it, and thirty drop icons laid over shelves they do not line up with reads as a mistake.
     */
    public static final char GLYPH_SHOP_LIST = 0xE902;

    /**
     * Where the measurement says the art belongs.
     *
     * <p>Its columns are drawn at x 47, 65, 83 … 191 — an 18px pitch, the vanilla one — while a
     * window's slots start at x 8. That is 39px of shift, and since the title's cursor starts at
     * x=8 the nudge is {@code -39 - 8 = -47}.
     *
     * <p>Kept separate from {@link #GLYPH_X_ALIGNED} so the arithmetic stays checkable after
     * somebody nudges the art by eye — which is exactly what happened.
     */
    public static final int GLYPH_X_MEASURED = -47;

    /**
     * What actually ships: the measurement, plus a pixel asked for on screen.
     *
     * <p>The vertical partner is {@code ascent: 31} in the pack's {@code font/gui.json}, which is
     * the measured 29 plus two of the same kind of adjustment. Both are documented in both places
     * because they have to agree, and neither is guessable from the other.
     */
    public static final int GLYPH_X_ALIGNED = GLYPH_X_MEASURED - 1;

    public GuiSettings {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(sellMenu, "sellMenu");
        Objects.requireNonNull(confirmMenu, "confirmMenu");
        Objects.requireNonNull(priceBook, "priceBook");
    }

    public static GuiSettings defaults() {
        return new GuiSettings(true, MenuStyle.STYLED, true, true,
                // Six rows, and not by preference: the art's drawn slot grid is a vanilla 6-row
                // chest's, so any other row count puts every item off its own drawn cell.
                //
                // The button rows land on the three shelves drawn in the panel -- rows 4, 5 and 6
                // sit at art y 88, 106 and 124, and the shelves are drawn at 86-90, 104-108 and
                // 122-126.
                new MenuLayout(6, Map.of(
                        BUTTON_STATS, 22,
                        BUTTON_HELD, 31,
                        BUTTON_SELL_HAND, 38,
                        BUTTON_SELL_ALL, 40,
                        BUTTON_PRICES, 42),
                        // No filler: the backdrop art fills the window, and a wall of glass panes
                        // would hide the very thing the pack exists to show.
                        Map.of(), "", GLYPH_SHOP, GLYPH_X_ALIGNED),
                // The confirmation screen wears the same stand, so it needs the same six rows for
                // the drawn grid to line up. Its three buttons go on the middle shelf.
                new MenuLayout(6, Map.of(
                        BUTTON_CONFIRM, 38,
                        BUTTON_SUMMARY, 40,
                        BUTTON_CANCEL, 42),
                        Map.of(), "", GLYPH_SHOP, GLYPH_X_ALIGNED),
                new MenuLayout(6, Map.of(
                        BUTTON_BACK, 49,
                        BUTTON_PREVIOUS_PAGE, 45,
                        BUTTON_NEXT_PAGE, 53),
                        Map.of(), "", GLYPH_SHOP_LIST, GLYPH_X_ALIGNED));
    }

    /** Convenience for a plain vanilla icon spec, used by the loader's defaults. */
    public static IconSpec vanilla(String material) {
        return new IconSpec(material, OptionalInt.empty(), "");
    }
}
