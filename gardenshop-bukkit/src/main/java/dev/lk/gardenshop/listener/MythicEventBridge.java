package dev.lk.gardenshop.listener;

import dev.lk.gardenshop.config.ConfigService;
import dev.lk.gardenshop.core.ConfigSnapshot;
import dev.lk.gardenshop.item.MythicItems;
import dev.lk.gardenshop.item.PackIntegrity;
import dev.lk.gardenshop.item.WeightStamper;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Hooks MythicMobs' events without compiling against them.
 *
 * <h2>Why reflection here specifically</h2>
 * {@code MythicMobItemGenerateEvent} is the one API this plugin needs whose shape is
 * not documented — Mythic's published javadoc lists its constructor but no accessors,
 * so the name of the getter is an educated guess. Binding to it at compile time would
 * turn a wrong guess into a plugin that will not load at all. Registering the handler
 * dynamically turns it into a single warning on startup instead, and everything keeps
 * working: {@code HarvestResolver} pins weights lazily on first appraisal, so this
 * listener is an optimisation, not a requirement.
 *
 * <p>The cost is a {@link MethodHandle} call per generated item, which is negligible
 * next to the item generation itself.
 */
public final class MythicEventBridge implements Listener {

    private static final String ITEM_GENERATE_EVENT = "io.lumine.mythic.bukkit.events.MythicMobItemGenerateEvent";
    private static final String RELOADED_EVENT = "io.lumine.mythic.bukkit.events.MythicReloadedEvent";

    /** Candidate accessor names, most likely first. */
    private static final List<String> ITEM_STACK_ACCESSORS = List.of("getItemStack", "getItem", "itemStack");

    private final Plugin plugin;
    private final ConfigService configs;
    private final MythicItems mythic;
    private final WeightStamper stamper;
    private final PackIntegrity packIntegrity;
    private final Logger logger;

    public MythicEventBridge(Plugin plugin, ConfigService configs, MythicItems mythic,
                             WeightStamper stamper, PackIntegrity packIntegrity) {
        this.plugin = plugin;
        this.configs = configs;
        this.mythic = mythic;
        this.stamper = stamper;
        this.packIntegrity = packIntegrity;
        this.logger = plugin.getLogger();
    }

    /** Registers whatever can be found, reporting anything that cannot. */
    public void register() {
        PluginManager plugins = plugin.getServer().getPluginManager();
        registerItemGenerate(plugins);
        registerReloaded(plugins);
    }

    private void registerItemGenerate(PluginManager plugins) {
        Optional<Class<? extends Event>> eventClass = eventClass(ITEM_GENERATE_EVENT);
        if (eventClass.isEmpty()) {
            logger.warning("Could not find " + ITEM_GENERATE_EVENT
                    + "; harvest weights will be assigned on first appraisal instead of at harvest time.");
            return;
        }

        Optional<MethodHandle> accessor = findItemStackAccessor(eventClass.get());
        if (accessor.isEmpty()) {
            logger.warning(ITEM_GENERATE_EVENT + " exposes no ItemStack accessor this build recognises "
                    + "(tried " + ITEM_STACK_ACCESSORS + "). Weights will be assigned on first appraisal "
                    + "instead, which works but means freshly harvested lore is not rewritten.");
            return;
        }

        MethodHandle handle = accessor.get();
        // MONITOR: read the finished item, never alter what Mythic is building.
        plugins.registerEvent(eventClass.get(), this, EventPriority.MONITOR,
                (listener, event) -> {
                    try {
                        Object raw = handle.invoke(event);
                        if (raw instanceof ItemStack stack) {
                            onItemGenerated(stack);
                        }
                    } catch (Throwable t) {
                        // An exception thrown from an event handler would spam the console
                        // once per generated item, so swallow and rely on lazy stamping.
                        logger.fine(() -> "Skipped stamping a generated item: " + t);
                    }
                }, plugin, true);

        logger.info("Hooked MythicMobs item generation; harvest weights are stamped at harvest time.");
    }

    private void registerReloaded(PluginManager plugins) {
        Optional<Class<? extends Event>> eventClass = eventClass(RELOADED_EVENT);
        if (eventClass.isEmpty()) {
            return;
        }
        plugins.registerEvent(eventClass.get(), this, EventPriority.MONITOR,
                (listener, event) -> verifyAgainstPack(), plugin, true);
    }

    /**
     * Stamps a freshly generated harvest.
     *
     * <p>Runs inside item generation, so it does the minimum: a registry lookup and a
     * single meta edit.
     */
    private void onItemGenerated(ItemStack stack) {
        if (!configs.isLoaded()) {
            return;
        }
        ConfigSnapshot snapshot = configs.snapshot();
        if (!snapshot.items().stampOnGenerate()) {
            return;
        }
        mythic.typeOf(stack)
                .flatMap(type -> snapshot.registry().find(type))
                .ifPresent(definition -> stamper.stampFresh(stack, definition, snapshot.items()));
    }

    /**
     * After a {@code /mm reload}, checks that every type we price still exists in the
     * pack. A renamed drop would otherwise become quietly unsellable.
     */
    private void verifyAgainstPack() {
        if (!configs.isLoaded()) {
            return;
        }
        // Re-run the integrity check first: a /mm reload is exactly when a pack appears or vanishes,
        // and it is the event that lets a server which loaded MythicMobs late start trading without
        // a restart.
        PackIntegrity.Report report = packIntegrity.verify(configs.snapshot(), mythic);
        if (report.satisfied()) {
            return;
        }
        logger.warning("After the MythicMobs reload: " + report.summary() + ".");

        if (!mythic.isAvailable()) {
            return;
        }
        ConfigSnapshot snapshot = configs.snapshot();

        List<String> known = mythic.knownTypes().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
        if (known.isEmpty()) {
            return;
        }

        List<String> missing = snapshot.registry().types().stream()
                .filter(type -> !known.contains(type.toLowerCase(Locale.ROOT)))
                .sorted()
                .toList();

        if (!missing.isEmpty()) {
            logger.warning("MythicMobs reloaded and " + missing.size()
                    + " drop type(s) this plugin prices no longer exist in the pack (first: "
                    + missing.getFirst() + "). Those drops cannot be sold. Run /gs info for details.");
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<Class<? extends Event>> eventClass(String name) {
        try {
            Class<?> found = Class.forName(name, false, plugin.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(found)) {
                return Optional.empty();
            }
            return Optional.of((Class<? extends Event>) found);
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    private Optional<MethodHandle> findItemStackAccessor(Class<?> eventClass) {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        for (String candidate : ITEM_STACK_ACCESSORS) {
            try {
                return Optional.of(lookup.findVirtual(
                        eventClass, candidate, MethodType.methodType(ItemStack.class)));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                // Try the next name.
            }
        }
        return Optional.empty();
    }
}
