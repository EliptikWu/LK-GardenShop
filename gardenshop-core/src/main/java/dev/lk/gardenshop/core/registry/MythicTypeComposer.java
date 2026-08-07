package dev.lk.gardenshop.core.registry;

import dev.lk.gardenshop.core.config.WeightTable;
import dev.lk.gardenshop.core.domain.Mutation;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.Variant;
import dev.lk.gardenshop.core.domain.WeightRange;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds Mythic drop type names, and from them the whole {@link DropRegistry}.
 *
 * <h2>Why compose instead of parse</h2>
 * The obvious approach — take {@code growGardenNCDropIceRain} from Mythic and
 * regex it back into parts — is a trap: the mutation suffixes nest
 * ({@code IceRainLightning} ⊃ {@code IceRain} ⊃ {@code Ice}), so a naive pattern
 * happily matches the wrong one and mis-prices the item. Going the other way
 * makes that class of bug impossible: we enumerate every legal combination,
 * render its canonical name, and look items up by exact name.
 *
 * <p>6 species × 3 variants × 10 mutation states = <b>180</b> types, which is
 * exactly what the pack ships.
 */
public final class MythicTypeComposer {

    public static final String DEFAULT_PATTERN = "growGarden{crop}{variant}Drop{mutation}";

    private static final String CROP_TOKEN = "{crop}";
    private static final String VARIANT_TOKEN = "{variant}";
    private static final String MUTATION_TOKEN = "{mutation}";

    private final String pattern;

    public MythicTypeComposer(String pattern) {
        this.pattern = Objects.requireNonNull(pattern, "pattern");
        if (!pattern.contains(CROP_TOKEN)) {
            throw new IllegalArgumentException("type-pattern must contain " + CROP_TOKEN + ", got: " + pattern);
        }
        if (!pattern.contains(VARIANT_TOKEN)) {
            throw new IllegalArgumentException("type-pattern must contain " + VARIANT_TOKEN + ", got: " + pattern);
        }
        if (!pattern.contains(MUTATION_TOKEN)) {
            throw new IllegalArgumentException("type-pattern must contain " + MUTATION_TOKEN + ", got: " + pattern);
        }
    }

    public static MythicTypeComposer withDefaultPattern() {
        return new MythicTypeComposer(DEFAULT_PATTERN);
    }

    public String pattern() {
        return pattern;
    }

    /**
     * Renders the canonical Mythic internal name for one combination.
     *
     * @param cropToken the species' pack token, e.g. {@code NC} or {@code BlueBeet}
     */
    public String compose(String cropToken, Variant variant, Set<Mutation> mutations) {
        Objects.requireNonNull(cropToken, "cropToken");
        Objects.requireNonNull(variant, "variant");
        return pattern
                .replace(CROP_TOKEN, cropToken)
                .replace(VARIANT_TOKEN, variant.mythicToken())
                .replace(MUTATION_TOKEN, mutationToken(mutations));
    }

    /**
     * Concatenates mutation tokens in enum ordinal order, which is the order the
     * pack uses ({@code IceRainLightning}). Empty set renders as an empty string.
     */
    public static String mutationToken(Set<Mutation> mutations) {
        if (mutations == null || mutations.isEmpty()) {
            return "";
        }
        // EnumSet iterates in ordinal order regardless of insertion order.
        StringBuilder token = new StringBuilder();
        for (Mutation mutation : EnumSet.copyOf(mutations)) {
            token.append(mutation.mythicToken());
        }
        return token.toString();
    }

    /**
     * Every legal mutation state: the power set of the stackable weather mutations
     * (2³ = 8, all shipped by the pack) plus each dimensional mutation on its own.
     *
     * @return 10 sets, starting with the empty one; deterministic order
     */
    public static List<Set<Mutation>> mutationStates() {
        List<Mutation> stackable = Mutation.stackable();
        List<Set<Mutation>> states = new ArrayList<>();

        int combinations = 1 << stackable.size();
        for (int mask = 0; mask < combinations; mask++) {
            EnumSet<Mutation> state = EnumSet.noneOf(Mutation.class);
            for (int bit = 0; bit < stackable.size(); bit++) {
                if ((mask & (1 << bit)) != 0) {
                    state.add(stackable.get(bit));
                }
            }
            states.add(state);
        }

        for (Mutation exclusive : Mutation.exclusive()) {
            states.add(EnumSet.of(exclusive));
        }
        return List.copyOf(states);
    }

    /**
     * Expands the declared species into the full type matrix.
     *
     * <p>Each type's roll range comes from {@code weights.yml}; types missing there
     * fall back to their species' base range, so a pack addition still works
     * (slightly mis-weighted) instead of becoming unsellable.
     */
    public DropRegistry buildRegistry(Collection<Species> species, WeightTable weights) {
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(weights, "weights");

        List<Set<Mutation>> states = mutationStates();
        List<DropDefinition> definitions = new ArrayList<>(species.size() * Variant.values().length * states.size());
        Map<String, String> adapterAliases = new LinkedHashMap<>();

        for (Species crop : species) {
            for (Variant variant : Variant.values()) {
                for (Set<Mutation> mutations : states) {
                    String type = compose(crop.mythicToken(), variant, mutations);
                    WeightRange range = weights.rangeFor(type, crop.baseRange());
                    definitions.add(new DropDefinition(type, crop, variant, mutations, range));
                }
            }
        }

        // Foreign item ids map to the plain drop: NORMAL variant, no mutations. An item from
        // another plugin carries none of the variant or mutation information the composed names
        // encode, so pricing it as anything richer would be inventing data.
        for (Species crop : species) {
            String plain = compose(crop.mythicToken(), Variant.NORMAL, Set.of());
            for (String extra : crop.extraIds()) {
                String previous = adapterAliases.putIfAbsent(extra, plain);
                if (previous != null && !previous.equals(plain)) {
                    throw new IllegalArgumentException("extra-id '" + extra
                            + "' is declared under two different crops, so the shop cannot tell"
                            + " which one an item of that type is");
                }
            }
        }
        return new DropRegistry(definitions, adapterAliases);
    }
}
