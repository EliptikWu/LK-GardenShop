package dev.lk.gardenshop.core.config;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Whether a menu is drawn with the resource pack's art, from {@code config.yml → menu.style}.
 *
 * <p>This exists because every visual in the shop menu — the backdrop glyph and any
 * CustomModelData icon — lives in the resource pack. A player without it sees missing-glyph
 * boxes where the stand should be and identical blank sheets of paper where the buttons should
 * be. <b>The plugin must never need the pack to be usable; the pack is cosmetic.</b>
 */
public enum MenuStyle {

    /** Always use the pack art. Assumes every player has it. */
    STYLED,

    /** Never use it: vanilla item icons and a plain text title. For servers that ship no pack. */
    PLAIN,

    /**
     * Per player, based on whether they loaded <em>our</em> pack. When we do not send it
     * ourselves — another plugin serves it, or sending is off — there is no reliable signal and
     * {@link #STYLED} is assumed.
     */
    AUTO;

    /**
     * The style actually in force.
     *
     * <p>{@code resource-pack.installed: false} declares that players do not have the pack, no
     * matter who would serve it. That forces {@link #PLAIN} and ignores the configured style: it
     * is the single switch for "this server ships no resource pack".
     */
    public static MenuStyle effective(boolean packInstalled, MenuStyle configured) {
        return packInstalled ? configured : PLAIN;
    }

    /** Unknown or absent value → {@link #STYLED}. */
    public static MenuStyle byId(String id) {
        if (id == null || id.isBlank()) {
            return STYLED;
        }
        return switch (id.trim().toLowerCase(Locale.ROOT)) {
            case "plain" -> PLAIN;
            case "auto" -> AUTO;
            default -> STYLED;
        };
    }

    /** Strict parse, for reporting a typo instead of silently defaulting. */
    public static Optional<MenuStyle> parse(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return switch (id.trim().toLowerCase(Locale.ROOT)) {
            case "styled" -> Optional.of(STYLED);
            case "plain" -> Optional.of(PLAIN);
            case "auto" -> Optional.of(AUTO);
            default -> Optional.empty();
        };
    }

    /**
     * Is this player drawn <em>without</em> pack art?
     *
     * @param packLoaded predicate "this player loaded our pack"; consulted only in {@link #AUTO}
     */
    public boolean plainFor(UUID player, Predicate<UUID> packLoaded) {
        return switch (this) {
            case PLAIN -> true;
            case STYLED -> false;
            case AUTO -> !packLoaded.test(player);
        };
    }
}
