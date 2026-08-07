package dev.lk.gardenshop.core.registry;

import dev.lk.gardenshop.core.domain.Species;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable index of every sellable drop type, keyed for O(1) lookup — the hot
 * path runs once per inventory slot on every {@code /gs sell all}, so this must
 * not scan.
 *
 * <p>Lookup is case-insensitive: Mythic is inconsistent about the case of internal
 * names, and a miss here would make an item silently unsellable.
 */
public final class DropRegistry {

    private final Map<String, DropDefinition> byNormalisedType;
    private final Map<String, DropDefinition> byAdapterId;
    private final List<DropDefinition> definitions;

    public DropRegistry(Collection<DropDefinition> definitions) {
        this(definitions, Map.of());
    }

    /**
     * @param adapterAliases adapter id → the Mythic type it should sell as, from the crops'
     *                       {@code extra-ids}. Every value must name a type in {@code definitions}
     */
    public DropRegistry(Collection<DropDefinition> definitions, Map<String, String> adapterAliases) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(adapterAliases, "adapterAliases");

        Map<String, DropDefinition> index = new HashMap<>(definitions.size() * 2);
        for (DropDefinition definition : definitions) {
            DropDefinition previous = index.put(normalise(definition.mythicType()), definition);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "two drop definitions resolve to the same Mythic type '" + definition.mythicType()
                                + "' — check crops.yml for a duplicated mythic-token");
            }
        }

        Map<String, DropDefinition> aliases = new HashMap<>(adapterAliases.size() * 2);
        for (Map.Entry<String, String> alias : adapterAliases.entrySet()) {
            String id = normalise(alias.getKey());
            DropDefinition target = index.get(normalise(alias.getValue()));
            if (target == null) {
                throw new IllegalArgumentException("adapter id '" + alias.getKey()
                        + "' points at unknown drop type '" + alias.getValue() + "'");
            }
            // An alias is checked before the prefix rule, so one shaped like a Mythic id could
            // quietly re-point a real drop at another crop -- an item that prices as the wrong
            // plant, which is the same class of bug the composed names exist to prevent.
            AdapterSources.mythicType(id).ifPresent(shadowed -> {
                if (index.containsKey(shadowed)) {
                    throw new IllegalArgumentException("extra-id '" + alias.getKey()
                            + "' already names one of this pack's own drops. Mythic and Crucible"
                            + " items are matched automatically; extra-ids is for items from other"
                            + " plugins");
                }
            });
            aliases.put(id, target);
        }

        this.byNormalisedType = Map.copyOf(index);
        this.byAdapterId = Map.copyOf(aliases);
        this.definitions = List.copyOf(definitions);
    }

    public static DropRegistry empty() {
        return new DropRegistry(List.of());
    }

    public Optional<DropDefinition> find(String mythicType) {
        return mythicType == null
                ? Optional.empty()
                : Optional.ofNullable(byNormalisedType.get(normalise(mythicType)));
    }

    public boolean contains(String mythicType) {
        return find(mythicType).isPresent();
    }

    /**
     * Resolves an adapter id — {@code plugin:id}, as Wuason's Adapter reports it.
     *
     * <p>Two ways in, in this order. An id declared in a crop's {@code extra-ids} maps to that
     * crop's plain drop; otherwise, an id from a <em>Mythic or Crucible</em> source has its prefix
     * stripped and the remainder looked up as a type name, because that remainder <em>is</em> the
     * Mythic name — see {@link AdapterSources}. Anything else misses.
     *
     * <p>This is the third identification route, behind our own tag and the direct MythicMobs call.
     * It earns its place by covering what those cannot: a Mythic API that has started throwing, and
     * crops that come from a different item plugin.
     */
    public Optional<DropDefinition> findByAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return Optional.empty();
        }
        String id = normalise(adapterId);

        DropDefinition alias = byAdapterId.get(id);
        if (alias != null) {
            return Optional.of(alias);
        }
        return AdapterSources.mythicType(id)
                .flatMap(type -> Optional.ofNullable(byNormalisedType.get(type)));
    }

    public List<DropDefinition> all() {
        return definitions;
    }

    /** Every declared type name, in canonical (not normalised) form. */
    public Set<String> types() {
        Set<String> names = new LinkedHashSet<>(definitions.size() * 2);
        for (DropDefinition definition : definitions) {
            names.add(definition.mythicType());
        }
        return Set.copyOf(names);
    }

    /** Distinct species present, in declaration order. */
    public Collection<Species> species() {
        Map<String, Species> unique = new LinkedHashMap<>();
        for (DropDefinition definition : definitions) {
            unique.putIfAbsent(definition.species().id(), definition.species());
        }
        return List.copyOf(unique.values());
    }

    public int size() {
        return definitions.size();
    }

    public boolean isEmpty() {
        return definitions.isEmpty();
    }

    private static String normalise(String type) {
        return AdapterSources.normalise(type);
    }
}
