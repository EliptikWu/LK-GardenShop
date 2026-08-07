package dev.lk.gardenshop.core.config;

import dev.lk.gardenshop.core.domain.WeightRange;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The weight range each Mythic drop type rolls within, from {@code weights.yml}.
 *
 * <p>Lookup is case-insensitive because Mythic's own internal-name handling is
 * loose about case, and a mismatch here would silently fall back to the species
 * base range instead of erroring.
 */
public final class WeightTable {

    private final Map<String, WeightRange> byNormalisedType;

    public WeightTable(Map<String, WeightRange> ranges) {
        Objects.requireNonNull(ranges, "ranges");
        Map<String, WeightRange> copy = new HashMap<>(ranges.size());
        ranges.forEach((type, range) -> copy.put(normalise(type), range));
        this.byNormalisedType = Map.copyOf(copy);
    }

    public static WeightTable empty() {
        return new WeightTable(Map.of());
    }

    public Optional<WeightRange> find(String mythicType) {
        return mythicType == null
                ? Optional.empty()
                : Optional.ofNullable(byNormalisedType.get(normalise(mythicType)));
    }

    /** The declared range for a type, or {@code fallback} when the file has no entry. */
    public WeightRange rangeFor(String mythicType, WeightRange fallback) {
        return find(mythicType).orElse(fallback);
    }

    public int size() {
        return byNormalisedType.size();
    }

    private static String normalise(String type) {
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
