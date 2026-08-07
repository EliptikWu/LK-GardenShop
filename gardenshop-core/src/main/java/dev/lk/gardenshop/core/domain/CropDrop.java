package dev.lk.gardenshop.core.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One harvested crop, fully identified and weighed — the unit the pricing engine
 * consumes.
 *
 * <p>Stack size is deliberately absent: this describes a single item's identity,
 * while quantity is a property of a sale. The sell layer multiplies the unit
 * price it gets back.
 */
public record CropDrop(
        Species species,
        Variant variant,
        Set<Mutation> mutations,
        double weightKg,
        String mythicType
) {

    public CropDrop {
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(mutations, "mutations");
        Objects.requireNonNull(mythicType, "mythicType");

        if (!Double.isFinite(weightKg) || weightKg <= 0.0) {
            throw new IllegalArgumentException("weightKg must be a positive finite number, got " + weightKg);
        }
        // Copy into an EnumSet so iteration order is ordinal order (Ice, Rain,
        // Lightning), which is what Mythic type names depend on.
        mutations = mutations.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(mutations));
    }

    public double weightRatio() {
        return species.weightRatio(weightKg);
    }

    public boolean hasMutations() {
        return !mutations.isEmpty();
    }
}
