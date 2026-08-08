package dev.lk.gardenshop;

import dev.lk.gardenshop.command.GardenShopCommand;
import dev.lk.gardenshop.config.AdapterBindings;
import dev.lk.gardenshop.config.ConfigService;
import dev.lk.gardenshop.config.YamlConfigLoader;
import dev.lk.gardenshop.core.ConfigSnapshot;
import dev.lk.gardenshop.core.pricing.PriceCalculator;
import dev.lk.gardenshop.core.pricing.PriceSweep;
import dev.lk.gardenshop.economy.EconomyRouter;
import dev.lk.gardenshop.gui.MenuContext;
import dev.lk.gardenshop.gui.MenuListener;
import dev.lk.gardenshop.gui.MenuService;
import dev.lk.gardenshop.item.HarvestResolver;
import dev.lk.gardenshop.item.ItemKeys;
import dev.lk.gardenshop.item.ItemTagService;
import dev.lk.gardenshop.item.LoreWeightParser;
import dev.lk.gardenshop.item.MythicItems;
import dev.lk.gardenshop.item.WeightStamper;
import dev.lk.gardenshop.listener.MythicEventBridge;
import dev.lk.gardenshop.listener.PlayerCleanupListener;
import dev.lk.gardenshop.pack.EmbeddedPackServer;
import dev.lk.gardenshop.pack.PackBundleInstaller;
import dev.lk.gardenshop.pack.PackDelivery;
import dev.lk.gardenshop.item.AdapterItems;
import dev.lk.gardenshop.item.PackIntegrity;
import dev.lk.gardenshop.pack.PackTracker;
import dev.lk.gardenshop.papi.GardenShopExpansion;
import dev.lk.gardenshop.sell.SellService;
import dev.lk.gardenshop.stats.StatsService;
import dev.lk.gardenshop.stats.YamlStatsRepository;
import dev.lk.gardenshop.util.ConsoleBanner;
import dev.lk.gardenshop.util.Messages;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wiring and lifecycle.
 *
 * <p>Everything is constructed here and passed down by constructor — no service
 * locator, no mutable statics — so each collaborator can be swapped for a test double
 * and nothing survives a reload by accident.
 *
 * <p>Degradation is deliberate at every step. No MythicMobs: items are identified from
 * this plugin's own tags. No Vault: the server still starts, prices can still be
 * inspected, and selling reports why it is off. No PlaceholderAPI: no placeholders.
 * Only a configuration too broken to interpret stops the plugin, because at that point
 * there is nothing to run.
 */
public final class LKGardenShopPlugin extends JavaPlugin {

    private static final String COMMAND_NAME = "gardenshop";

    private ConfigService configs;
    private StatsService stats;
    private Messages messages;
    private MenuService menus;
    private PackTracker packs;
    private PackIntegrity packIntegrity;
    private PackDelivery packDelivery;

