package dev.lk.gardenshop.command;

import dev.lk.gardenshop.config.AdapterBindings;
import dev.lk.gardenshop.config.ConfigService;
import dev.lk.gardenshop.core.ConfigSnapshot;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.registry.AdapterSources;
import dev.lk.gardenshop.item.AdapterItems;
import dev.lk.gardenshop.util.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@code /gs adapter} — mapping an item from another plugin onto one of our crops.
 *
 * <p>The job splits cleanly in two, and this exists because the halves belong to different parties.
 * Only a person knows that a particular tomato is meant to sell as an Odre; the plugin cannot infer
 * it, and guessing from name similarity would be paying real money on a hunch — a pack with
 * {@code carrot_seed}, {@code carrot_crate} and {@code golden_carrot_statue} offers three ways to be
 * wrong about one word. But finding an item's exact id and editing YAML without breaking it is
 * fiddly and error-prone, and that half the plugin can do perfectly.
 *
 * <p>So: the human points at the item and names the crop, and the plugin writes
 * {@link AdapterBindings the bindings file} and reloads.
 */
public final class AdapterCommand {

    public static final String PERMISSION = "lkgardenshop.admin.adapter";

    /** Enough to be useful, few enough not to flood a chat box. */
    private static final int LIST_LIMIT = 40;

    private static final List<String> SUBCOMMANDS = List.of("hand", "list", "bind", "unbind");

    private final ConfigService configs;
    private final AdapterItems adapter;
    private final AdapterBindings bindings;
    private final Messages messages;
    private final Consumer<CommandSender> reload;

    public AdapterCommand(ConfigService configs, AdapterItems adapter, AdapterBindings bindings,
                          Messages messages, Consumer<CommandSender> reload) {
        this.configs = configs;
        this.adapter = adapter;
        this.bindings = bindings;
        this.messages = messages;
        this.reload = reload;
    }

