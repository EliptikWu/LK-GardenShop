package dev.lk.gardenshop.core.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Growth variant of a harvested crop.
 *
 * <p>The token is the fragment the Mythic pack splices into a drop's internal
 * name: {@code growGarden}<b>Chilli</b><i>Rainbow</i>{@code Drop}. {@link #NORMAL}
 * contributes nothing, which is why the plain drops are {@code growGardenChilliDrop}
 * and not {@code growGardenChilliNormalDrop}.
 */
public enum Variant {

    NORMAL(""),
    GOLD("Gold"),
    RAINBOW("Rainbow");

    private final String mythicToken;

    Variant(String mythicToken) {
        this.mythicToken = mythicToken;
    }

    public String mythicToken() {
        return mythicToken;
    }

    public static Optional<Variant> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalised = id.trim().toUpperCase(Locale.ROOT);
        for (Variant variant : values()) {
            if (variant.name().equals(normalised)) {
                return Optional.of(variant);
            }
        }
        return Optional.empty();
    }
}
