package dev.lk.gardenshop.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Text conversion in one place.
 *
 * <p>Two formats coexist here on purpose. Player-facing messages in
 * {@code messages.yml} use MiniMessage, because they need placeholders and nesting.
 * Labels in {@code crops.yml} and {@code pricing.yml} use legacy {@code &} codes,
 * because they sit next to a Mythic pack written that way and asking the owner to
 * switch notation mid-project would be gratuitous.
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * Accepts both {@code &} and {@code §} so labels can be pasted straight out of
     * the Mythic pack, which mixes the two.
     */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private Text() {
    }

    /** Parses a MiniMessage string, e.g. a line from {@code messages.yml}. */
    public static Component mini(String input, TagResolver... resolvers) {
        return MINI.deserialize(input == null ? "" : input, resolvers);
    }

    /**
     * Parses a legacy {@code &}-coded string.
     *
     * <p>Italics are switched off explicitly: item lore and display names inherit
     * italic from vanilla, which makes every generated line look like a placeholder.
     */
    public static Component legacy(String input) {
        if (input == null) {
            return Component.empty();
        }
        return LEGACY.deserialize(input.replace('§', '&'))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Strips all formatting — used to match lore lines without caring about colour. */
    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Strips legacy colour codes from a raw string. */
    public static String stripLegacy(String input) {
        if (input == null) {
            return "";
        }
        return plain(legacy(input));
    }
}
