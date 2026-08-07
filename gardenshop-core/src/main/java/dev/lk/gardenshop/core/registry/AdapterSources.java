package dev.lk.gardenshop.core.registry;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What the prefix of an adapter id tells us.
 *
 * <p>Wuason's Adapter names every custom item {@code plugin:id} — {@code mythic:growGardenNCDrop},
 * {@code ia:mygarden:tomato}, {@code mc:paper}. Two of those prefix families need special handling
 * and both for the same reason: our crops are Mythic items built on {@code Id: paper}, so a single
 * harvest truthfully answers to a Mythic id <em>and</em> to {@code mc:paper}.
 *
 * <p>Pure string work, deliberately. Core knows the shape of an adapter id without depending on the
 * library that produces them.
 */
public final class AdapterSources {

    /**
     * Prefixes meaning "the rest of this id is a Mythic type name".
     *
     * <p>A superset of what the library actually emits: matching is case-insensitive, so its
     * {@code mMobs} and {@code mythicC} spellings land here alongside the longer forms its
     * documentation lists.
     */
    public static final Set<String> MYTHIC = Set.of(
            "mythic", "mythicmobs", "mmobs", "mythicm",
            "crucible", "mythicc", "mcrucible", "mythiccrucible");

    /**
     * Prefixes naming a plain vanilla material.
     *
     * <p>Never bindable to a crop. {@code mc:paper} is a true statement about every harvest in the
     * pack, and treating it as an identifying one would turn every sheet of paper on the server into
     * a sellable crop.
     */
    public static final Set<String> VANILLA = Set.of("mc", "minecraft", "vanilla");

    private AdapterSources() {
    }

    /** Lower-cased, trimmed. Callers compare against the sets above. */
    public static Optional<String> prefixOf(String adapterId) {
        if (adapterId == null) {
            return Optional.empty();
        }
        String id = normalise(adapterId);
        int colon = id.indexOf(':');
        return colon <= 0 ? Optional.empty() : Optional.of(id.substring(0, colon));
    }

    public static boolean isVanilla(String adapterId) {
        return prefixOf(adapterId).filter(VANILLA::contains).isPresent();
    }

    /**
     * The Mythic type name inside a Mythic-sourced id.
     *
     * <p>Empty for every other prefix, which is the point: stripping the prefix off any id and
     * looking the remainder up would let {@code mc:} plus a type name resolve to a drop.
     *
     * @return normalised remainder, or empty if this is not a Mythic-sourced id
     */
    public static Optional<String> mythicType(String adapterId) {
        if (adapterId == null) {
            return Optional.empty();
        }
        String id = normalise(adapterId);
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            return Optional.empty();
        }
        return MYTHIC.contains(id.substring(0, colon))
                ? Optional.of(id.substring(colon + 1))
                : Optional.empty();
    }

    /** The single normalisation every lookup here and in {@link DropRegistry} agrees on. */
    public static String normalise(String adapterId) {
        return adapterId.trim().toLowerCase(Locale.ROOT);
    }
}
