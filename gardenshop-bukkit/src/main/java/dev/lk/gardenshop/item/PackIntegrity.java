package dev.lk.gardenshop.item;

import dev.lk.gardenshop.core.ConfigSnapshot;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
     */
    public record Report(boolean mythicHooked, int declared, int present, int withoutModel) {

        public static Report unknown() {
            return new Report(false, 0, 0, 0);
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

        Set<String> declared = snapshot.registry().types();
        Set<String> known = mythic.knownTypes().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        int present = 0;
        int withoutModel = 0;
        for (String type : declared) {
            if (!known.contains(type.toLowerCase(Locale.ROOT))) {
                continue;
            }
            present++;
            if (!hasModel(mythic, type)) {
                withoutModel++;
            }
        }
        return store(new Report(true, declared.size(), present, withoutModel));
    }

    /** Absent art is not a missing item, so a build failure counts as "no model" rather than absent. */
    @SuppressWarnings("deprecation")
    private static boolean hasModel(MythicItems mythic, String type) {
        return mythic.menuItem(type)
                .map(ItemStack::getItemMeta)
                .map(meta -> meta != null && meta.hasCustomModelData())
                .orElse(false);
    }

    private Report store(Report report) {
        latest.set(report);
        return report;
    }
}
