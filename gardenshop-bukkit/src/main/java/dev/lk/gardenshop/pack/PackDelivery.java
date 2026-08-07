package dev.lk.gardenshop.pack;

import dev.lk.gardenshop.core.ConfigSnapshot;
import dev.lk.gardenshop.core.config.PackMode;
import dev.lk.gardenshop.core.config.ResourcePackSettings;
import dev.lk.gardenshop.util.Text;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Decides how the pack reaches players, and sends it when they join.
 *
 * <p>Resolution happens once at startup and again on reload, producing a {@link Resolved} that both
 * the join handler and the startup banner read. Deciding per join would mean re-hashing the zip and
 * re-checking a port on every player.
 *
 * <p>Our pack is <b>added</b> to whatever the client already has, never swapped in — see
 * {@link #request}. A server running ItemsAdder, Nexo, Oraxen or a {@code server.properties} pack
 * keeps all of it; ours goes on top.
 */
public final class PackDelivery implements Listener {

    /**
     * Our pack's identity on the client.
     *
     * <p>Fixed for the lifetime of the plugin, and derived from a name rather than written as a magic
     * literal so it is self-evidently ours and cannot collide with another plugin's.
     *
     * <p>It deliberately does <b>not</b> depend on the URL, the way Bukkit's convenience overloads do
     * ({@code UUID.nameUUIDFromBytes(url)}). The client keys applied packs by this id, so a stable one
     * means a changed pack updates our entry instead of stacking a second copy, and lets us remove
     * exactly ours when delivery is turned off. A url-derived id would leave the old pack applied
     * forever the moment somebody changed the self-host port.
     */
    public static final UUID PACK_ID =
            UUID.nameUUIDFromBytes("dev.lk.gardenshop:pack".getBytes(StandardCharsets.UTF_8));

    /**
     * The outcome of working out how to deliver the pack.
     *
     * @param mode        what was actually settled on, which may be {@link PackMode#NONE} if the
     *                    chosen route turned out to be unavailable
     * @param url         where clients download from; empty when nothing is being sent
     * @param sha1        hex digest clients verify against
     * @param description one line for {@code /gs info} and the startup banner
     */
    public record Resolved(PackMode mode, String url, String sha1, String description) {

        static Resolved none(String why) {
            return new Resolved(PackMode.NONE, "", "", why);
        }

        public boolean sends() {
            return mode != PackMode.NONE && !url.isEmpty();
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final Supplier<ConfigSnapshot> configs;
    private final PackBundleInstaller installer;
    private final EmbeddedPackServer server;
    private final PackTracker tracker;

    private volatile Resolved resolved = Resolved.none("not resolved yet");

    public PackDelivery(Plugin plugin, Supplier<ConfigSnapshot> configs,
                        PackBundleInstaller installer, EmbeddedPackServer server,
                        PackTracker tracker) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configs = configs;
        this.installer = installer;
        this.server = server;
        this.tracker = tracker;
    }

    public Resolved resolved() {
        return resolved;
    }

    /** Works out the delivery route and prepares it. Call on enable and after a reload. */
    public Resolved resolve() {
        ResourcePackSettings settings = configs.get().pack();
        Resolved previous = resolved;

        // Stopped unconditionally first. start() is a no-op while already listening, so without
        // this a reload would keep serving the OLD bytes from the OLD port while handing clients a
        // URL built from the new configuration.
        server.stop();

        resolved = switch (settings.mode()) {
            case NONE -> Resolved.none(settings.enabled()
                    ? "no delivery route available"
                    : "sending disabled in config.yml");
            case EXTERNAL_URL -> external(settings);
            case BUNDLED_SELF_HOST -> selfHost(settings);
        };

        // Only forget who has the pack when the pack itself changed. Clearing on every reload
        // would mark every online player as pack-less, and since nothing re-sends it to them,
        // 'style: auto' would drop them all to plain menus until they reconnected.
        if (!previous.sha1().equals(resolved.sha1())) {
            tracker.forgetAll();
            refreshOnline(previous);
        }
        return resolved;
    }

    /**
     * Brings players already on the server onto the pack we now serve.
     *
     * <p>Without this they keep rendering the old art until they reconnect, which under
     * {@code style: auto} means they are correctly marked as not having the current pack and get
     * plain menus indefinitely.
     *
     * <p>Ours is popped before the new one is pushed. The client keys applied packs by
     * {@link #PACK_ID}, so pushing the same id twice is ambiguous, and the pop is also the only thing
     * that makes turning delivery <em>off</em> visible without a reconnect. Nothing else the client
     * has is touched.
     */
    private void refreshOnline(Resolved previous) {
        Resolved current = resolved;
        if (!previous.sends() && !current.sends()) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (previous.sends()) {
                player.removeResourcePack(PACK_ID);
            }
            if (current.sends()) {
                send(player, current);
            }
        }
    }

    private Resolved external(ResourcePackSettings settings) {
        if (!settings.hasValidSha1()) {
            // Sending a wrong hash is worse than sending nothing: the client downloads, rejects it,
            // and the player sees an error with no idea why.
            logger.warning("resource-pack.url is set but sha1 is not 40 hex characters. "
                    + "Run './gradlew packZip' to get the right hash, or clear the url to let the "
                    + "plugin serve the pack itself. Nothing will be sent.");
            return Resolved.none("external-url with an invalid sha1");
        }
        try {
            // Checked once here rather than per join: an unparseable url would otherwise throw for
            // every player who logged in, one warning at a time, with the startup banner claiming
            // the pack was being delivered.
            new URI(settings.url());
        } catch (URISyntaxException e) {
            logger.warning("resource-pack.url is not a valid URL (" + e.getReason()
                    + "). Nothing will be sent.");
            return Resolved.none("external-url with an unusable url");
        }
        return new Resolved(PackMode.EXTERNAL_URL, settings.url(), settings.sha1(),
                "external-url -> " + settings.url());
    }

    private Resolved selfHost(ResourcePackSettings settings) {
        Optional<PackBundleInstaller.InstalledPack> installed = installer.install();
        if (installed.isEmpty()) {
            return Resolved.none("no pack available to serve");
        }
        PackBundleInstaller.InstalledPack pack = installed.get();

        // The host check comes before binding a port, so a misconfigured server does not
        // open and immediately close a socket for nothing.
        Optional<String> host = publicHost(settings);
        if (host.isEmpty()) {
            // Guessing an address here would produce a URL that silently fails for every player.
            logger.warning("Cannot work out the address clients should download from. "
                    + "Set resource-pack.self-host.public-host to your server's public IP or "
                    + "hostname, or set resource-pack.url to host the zip yourself.");
            return Resolved.none("public host unknown");
        }

        if (!server.start(pack.file(), settings.selfHostPort())) {
            return Resolved.none("port " + settings.selfHostPort() + " could not be opened");
        }

        String url = "http://" + host.get() + ":" + settings.selfHostPort() + EmbeddedPackServer.PATH;
        return new Resolved(PackMode.BUNDLED_SELF_HOST, url, pack.sha1(),
                "self-hosted on port " + settings.selfHostPort()
                        + (pack.customised() ? " (your edited pack)" : ""));
    }

    /** The configured host, else the address the server bound to if it is not the wildcard. */
    private Optional<String> publicHost(ResourcePackSettings settings) {
        if (!settings.publicHost().isEmpty()) {
            return Optional.of(settings.publicHost());
        }
        String bound = Bukkit.getIp();
        return bound == null || bound.isBlank() ? Optional.empty() : Optional.of(bound);
    }

    public void stop() {
        server.stop();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Resolved current = resolved;
        if (!current.sends()) {
            return;
        }
        send(event.getPlayer(), current);
    }

    /**
     * The download request for one client.
     *
     * <p>{@code replace(false)} is the entire point of this method. Every {@code setResourcePack}
     * overload on {@code Player} — including the one this used to call — is deprecated and pops
     * <b>every</b> pack the client has applied before pushing its own. On a server with ItemsAdder,
     * Nexo, Oraxen or a {@code server.properties} pack, sending our art that way would strip theirs
     * and leave players with no custom textures at all: on this server, no crop textures, so the
     * plugin would break the very items it sells. Since 1.20.3 the client holds a stack of packs, so
     * ours is appended and nobody else's is disturbed.
     *
     * <p>Package-private and static so the shape of the request is testable without a server.
     */
    static ResourcePackRequest request(Resolved resolved, ResourcePackSettings settings) {
        ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(
                        PACK_ID, URI.create(resolved.url()), resolved.sha1()))
                .required(settings.required())
                .replace(false);
        if (!settings.prompt().isBlank()) {
            request.prompt(Text.mini(settings.prompt()));
        }
        return request.asResourcePackRequest();
    }

    private void send(Player player, Resolved current) {
        try {
            player.sendResourcePacks(request(current, configs.get().pack()));
        } catch (Exception e) {
            // A failure here must not interfere with joining.
            logger.warning("Could not send the resource pack to " + player.getName()
                    + ": " + e.getMessage());
        }
    }

    /** Registers this and the tracker. Kept here so the plugin class stays a wiring list. */
    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(tracker, plugin);
    }
}