    @Override
    public void onEnable() {
        long startedAt = System.currentTimeMillis();

        // config.yml doubles as Bukkit's default config so the stats and console blocks,
        // both read once at startup, can be fetched without a second parse.
        saveDefaultConfig();

        messages = new Messages(this);

        YamlConfigLoader loader = new YamlConfigLoader(this);
        configs = new ConfigService(loader, getLogger());

        if (!configs.loadInitial()) {
            getLogger().severe("Disabling: the configuration could not be loaded. "
                    + "Fix the errors above and restart, or delete the files to regenerate defaults.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        messages.reload(configs.snapshot().language());

        ItemKeys keys = new ItemKeys(this);
        ItemTagService tags = new ItemTagService(keys);
        LoreWeightParser loreParser = new LoreWeightParser();
        WeightStamper stamper = new WeightStamper(tags, loreParser);
        MythicItems mythic = MythicItems.detect(getLogger());
        // Once, here. Adapter holds static state and refuses a second initialisation, so this
        // deliberately does not participate in /gs reload.
        AdapterItems adapter = AdapterItems.init(this);
        HarvestResolver resolver = new HarvestResolver(tags, mythic, adapter, loreParser, stamper);

        stats = createStatsService();
        EconomyRouter economy = new EconomyRouter(getServer(), getLogger());
        economy.resolve(configs.snapshot().economy());

        // Verified before the first sale can happen, and re-verified on /gs reload and after a
        // /mm reload -- see PackIntegrity for why once at enable would be a race.
        packIntegrity = new PackIntegrity();
        packIntegrity.verify(configs.snapshot(), mythic);

        PriceCalculator calculator = new PriceCalculator();
        PriceSweep sweep = new PriceSweep(calculator);
        SellService sell = new SellService(configs::snapshot, resolver, tags,
                calculator, economy::provider, stats, packIntegrity, getLogger());

        packs = new PackTracker();
        packDelivery = new PackDelivery(this, configs::snapshot,
                new PackBundleInstaller(this), new EmbeddedPackServer(getLogger()), packs);
        packDelivery.register();
        PackDelivery.Resolved pack = packDelivery.resolve();

        menus = new MenuService(this);
        MenuContext menuContext = new MenuContext(
                menus, configs::snapshot, sell, stats, messages, sweep, mythic, packIntegrity,
                packs, getLogger());

        registerCommand(new GardenShopCommand(configs, sell, resolver, tags, economy,
                mythic, adapter, new AdapterBindings(this), packIntegrity,
                stats, messages, sweep, menuContext, packDelivery));

        new MythicEventBridge(this, configs, mythic, stamper, packIntegrity).register();
        getServer().getPluginManager().registerEvents(new PlayerCleanupListener(sell), this);
        getServer().getPluginManager().registerEvents(new MenuListener(menus, getLogger()), this);

        boolean placeholders = registerPlaceholders(sell);
        scheduleStatsFlush();

        new ConsoleBanner(messages).print(
                getPluginMeta().getVersion(),
                checklist(mythic, adapter, economy, placeholders, pack),
                System.currentTimeMillis() - startedAt,
                getConfig().getBoolean("console.banner", true));
    }

    /**
     * The startup checklist.
     *
     * <p>Built from what actually happened rather than from what was configured, so a row that
     * says OK means the thing is genuinely wired up. Every subsystem here has a degraded mode,
     * and the whole reason for printing this is that those modes are otherwise invisible until
     * a player reports something odd.
     */
    private List<ConsoleBanner.Entry> checklist(MythicItems mythic, AdapterItems adapter,
                                                EconomyRouter economy,
                                                boolean placeholders, PackDelivery.Resolved pack) {
        ConfigSnapshot snapshot = configs.snapshot();
        List<ConsoleBanner.Entry> entries = new ArrayList<>();

        int warnings = snapshot.report().warnings().size();
        entries.add(warnings == 0
                ? ConsoleBanner.Entry.ok("Crops", snapshot.species().size() + " species, "
                        + snapshot.registry().size() + " drop types")
                : ConsoleBanner.Entry.warn("Crops", snapshot.species().size() + " species, "
                        + snapshot.registry().size() + " drop types, " + warnings + " warning(s)"));

        entries.add(economy.provider().isAvailable()
                ? ConsoleBanner.Entry.ok("Economy", economy.provider().description())
                : ConsoleBanner.Entry.off("Economy", economy.provider().description()
                        + " — selling is disabled"));

        if (!mythic.isAvailable()) {
            entries.add(ConsoleBanner.Entry.off("MythicMobs",
                    "not hooked — crops identified from this plugin's own tags only"));
        } else {
            long missing = snapshot.registry().types().stream()
                    .filter(type -> mythic.knownTypes().stream().noneMatch(type::equalsIgnoreCase))
                    .count();
            if (missing == 0) {
                entries.add(ConsoleBanner.Entry.ok("MythicMobs", "hooked, all "
                        + snapshot.registry().size() + " types present in the pack"));
            } else if (missing == snapshot.registry().size()) {
                // Every single type absent is not "some drops are missing", it is "the crop pack was
                // never installed" -- the state a fresh download lands in. Reporting it as a count
                // is true and useless: it tells an owner a number when what they need is the reason
                // and the fix.
                entries.add(ConsoleBanner.Entry.off("Crop pack",
                        "NOT INSTALLED — put the crop pack in plugins/MythicMobs/Items/ and restart."
                                + (snapshot.items().requireCropPack()
                                        ? " Selling and the shop menu are refused until you do."
                                        : " Nothing is sellable until you do.")));
            } else {
                entries.add(ConsoleBanner.Entry.warn("MythicMobs", missing
                        + " of " + snapshot.registry().size()
                        + " configured type(s) missing from the pack — those drops cannot be sold"));
            }
        }

        // The third identification route. It earns a row precisely because it is invisible in
        // normal operation: it only speaks up once the two routes ahead of it have missed, so
        // without this nobody would know whether the safety net is actually strung.
        long mapped = snapshot.species().stream().mapToLong(crop -> crop.extraIds().size()).sum();
        entries.add(adapter.isAvailable()
                ? ConsoleBanner.Entry.ok("Item adapter", mapped == 0
                        ? "hooked — backs up Mythic identification"
                        : "hooked — " + mapped + " id(s) mapped from other item plugins")
                : ConsoleBanner.Entry.off("Item adapter",
                        "not started — identification by tag and MythicMobs only"));

        entries.add(snapshot.gui().enabled()
                ? ConsoleBanner.Entry.ok("Menu", "/" + COMMAND_NAME + " opens the shop, style "
                        + snapshot.menuStyle().name().toLowerCase(java.util.Locale.ROOT))
                : ConsoleBanner.Entry.off("Menu", "disabled in gui.yml, commands only"));

        // The row that matters most when the menu looks wrong: no pack means no backdrop.
        if (!snapshot.pack().installed()) {
            entries.add(ConsoleBanner.Entry.off("Resource pack",
                    "installed: false - menus drawn plain, no art anywhere"));
        } else {
            entries.add(pack.sends()
                    ? ConsoleBanner.Entry.ok("Resource pack", pack.description())
                    : ConsoleBanner.Entry.warn("Resource pack", pack.description()
                            + " - players will see plain menus unless another plugin serves it"));
        }

        entries.add(placeholders
                ? ConsoleBanner.Entry.ok("Placeholders", "%gardenshop_...% registered")
                : ConsoleBanner.Entry.off("Placeholders", "PlaceholderAPI not installed"));

        entries.add(stats.isEnabled()
                ? ConsoleBanner.Entry.ok("Statistics", stats.trackedPlayers() + " player(s) tracked")
                : ConsoleBanner.Entry.off("Statistics", "disabled in config.yml"));

        return entries;
    }

    @Override
    public void onDisable() {
        // Menus first: once this plugin is disabled its listener is gone, and an open menu
        // with nothing guarding it is an inventory the player can interact with freely.
        if (menus != null) {
            menus.closeAll();
        }

        // Then the pack server: a bound port outliving the plugin blocks the next enable.
        if (packDelivery != null) {
            packDelivery.stop();
        }

        // Synchronous on purpose: an async flush scheduled during shutdown is not
        // guaranteed to run, and losing a session of statistics to a clean restart
        // would be a silly way to lose data.
        if (stats != null) {
            stats.flush();
        }
    }

    private StatsService createStatsService() {
        boolean enabled = getConfig().getBoolean("stats.enabled", true);
        StatsService service = new StatsService(
                new YamlStatsRepository(getDataFolder(), getLogger()), getLogger(), enabled);

        if (enabled) {
            // Reading a file on the main thread during startup is avoidable, so avoid it.
            getServer().getAsyncScheduler().runNow(this, task -> service.load());
        }
        return service;
    }

    private void scheduleStatsFlush() {
        if (!stats.isEnabled()) {
            return;
        }
        int interval = Math.max(10, getConfig().getInt("stats.flush-interval-seconds", 60));
        // getAsyncScheduler() works on both Paper and Folia, which is the only
        // scheduling this plugin needs.
        getServer().getAsyncScheduler().runAtFixedRate(
                this, task -> stats.flush(), interval, interval, TimeUnit.SECONDS);
    }

    private void registerCommand(GardenShopCommand executor) {
        PluginCommand command = getCommand(COMMAND_NAME);
        if (command == null) {
            // Only reachable if plugin.yml and this class disagree.
            getLogger().severe("Command /" + COMMAND_NAME + " is missing from plugin.yml.");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    /** @return whether the expansion is live, for the startup checklist */
    private boolean registerPlaceholders(SellService sell) {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return false;
        }
        try {
            // Touching the class only inside the guard keeps a PAPI-less server from
            // ever loading it.
            return new GardenShopExpansion(this, configs, sell, stats).register();
        } catch (Throwable t) {
            getLogger().warning("Could not register placeholders (" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + "). Everything else works normally.");
            return false;
        }
    }
}
