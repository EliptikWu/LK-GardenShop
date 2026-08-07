package dev.lk.gardenshop.core.registry;

import dev.lk.gardenshop.core.domain.CropDrop;
import dev.lk.gardenshop.core.domain.Mutation;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.Variant;
import dev.lk.gardenshop.core.domain.WeightRange;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * What one Mythic drop type <em>is</em>: which species, variant and mutations it
 * represents, and the range its weight rolls within.
 *
 * <p>This is the static blueprint; a {@link CropDrop} is one weighed instance of it.
 */
public record DropDefinition(
        String mythicType,
        Species species,
        Variant variant,
        Set<Mutation> mutations,
        WeightRange weightRange
) {

    public DropDefinition {
        Objects.requireNonNull(mythicType, "mythicType");
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(mutations, "mutations");
        Objects.requireNonNull(weightRange, "weightRange");

        if (mythicType.isBlank()) {
            throw new IllegalArgumentException("mythicType must not be blank");
        }
        mutations = mutations.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(mutations));
    }

    /** Binds a concrete weight to this blueprint. */
    public CropDrop withWeight(double weightKg) {
        return new CropDrop(species, variant, mutations, weightKg, mythicType);
    }
}
