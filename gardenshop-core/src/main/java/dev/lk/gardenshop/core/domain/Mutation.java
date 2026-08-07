package dev.lk.gardenshop.core.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A growth mutation a crop can pick up while planted.
 *
 * <p><b>Declaration order is load-bearing.</b> {@link java.util.EnumSet} iterates
 * in ordinal order, and the Mythic pack always names stacked mutations
 * ice-then-rain-then-lightning ({@code growGardenNCDropIceRainLightning} — never
 * {@code ...LightningRainIce}). Reordering these constants would silently
 * generate type names that match nothing in the pack.
 *
 * <p>{@link #isStackable()} mutations combine freely: the three weather mutations
 * yield 2³ = 8 combinations and the pack ships all eight. {@link #NETHER} and
 * {@link #END} are dimensional and only ever appear on their own, for a total of
 * ten mutation states per crop/variant pair.
 */
public enum Mutation {

    ICE("Ice", true),
    RAIN("Rain", true),
    LIGHTNING("Lightning", true),
    NETHER("Nether", false),
    END("End", false);

    private final String mythicToken;
    private final boolean stackable;

    Mutation(String mythicToken, boolean stackable) {
        this.mythicToken = mythicToken;
        this.stackable = stackable;
    }

    public String mythicToken() {
        return mythicToken;
    }

    /** Whether this mutation may appear alongside other mutations. */
    public boolean isStackable() {
        return stackable;
    }

    public static List<Mutation> stackable() {
        return Arrays.stream(values()).filter(Mutation::isStackable).toList();
    }

    public static List<Mutation> exclusive() {
        return Arrays.stream(values()).filter(m -> !m.isStackable()).toList();
    }

    public static Optional<Mutation> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalised = id.trim().toUpperCase(Locale.ROOT);
        for (Mutation mutation : values()) {
            if (mutation.name().equals(normalised)) {
                return Optional.of(mutation);
            }
        }
        return Optional.empty();
    }
}
