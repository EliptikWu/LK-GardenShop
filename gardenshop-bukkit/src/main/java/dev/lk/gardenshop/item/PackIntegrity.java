package dev.lk.gardenshop.item;

import dev.lk.gardenshop.core.ConfigSnapshot;
import dev.lk.gardenshop.core.registry.DropDefinition;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Whether MythicMobs actually has the crops this plugin prices.
 *
 * <h2>What this is not</h2>
 * It is <b>not protection</b>. The source is public, so anyone who wants the plugin without the pack
 * deletes a call and rebuilds. Treating a check like this as a lock would be fooling ourselves.
 *
 * <h2>What it is</h2>
 * The difference between a shop that sells nothing and a shop that <em>says why</em> it sells
 * nothing. Without the pack, {@code crops.yml} describes 180 harvest types that no item in the world
 * matches, so every sale reports "you have nothing to sell" — true, and useless. A named failure
 * turns a support question into a one-line answer.
 *
 * <h2>Checked, not assumed</h2>
 * <ul>
 *   <li>MythicMobs is hooked at all;</li>
 *   <li>every type {@code crops.yml} declares is in Mythic's item registry. An empty or malformed
 *       pack file registers nothing, so this catches it without reading the file: Mythic will not
 *       register an entry that has no {@code Id}, which means a name being present is already
 *       evidence of a real item behind it;</li>
 *   <li>those items carry a {@code Model}. An entry that exists without one renders as the plain
 *       paper it is built on, which is a pack that loaded but is not finished.</li>
 * </ul>
 *
 * <p>The last one is reported but does <b>not</b> gate. A pack could reasonably style its items some
 * other way, and refusing to run over that would be this plugin deciding how other people's packs
 * must be built.
 */
public final class PackIntegrity {

    /**
     * @param mythicHooked whether the Mythic item API answered at all
     * @param declared     types {@code crops.yml} prices
     * @param present      of those, how many Mythic knows
     * @param withoutModel of the present ones, how many build an item with no CustomModelData
     * @param borrowedArt  types drawn with a <em>sibling's</em> art rather than their own — see
     *                     {@link #borrowedArt()}
     */
    public record Report(boolean mythicHooked, int declared, int present, int withoutModel,
                         Set<String> borrowedArt) {

        public Report {
            borrowedArt = Set.copyOf(borrowedArt);
        }

        /** Without the art analysis, for tests and for the unknown state. */
        public Report(boolean mythicHooked, int declared, int present, int withoutModel) {
            this(mythicHooked, declared, present, withoutModel, Set.of());
        }

        public static Report unknown() {
            return new Report(false, 0, 0, 0);
        }

        /**
         * Whether this type has art of its own.
         *
         * <p>Normalised, because everything else here compares type names case-insensitively.
         */
        public boolean hasOwnArt(String mythicType) {
            return mythicType == null || !borrowedArt.contains(mythicType.toLowerCase(Locale.ROOT));
        }

        public int missing() {
            return declared - present;
        }

        /** Nothing at all matched: the pack was never installed, which is a fresh download. */
        public boolean packAbsent() {
            return declared > 0 && present == 0;
        }

        /** Whether selling can work. Deliberately ignores {@link #withoutModel}. */
        public boolean satisfied() {
            return mythicHooked && declared > 0 && missing() == 0;
        }

        /** One line for the console banner and {@code /gs info}. */
        public String summary() {
            if (!mythicHooked) {
                return "MythicMobs is not hooked, so no crop can be identified";
            }
            if (packAbsent()) {
                return "the crop pack is not installed: none of the " + declared
                        + " types in crops.yml exist in MythicMobs";
            }
            if (missing() > 0) {
                return missing() + " of " + declared + " types are missing from the pack";
            }
            if (withoutModel > 0) {
                return declared + " types present, but " + withoutModel
                        + " have no Model and will render as plain paper";
            }
            return "all " + declared + " types present in the pack";
        }
    }

    private final AtomicReference<Report> latest = new AtomicReference<>(Report.unknown());

    public Report latest() {
        return latest.get();
    }

    /**
     * Re-runs the check and stores the result.
     *
     * <p>Called on enable, on {@code /gs reload} and after a {@code /mm reload} — not per sale. Doing
     * it lazily rather than once at startup is what keeps a slow-loading pack from locking the shop:
     * MythicMobs can register its items after another plugin's {@code onEnable}, so a single check at
     * enable time would be a race, and the losing side would be a working server refusing to trade.
     */
    public Report verify(ConfigSnapshot snapshot, MythicItems mythic) {
        if (!mythic.isAvailable()) {
            return store(new Report(false, snapshot.registry().size(), 0, 0));
        }

        Set<String> known = mythic.knownTypes().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        int present = 0;
        int withoutModel = 0;
        // Model -> the types drawn with it. One model serving several types means they are visually
        // indistinguishable, which is what the art analysis below turns into an answer.
        Map<Integer, List<DropDefinition>> byModel = new LinkedHashMap<>();

        for (DropDefinition drop : snapshot.registry().all()) {
            if (!known.contains(drop.mythicType().toLowerCase(Locale.ROOT))) {
                continue;
            }
            present++;
            OptionalInt model = modelOf(mythic, drop.mythicType());
            if (model.isEmpty()) {
                withoutModel++;
                continue;
            }
            byModel.computeIfAbsent(model.getAsInt(), key -> new ArrayList<>()).add(drop);
        }
        return store(new Report(true, snapshot.registry().size(), present, withoutModel,
                borrowers(byModel)));
    }

    /**
     * Of each set of types sharing one model, the ones that are not its owner.
     *
     * <p>The owner is the drop with the <b>fewest mutations</b>. That is not a guess about this
     * particular pack: art gets drawn for the plain crop first and mutated variants are added later,
     * so a mutated drop wearing the same sprite as a plainer sibling is a placeholder — it exists in
     * the config and prices correctly, but there is nothing drawn for it yet.
     *
     * <p>Deriving it beats a hand-kept list of exceptions: the day the missing art is drawn and given
     * its own {@code Model}, this set shrinks on the next reload with nothing to update.
     */
    private static Set<String> borrowers(Map<Integer, List<DropDefinition>> byModel) {
        Set<String> borrowed = new LinkedHashSet<>();
        for (List<DropDefinition> sharing : byModel.values()) {
            if (sharing.size() < 2) {
                continue;
            }
            DropDefinition owner = sharing.stream()
                    .min(Comparator.comparingInt(drop -> drop.mutations().size()))
                    .orElseThrow();
            for (DropDefinition drop : sharing) {
                if (drop != owner) {
                    borrowed.add(drop.mythicType().toLowerCase(Locale.ROOT));
                }
            }
        }
        return borrowed;
    }

    /** Absent art is not a missing item, so a build failure counts as "no model" rather than absent. */
    @SuppressWarnings("deprecation")
    private static OptionalInt modelOf(MythicItems mythic, String type) {
        return mythic.menuItem(type)
                .map(ItemStack::getItemMeta)
                .filter(meta -> meta != null && meta.hasCustomModelData())
                .map(meta -> OptionalInt.of(meta.getCustomModelData()))
                .orElse(OptionalInt.empty());
    }

    private Report store(Report report) {
        latest.set(report);
        return report;
    }
}
