package dev.lk.gardenshop.command;

import dev.lk.gardenshop.config.AdapterBindings;
import dev.lk.gardenshop.config.ConfigService;
import dev.lk.gardenshop.core.ConfigSnapshot;
import dev.lk.gardenshop.core.config.ValidationReport;
import dev.lk.gardenshop.core.domain.PriceFactor;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.pricing.PriceSweep;
import dev.lk.gardenshop.economy.EconomyProvider;
import dev.lk.gardenshop.economy.EconomyRouter;
import dev.lk.gardenshop.gui.MenuContext;
import dev.lk.gardenshop.pack.PackDelivery;
import dev.lk.gardenshop.gui.SellMenu;
import dev.lk.gardenshop.item.AdapterItems;
import dev.lk.gardenshop.item.HarvestResolver;
import dev.lk.gardenshop.item.ItemTagService;
import dev.lk.gardenshop.item.MythicItems;
import dev.lk.gardenshop.item.WeightStamper;
import dev.lk.gardenshop.sell.SellLine;
import dev.lk.gardenshop.sell.SellResult;
import dev.lk.gardenshop.sell.SellService;
import dev.lk.gardenshop.stats.StatsService;
import dev.lk.gardenshop.util.Messages;
import dev.lk.gardenshop.util.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /gardenshop} and its subcommands, mirroring the three things Steven's stand
 * offers in Grow a Garden — sell this, sell everything, and tell me what it is worth —
 * plus the favorite toggle that keeps a bulk sale from eating a record harvest.
 */
public final class GardenShopCommand implements CommandExecutor, TabCompleter {

    public static final String PERMISSION_SELL = "lkgardenshop.sell";
    public static final String PERMISSION_VALUE = "lkgardenshop.value";
    public static final String PERMISSION_FAVORITE = "lkgardenshop.favorite";
    public static final String PERMISSION_RELOAD = "lkgardenshop.admin.reload";
    public static final String PERMISSION_INFO = "lkgardenshop.admin.info";
    public static final String PERMISSION_PRICES = "lkgardenshop.admin.prices";
    public static final String PERMISSION_MENU = "lkgardenshop.menu";

    private static final List<String> SUBCOMMANDS =
            List.of("menu", "sell", "value", "favorite", "prices", "reload", "adapter", "info", "help");
    private static final List<String> SELL_TARGETS = List.of("hand", "all");

    private final ConfigService configs;
    private final SellService sell;
    private final HarvestResolver resolver;
    private final ItemTagService tags;
    private final EconomyRouter economy;
    private final MythicItems mythic;
    private final StatsService stats;
    private final Messages messages;
    private final PriceSweep sweep;
    private final MenuContext menuContext;
    private final PackDelivery packDelivery;
    private final AdapterCommand adapterCommand;

