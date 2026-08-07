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
     * Codepoint of the shop backdrop, as declared in the pack's
     * {@code assets/minecraft/font/gui.json}.
     *
     * <p>Written as a numeric constant rather than a character literal on purpose: a private-use
     * codepoint pasted into source is invisible in every editor and is the first thing an encoding
     * mishap destroys.
     */
    public static final char GLYPH_SHOP = 0xE901;

    /**
     * Pixels to shift left before drawing a 256-wide glyph so it centres on the 176-wide window.
     *
     * <p>The title's cursor starts at x=8, and centring a 256px canvas wants its left edge at
     * {@code (176-256)/2 = -40}, so the nudge is {@code -40 - 8 = -48}.
     */
    public static final int GLYPH_X_CENTRED_256 = -48;

    public GuiSettings {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(sellMenu, "sellMenu");
        Objects.requireNonNull(confirmMenu, "confirmMenu");
        Objects.requireNonNull(priceBook, "priceBook");
    }

    public static GuiSettings defaults() {
        return new GuiSettings(true, MenuStyle.STYLED, true, true,
                // Six rows because the art is 222px tall, which is the exact height of a 6-row
                // chest window. Fewer rows and the stand would hang below the window's edge.
                new MenuLayout(6, Map.of(
                        BUTTON_STATS, 4,
                        BUTTON_HELD, 22,
                        BUTTON_SELL_HAND, 38,
                        BUTTON_SELL_ALL, 40,
                        BUTTON_PRICES, 42),
                        // No filler: the backdrop art fills the window, and a wall of glass panes
                        // would hide the very thing the pack exists to show.
                        Map.of(), "", GLYPH_SHOP, GLYPH_X_CENTRED_256),
                new MenuLayout(3, Map.of(
                        BUTTON_CONFIRM, 11,
                        BUTTON_SUMMARY, 13,
                        BUTTON_CANCEL, 15),
                        Map.of(), "GRAY_STAINED_GLASS_PANE", MenuLayout.NO_GLYPH, 0),
                new MenuLayout(6, Map.of(
                        BUTTON_BACK, 49,
                        BUTTON_PREVIOUS_PAGE, 45,
                        BUTTON_NEXT_PAGE, 53),
                        Map.of(), "", MenuLayout.NO_GLYPH, 0));
    }

    /** Convenience for a plain vanilla icon spec, used by the loader's defaults. */
    public static IconSpec vanilla(String material) {
        return new IconSpec(material, OptionalInt.empty(), "");
    }
}