    public void handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            messages.send(sender, "no-permission");
            return;
        }
        if (!adapter.isAvailable()) {
            // Every subcommand needs it, so say so once instead of failing four different ways.
            messages.send(sender, "adapter.unavailable");
            return;
        }

        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "hand" -> hand(sender);
            case "list" -> list(sender, args.length > 2 ? args[2] : "");
            case "bind" -> bind(sender, args);
            case "unbind" -> unbind(sender, args);
            default -> status(sender);
        }
    }

    // --------------------------------------------------------------------- status

    private void status(CommandSender sender) {
        Map<String, String> current = bindings.load();
        messages.send(sender, "adapter.header");
        messages.send(sender, "adapter.status",
                Placeholder.unparsed("items", Integer.toString(adapter.allIds().size())),
                Placeholder.unparsed("bindings", Integer.toString(current.size())),
                Placeholder.unparsed("file", AdapterBindings.FILE));
        messages.sendList(sender, "adapter.usage");
    }

    // ----------------------------------------------------------------------- hand

    /**
     * The ids of the held item, each labelled with what it would do.
     *
     * <p>This is the discovery step, and the labels are the useful part: an item usually answers to
     * several ids and only one of them is worth binding.
     */
    private void hand(CommandSender sender) {
        Optional<ItemStack> held = heldItem(sender);
        if (held.isEmpty()) {
            return;
        }

        List<String> ids = adapter.idsOf(held.get());
        if (ids.isEmpty()) {
            messages.send(sender, "adapter.hand.no-ids");
            return;
        }

        ConfigSnapshot snapshot = configs.snapshot();
        messages.send(sender, "adapter.hand.header");
        for (String id : ids) {
            String key = snapshot.registry().findByAdapterId(id).isPresent()
                    ? "adapter.hand.resolved"
                    : AdapterSources.isVanilla(id) ? "adapter.hand.vanilla" : "adapter.hand.bindable";
            messages.send(sender, key,
                    Placeholder.unparsed("id", id),
                    Placeholder.unparsed("crop", snapshot.registry().findByAdapterId(id)
                            .map(drop -> drop.species().id()).orElse("-")));
        }
    }

    // ----------------------------------------------------------------------- list

    private void list(CommandSender sender, String filter) {
        String needle = AdapterSources.normalise(filter);
        List<String> matches = adapter.allIds().stream()
                .filter(id -> needle.isEmpty() || AdapterSources.normalise(id).contains(needle))
                .sorted()
                .toList();

        if (matches.isEmpty()) {
            messages.send(sender, "adapter.list.empty", Placeholder.unparsed("filter",
                    filter.isEmpty() ? "-" : filter));
            return;
        }

        messages.send(sender, "adapter.list.header",
                Placeholder.unparsed("count", Integer.toString(matches.size())),
                Placeholder.unparsed("filter", filter.isEmpty() ? "-" : filter));
        for (String id : matches.subList(0, Math.min(LIST_LIMIT, matches.size()))) {
            messages.send(sender, "adapter.list.entry", Placeholder.unparsed("id", id));
        }
        if (matches.size() > LIST_LIMIT) {
            messages.send(sender, "adapter.list.more",
                    Placeholder.unparsed("count", Integer.toString(matches.size() - LIST_LIMIT)));
        }
    }

    // ----------------------------------------------------------------------- bind

    private void bind(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "adapter.bind.usage");
            return;
        }

        String cropId = args[2].toLowerCase(Locale.ROOT);
        if (crop(cropId).isEmpty()) {
            messages.send(sender, "adapter.bind.unknown-crop",
                    Placeholder.unparsed("crop", args[2]),
                    Placeholder.unparsed("crops", String.join(", ", cropIds())));
            return;
        }

        Optional<String> chosen = args.length > 3
                ? explicitId(sender, args[3])
                : idFromHand(sender);
        if (chosen.isEmpty()) {
            return;
        }
        String id = AdapterSources.normalise(chosen.get());

        try {
            Optional<String> previous = bindings.bind(id, cropId);
            messages.send(sender, previous.isPresent() ? "adapter.bind.replaced" : "adapter.bind.done",
                    Placeholder.unparsed("id", id),
                    Placeholder.unparsed("crop", cropId),
                    Placeholder.unparsed("previous", previous.orElse("-")));
        } catch (IOException e) {
            messages.send(sender, "adapter.write-failed",
                    Placeholder.unparsed("file", AdapterBindings.FILE),
                    Placeholder.unparsed("message", String.valueOf(e.getMessage())));
            return;
        }
        // Written but not yet live: the registry is built at load time, so without this the item
        // stays unsellable until the next restart and the command looks like it did nothing.
        reload.accept(sender);
    }

    /** An id typed out by hand. Verified to exist, because a typo would otherwise write silently. */
    private Optional<String> explicitId(CommandSender sender, String id) {
        if (!refuseVanilla(sender, id)) {
            return Optional.empty();
        }
        if (!adapter.exists(id)) {
            messages.send(sender, "adapter.bind.unknown-id", Placeholder.unparsed("id", id));
            return Optional.empty();
        }
        return Optional.of(id);
    }

    /**
     * The one bindable id of the held item.
     *
     * <p>Refuses when there is more than one candidate rather than picking. Choosing wrong here binds
     * the wrong item family to a crop and pays out for it, and the cost of asking is one extra
     * command with the id spelled out.
     */
    private Optional<String> idFromHand(CommandSender sender) {
        Optional<ItemStack> held = heldItem(sender);
        if (held.isEmpty()) {
            return Optional.empty();
        }

        ConfigSnapshot snapshot = configs.snapshot();
        List<String> candidates = new ArrayList<>();
        for (String id : adapter.idsOf(held.get())) {
            if (AdapterSources.isVanilla(id)) {
                continue;
            }
            if (snapshot.registry().findByAdapterId(id).isPresent()) {
                // Already sellable. Binding it again would either be a no-op or, if it names one of
                // the pack's own drops, be rejected outright by the registry on reload.
                messages.send(sender, "adapter.bind.already",
                        Placeholder.unparsed("id", id),
                        Placeholder.unparsed("crop", snapshot.registry().findByAdapterId(id)
                                .orElseThrow().species().id()));
                return Optional.empty();
            }
            candidates.add(id);
        }

        if (candidates.isEmpty()) {
            messages.send(sender, "adapter.bind.no-candidates");
            return Optional.empty();
        }
        if (candidates.size() > 1) {
            messages.send(sender, "adapter.bind.ambiguous",
                    Placeholder.unparsed("count", Integer.toString(candidates.size())));
            for (String id : candidates) {
                messages.send(sender, "adapter.bind.ambiguous-entry", Placeholder.unparsed("id", id));
            }
            return Optional.empty();
        }
        return Optional.of(candidates.getFirst());
    }

    // --------------------------------------------------------------------- unbind

    private void unbind(CommandSender sender, String[] args) {
        Optional<String> target = args.length > 2
                ? Optional.of(args[2])
                : heldItem(sender).flatMap(item -> adapter.idsOf(item).stream()
                        .map(AdapterSources::normalise)
                        .filter(bindings.load()::containsKey)
                        .findFirst());
        if (target.isEmpty()) {
            if (args.length <= 2 && sender instanceof Player) {
                messages.send(sender, "adapter.unbind.nothing-bound");
            }
            return;
        }

        try {
            Optional<String> removed = bindings.unbind(target.get());
            if (removed.isEmpty()) {
                messages.send(sender, "adapter.unbind.not-bound",
                        Placeholder.unparsed("id", target.get()));
                return;
            }
            messages.send(sender, "adapter.unbind.done",
                    Placeholder.unparsed("id", AdapterSources.normalise(target.get())),
                    Placeholder.unparsed("crop", removed.get()));
        } catch (IOException e) {
            messages.send(sender, "adapter.write-failed",
                    Placeholder.unparsed("file", AdapterBindings.FILE),
                    Placeholder.unparsed("message", String.valueOf(e.getMessage())));
            return;
        }
        reload.accept(sender);
    }

    // ---------------------------------------------------------------------- shared

    /** @return empty <b>after telling the sender why</b>, so callers can just return */
    private Optional<ItemStack> heldItem(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "players-only");
            return Optional.empty();
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            messages.send(sender, "adapter.hand.empty");
            return Optional.empty();
        }
        return Optional.of(held);
    }

    /** @return {@code true} when the id is fine to bind; otherwise the sender has been told */
    private boolean refuseVanilla(CommandSender sender, String id) {
        if (AdapterSources.isVanilla(id)) {
            // 'mc:paper' is a true statement about every crop in the pack. Binding it would make
            // every sheet of paper on the server sell as a harvest.
            messages.send(sender, "adapter.bind.vanilla", Placeholder.unparsed("id", id));
            return false;
        }
        return true;
    }

    private Optional<Species> crop(String cropId) {
        return configs.isLoaded()
                ? configs.snapshot().species().stream()
                        .filter(species -> species.id().equalsIgnoreCase(cropId))
                        .findFirst()
                : Optional.empty();
    }

    private List<String> cropIds() {
        return configs.isLoaded()
                ? configs.snapshot().species().stream().map(Species::id).toList()
                : List.of();
    }

    // ------------------------------------------------------------------ completion

    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        return switch (args.length) {
            case 2 -> SUBCOMMANDS;
            case 3 -> args[1].equalsIgnoreCase("bind") ? cropIds() : List.of();
            default -> List.of();
        };
    }
}