    public GardenShopCommand(ConfigService configs, SellService sell, HarvestResolver resolver,
                             ItemTagService tags, EconomyRouter economy, MythicItems mythic,
                             AdapterItems adapter, AdapterBindings bindings,
                             StatsService stats, Messages messages, PriceSweep sweep,
                             MenuContext menuContext, PackDelivery packDelivery) {
        this.configs = configs;
        this.sell = sell;
        this.resolver = resolver;
        this.tags = tags;
        this.economy = economy;
        this.mythic = mythic;
        this.stats = stats;
        this.messages = messages;
        this.sweep = sweep;
        this.menuContext = menuContext;
        this.packDelivery = packDelivery;
        this.adapterCommand = new AdapterCommand(configs, adapter, bindings, messages, this::applyReload);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // The bare command is the shop for a player, and help for the console — which
            // cannot open an inventory.
            if (sender instanceof Player player && menuEnabled()) {
                openShop(player);
            } else {
                sendHelp(sender);
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu", "shop", "gui" -> {
                if (sender instanceof Player player) {
                    openShop(player);
                } else {
                    messages.send(sender, "players-only");
                }
            }
            case "sell" -> handleSell(sender, args);
            case "value", "appraise" -> handleValue(sender);
            case "favorite", "fav" -> handleFavorite(sender);
            case "prices", "table" -> handlePrices(sender, args);
            case "reload" -> handleReload(sender);
            case "adapter" -> adapterCommand.handle(sender, args);
            case "info" -> handleInfo(sender);
            case "help" -> sendHelp(sender);
            default -> messages.send(sender, "unknown-subcommand", Placeholder.unparsed("input", args[0]));
        }
        return true;
    }

    // ----------------------------------------------------------------------- menu

    private boolean menuEnabled() {
        return configs.isLoaded() && configs.snapshot().gui().enabled();
    }

    private void openShop(Player player) {
        if (!player.hasPermission(PERMISSION_MENU)) {
            messages.send(player, "no-permission");
            return;
        }
        if (!menuEnabled()) {
            // Falling back to help rather than silently doing nothing: the owner turned the
            // menu off, and the commands still work.
            sendHelp(player);
            return;
        }
        new SellMenu(menuContext, player).open();
    }

    // ----------------------------------------------------------------------- sell

    private void handleSell(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, PERMISSION_SELL);
        if (player == null) {
            return;
        }

        String target = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "hand";
        SellResult result = switch (target) {
            case "hand", "this" -> sell.sellHand(player);
            case "all", "inventory" -> sell.sellInventory(player);
            default -> null;
        };

        if (result == null) {
            messages.send(sender, "sell.usage");
            return;
        }
        reportSale(messages, sell.provider(), player, result);
    }

    /**
     * Tells a player how their sale went.
     *
     * <p>Static and public because the menus need exactly the same reporting: every failure
     * mode has its own wording, and duplicating that in the GUI would guarantee the two drift
     * apart — most damagingly on {@code DEPOSIT_FAILED}, where the player has to be told
     * their crops came back.
     */
    public static void reportSale(Messages messages, EconomyProvider provider,
                                  Player player, SellResult result) {
        switch (result.outcome()) {
            case SOLD -> {
                messages.send(player, "sell.success",
                        Placeholder.unparsed("amount", provider.format(result.total())),
                        Placeholder.unparsed("items", Integer.toString(result.itemCount())),
                        Placeholder.unparsed("stacks", Integer.toString(result.lines().size())),
                        Placeholder.unparsed("currency", provider.currencyName()));
                if (result.skippedFavorites() > 0) {
                    messages.send(player, "sell.skipped-favorites",
                            Placeholder.unparsed("count", Integer.toString(result.skippedFavorites())));
                }
                if (result.leftOverLimit() > 0) {
                    messages.send(player, "sell.left-over-limit",
                            Placeholder.unparsed("count", Integer.toString(result.leftOverLimit())));
                }
                if (result.capped()) {
                    messages.send(player, "sell.capped",
                            Placeholder.unparsed("amount", provider.format(result.total())));
                }
            }
            case NOTHING_TO_SELL -> messages.send(player, "sell.nothing");
            case ALL_FAVORITED -> messages.send(player, "sell.all-favorited",
                    Placeholder.unparsed("count", Integer.toString(result.skippedFavorites())));
            case ECONOMY_UNAVAILABLE -> messages.send(player, "sell.economy-unavailable",
                    Placeholder.unparsed("reason", result.error()));
            case DEPOSIT_FAILED -> messages.send(player, "sell.deposit-failed",
                    Placeholder.unparsed("reason", result.error()));
            case INVENTORY_CHANGED -> messages.send(player, "sell.inventory-changed");
            case ON_COOLDOWN -> messages.send(player, "sell.cooldown",
                    Placeholder.unparsed("seconds", result.error()));
        }
    }

    // ---------------------------------------------------------------------- value

    private void handleValue(CommandSender sender) {
        Player player = requirePlayer(sender, PERMISSION_VALUE);
        if (player == null) {
            return;
        }

        Optional<SellLine> appraisal = sell.appraiseHand(player);
        if (appraisal.isEmpty()) {
            messages.send(player, "value.not-a-crop");
            sendInventoryTotal(player);
            return;
        }

        SellLine line = appraisal.get();
        EconomyProvider provider = sell.provider();
        var drop = line.quote().drop();

        messages.send(player, "value.header");
        messages.send(player, "value.identity",
                Placeholder.component("crop", Text.legacy(drop.species().displayName())),
                Placeholder.unparsed("variant", drop.variant().name()));
        if (drop.hasMutations()) {
            messages.send(player, "value.mutations",
                    Placeholder.unparsed("mutations", mutationList(line)));
        }
        messages.send(player, "value.weight",
                Placeholder.unparsed("weight", WeightStamper.formatWeight(drop.weightKg())),
                Placeholder.component("band", Text.legacy(line.quote().bandLabel())));
        messages.send(player, "value.unit",
                Placeholder.unparsed("unit", provider.format(line.quote().unitPrice())));
        messages.send(player, "value.total",
                Placeholder.unparsed("amount", Integer.toString(line.amount())),
                Placeholder.unparsed("total", provider.format(line.lineTotal())));

        for (PriceFactor factor : line.quote().factors()) {
            messages.send(player, "value.factor",
                    Placeholder.unparsed("factor", factor.key()),
                    Placeholder.component("label", Text.legacy(factor.label())),
                    Placeholder.unparsed("value", factor.value().stripTrailingZeros().toPlainString()));
        }
        sendInventoryTotal(player);
    }

    private void sendInventoryTotal(Player player) {
        List<SellLine> lines = sell.appraiseInventory(player);
        if (lines.isEmpty()) {
            return;
        }
        BigDecimal total = BigDecimal.ZERO;
        int items = 0;
        for (SellLine line : lines) {
            total = total.add(line.lineTotal());
            items += line.amount();
        }
        messages.send(player, "value.inventory",
                Placeholder.unparsed("total", sell.provider().format(total)),
                Placeholder.unparsed("items", Integer.toString(items)));
    }

    private static String mutationList(SellLine line) {
        List<String> names = new ArrayList<>();
        line.quote().drop().mutations().forEach(mutation -> names.add(mutation.name()));
        return String.join(", ", names);
    }

    // ------------------------------------------------------------------- favorite

    private void handleFavorite(CommandSender sender) {
        Player player = requirePlayer(sender, PERMISSION_FAVORITE);
        if (player == null) {
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!resolver.isHarvest(hand, configs.snapshot())) {
            messages.send(player, "favorite.not-a-crop");
            return;
        }
        boolean favorited = tags.toggleFavorite(hand);
        messages.send(player, favorited ? "favorite.added" : "favorite.removed");
    }

    // --------------------------------------------------------------------- prices

    /**
     * The calibration sheet: what every crop is actually worth across its whole range.
     *
     * <p>Tuning a 180-entry price matrix by editing multipliers and then going looking for
     * crops in-game does not work. This answers the question an owner really has — "is the
     * cheapest thing in my garden worth 8 and the dearest 40,000, and am I happy with
     * that?" — in one screen, and re-answers it after every {@code /gs reload}.
     */
    private void handlePrices(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION_PRICES)) {
            messages.send(sender, "no-permission");
            return;
        }

        ConfigSnapshot snapshot = configs.snapshot();
        EconomyProvider provider = economy.provider();

        if (args.length > 1) {
            sendCropPrices(sender, snapshot, provider, args[1].toLowerCase(Locale.ROOT));
            return;
        }
        sendPricesOverview(sender, snapshot, provider);
    }

    private void sendPricesOverview(CommandSender sender, ConfigSnapshot snapshot, EconomyProvider provider) {
        List<PriceSweep.CropRow> rows = sweep.overview(snapshot.registry(), snapshot.pricing());

        messages.send(sender, "prices.header",
                Placeholder.unparsed("mode", snapshot.pricing().mode().name()),
                Placeholder.unparsed("types", Integer.toString(snapshot.registry().size())));

        BigDecimal cheapest = null;
        BigDecimal dearest = null;
        for (PriceSweep.CropRow row : rows) {
            messages.send(sender, "prices.crop",
                    Placeholder.component("crop", Text.legacy(row.species().displayName())),
                    Placeholder.unparsed("id", row.species().id()),
                    Placeholder.unparsed("typical", provider.format(row.plainMid())),
                    Placeholder.unparsed("min", provider.format(row.cheapest())),
                    Placeholder.unparsed("max", provider.format(row.dearest())),
                    Placeholder.unparsed("multiple", round(row.multiple())),
                    Placeholder.unparsed("mintype", row.cheapestType()),
                    Placeholder.unparsed("maxtype", row.dearestType()));

            cheapest = cheapest == null ? row.cheapest() : cheapest.min(row.cheapest());
            dearest = dearest == null ? row.dearest() : dearest.max(row.dearest());
        }

        if (cheapest != null && dearest != null) {
            messages.send(sender, "prices.spread",
                    Placeholder.unparsed("min", provider.format(cheapest)),
                    Placeholder.unparsed("max", provider.format(dearest)),
                    Placeholder.unparsed("multiple", round(ratio(dearest, cheapest))));
        }
        messages.send(sender, "prices.hint");
    }

    private void sendCropPrices(CommandSender sender, ConfigSnapshot snapshot,
                                EconomyProvider provider, String speciesId) {
        // Case-insensitive: crop ids are lowercase by convention but nothing enforces it
        // in crops.yml, and a case mismatch here would look like a missing crop.
        Optional<Species> species = snapshot.species().stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(speciesId))
                .findFirst();
        if (species.isEmpty()) {
            messages.send(sender, "prices.unknown-crop",
                    Placeholder.unparsed("input", speciesId),
                    Placeholder.unparsed("known", knownCropIds(snapshot)));
            return;
        }

        List<PriceSweep.TypeRow> rows = sweep.forCrop(speciesId, snapshot.registry(), snapshot.pricing());
        messages.send(sender, "prices.crop-header",
                Placeholder.component("crop", Text.legacy(species.get().displayName())),
                Placeholder.unparsed("count", Integer.toString(rows.size())),
                Placeholder.unparsed("base", species.get().baseValue().stripTrailingZeros().toPlainString()),
                Placeholder.unparsed("ref", WeightStamper.formatWeight(species.get().baseWeightKg())));

        for (PriceSweep.TypeRow row : rows) {
            messages.send(sender, "prices.type",
                    Placeholder.unparsed("type", row.mythicType()),
                    Placeholder.unparsed("variant", row.variant().name()),
                    Placeholder.unparsed("mutations", mutationsOf(row)),
                    Placeholder.unparsed("wmin", WeightStamper.formatWeight(row.definition().weightRange().minKg())),
                    Placeholder.unparsed("wmax", WeightStamper.formatWeight(row.definition().weightRange().maxKg())),
                    Placeholder.component("band", Text.legacy(row.atMax().bandLabel())),
                    Placeholder.unparsed("pmin", provider.format(row.atMin().unitPrice())),
                    Placeholder.unparsed("pmax", provider.format(row.atMax().unitPrice())),
                    Placeholder.unparsed("spread", round(row.spread())));
        }
    }

    private static String mutationsOf(PriceSweep.TypeRow row) {
        List<String> names = new ArrayList<>();
        row.definition().mutations().forEach(mutation -> names.add(mutation.name()));
        return names.isEmpty() ? "-" : String.join("+", names);
    }

    private static String knownCropIds(ConfigSnapshot snapshot) {
        List<String> ids = new ArrayList<>();
        snapshot.species().forEach(species -> ids.add(species.id()));
        return String.join(", ", ids);
    }

    private static BigDecimal ratio(BigDecimal dearest, BigDecimal cheapest) {
        return cheapest.signum() == 0
                ? BigDecimal.ZERO
                : dearest.divide(cheapest, MathContext.DECIMAL32);
    }

    /** Multiples read better as "742x" than "742.0000". */
    private static String round(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    // --------------------------------------------------------------------- reload

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION_RELOAD)) {
            messages.send(sender, "no-permission");
            return;
        }
        applyReload(sender);
    }

    /**
     * The reload itself, without the permission check.
     *
     * <p>Separate so {@code /gs adapter bind} can apply what it just wrote. Folding the check in
     * would mean an owner with the adapter permission but not the reload one wrote a binding that
     * never took effect — a command that reports success and changes nothing.
     */
    void applyReload(CommandSender sender) {
        ConfigService.ReloadOutcome outcome = configs.reload();
        // Messages reload after the config, since the config is what names the language.
        messages.reload(configs.isLoaded() ? configs.snapshot().language() : messages.language());

        if (outcome.applied()) {
            ConfigSnapshot snapshot = configs.snapshot();
            // Re-resolving picks up an economy plugin that registered with Vault after
            // this plugin enabled.
            economy.resolve(snapshot.economy());

            // Any open menu is displaying prices that have just been replaced. Closing beats
            // refreshing: a player mid-confirmation should not have the numbers change under
            // their cursor.
            if (snapshot.gui().closeMenusOnReload()) {
                menuContext.menus().closeAll();
            } else {
                menuContext.menus().refreshAll();
            }

            // Re-resolving picks up a changed pack, a new url or a different port. It also
            // forgets who had the old pack, since that art may no longer be what we serve.
            packDelivery.resolve();

            messages.send(sender, "reload.success",
                    Placeholder.unparsed("types", Integer.toString(snapshot.registry().size())),
                    Placeholder.unparsed("crops", Integer.toString(snapshot.species().size())),
                    Placeholder.unparsed("mode", snapshot.pricing().mode().name()));
        } else {
            messages.send(sender, "reload.failed");
        }

        for (ValidationReport.Issue issue : outcome.reportableIssues()) {
            messages.send(sender, outcome.applied() ? "reload.warning" : "reload.error",
                    Placeholder.unparsed("file", issue.file()),
                    Placeholder.unparsed("path", issue.path()),
                    Placeholder.unparsed("message", issue.message()));
        }
        if (outcome.hiddenIssueCount() > 0) {
            messages.send(sender, "reload.more-issues",
                    Placeholder.unparsed("count", Integer.toString(outcome.hiddenIssueCount())));
        }
    }

    // ----------------------------------------------------------------------- info

    private void handleInfo(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION_INFO)) {
            messages.send(sender, "no-permission");
            return;
        }

        ConfigSnapshot snapshot = configs.snapshot();
        EconomyProvider provider = economy.provider();

        messages.send(sender, "info.header");
        messages.send(sender, "info.economy",
                Placeholder.unparsed("provider", provider.description()),
                Placeholder.unparsed("available", Boolean.toString(provider.isAvailable())));
        messages.send(sender, "info.pricing",
                Placeholder.unparsed("mode", snapshot.pricing().mode().name()),
                Placeholder.unparsed("bands", Integer.toString(snapshot.pricing().bands().bands().size())),
                Placeholder.unparsed("interpolate",
                        Boolean.toString(snapshot.pricing().bands().interpolate())));
        messages.send(sender, "info.registry",
                Placeholder.unparsed("types", Integer.toString(snapshot.registry().size())),
                Placeholder.unparsed("crops", Integer.toString(snapshot.species().size())),
                Placeholder.unparsed("weights", Integer.toString(snapshot.weights().size())));
        messages.send(sender, "info.mythic",
                Placeholder.unparsed("available", Boolean.toString(mythic.isAvailable())),
                Placeholder.unparsed("known", Integer.toString(mythic.knownTypes().size())));
        messages.send(sender, "info.pack",
                Placeholder.unparsed("mode", packDelivery.resolved().mode().configValue()),
                Placeholder.unparsed("detail", packDelivery.resolved().description()),
                Placeholder.unparsed("style", snapshot.menuStyle().name()),
                Placeholder.unparsed("loaded", Integer.toString(menuContext.packs().loadedCount())));
        messages.send(sender, "info.stats",
                Placeholder.unparsed("enabled", Boolean.toString(stats.isEnabled())),
                Placeholder.unparsed("players", Integer.toString(stats.trackedPlayers())));

        for (Species species : snapshot.species()) {
            messages.send(sender, "info.crop",
                    Placeholder.component("crop", Text.legacy(species.displayName())),
                    Placeholder.unparsed("id", species.id()),
                    Placeholder.unparsed("token", species.mythicToken()),
                    Placeholder.unparsed("base", species.baseValue().stripTrailingZeros().toPlainString()),
                    Placeholder.unparsed("weight", WeightStamper.formatWeight(species.baseWeightKg())));
        }

        // The single most useful diagnostic: types we price that the pack does not have.
        List<String> missing = missingFromMythic(snapshot);
        if (!missing.isEmpty()) {
            messages.send(sender, "info.missing-types",
                    Placeholder.unparsed("count", Integer.toString(missing.size())),
                    Placeholder.unparsed("first", missing.getFirst()));
        }
    }

    private List<String> missingFromMythic(ConfigSnapshot snapshot) {
        if (!mythic.isAvailable()) {
            return List.of();
        }
        List<String> known = new ArrayList<>();
        mythic.knownTypes().forEach(name -> known.add(name.toLowerCase(Locale.ROOT)));
        if (known.isEmpty()) {
            return List.of();
        }

        List<String> missing = new ArrayList<>();
        for (String type : snapshot.registry().types()) {
            if (!known.contains(type.toLowerCase(Locale.ROOT))) {
                missing.add(type);
            }
        }
        return missing;
    }

    // ----------------------------------------------------------------------- misc

    private void sendHelp(CommandSender sender) {
        messages.sendList(sender, "help");
        // Admin commands are appended rather than always listed, so players are not shown
        // things they cannot run and admins do not have to read the README to find them.
        if (sender.hasPermission(PERMISSION_PRICES)
                || sender.hasPermission(PERMISSION_RELOAD)
                || sender.hasPermission(PERMISSION_INFO)) {
            messages.sendList(sender, "help-admin");
        }
    }

    /** @return the player, or {@code null} after sending the appropriate refusal */
    private Player requirePlayer(CommandSender sender, String permission) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "players-only");
            return null;
        }
        if (!player.hasPermission(permission)) {
            messages.send(player, "no-permission");
            return null;
        }
        return player;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sell")) {
            return filter(SELL_TARGETS, args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("prices") || args[0].equalsIgnoreCase("table"))) {
            if (!sender.hasPermission(PERMISSION_PRICES) || !configs.isLoaded()) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            configs.snapshot().species().forEach(species -> ids.add(species.id()));
            return filter(ids, args[1]);
        }
        if (args[0].equalsIgnoreCase("adapter")) {
            return filter(adapterCommand.complete(sender, args), args[args.length - 1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String needle = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(needle)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
