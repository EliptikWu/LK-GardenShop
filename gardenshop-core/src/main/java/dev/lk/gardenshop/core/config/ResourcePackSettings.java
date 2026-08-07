package dev.lk.gardenshop.core.config;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Resource pack delivery, from {@code config.yml → resource-pack}.
 *
 * @param installed    do players have the pack, by any means? The one switch for "this server
 *                     ships no pack": false turns off every piece of art at once so nothing
 *                     renders as an empty glyph box
 * @param enabled      send the pack automatically on join
 * @param configured   the mode the owner explicitly asked for. <b>Empty means auto-pick</b>, which
 *                     is the normal case — and it has to be distinguishable from an explicit
 *                     choice, or setting {@code mode: bundled-self-host} alongside a {@code url}
 *                     would silently be overridden
 * @param url          external zip URL, used by {@link PackMode#EXTERNAL_URL}
 * @param sha1         40 hex characters. The client rejects a pack whose bytes disagree, so a
 *                     stale hash here is the usual cause of "the pack won't download"
 * @param required     kick players who decline
 * @param prompt       MiniMessage shown in the client's accept dialog
 * @param selfHostPort TCP port for {@link PackMode#BUNDLED_SELF_HOST}; must be reachable from
 *                     the internet
 * @param publicHost   hostname or IP clients should download from. Blank means "work it out from
 *                     the server's own address", which is wrong behind NAT often enough to be
 *                     worth overriding
 */
public record ResourcePackSettings(
        boolean installed,
        boolean enabled,
        Optional<PackMode> configured,
        String url,
        String sha1,
        boolean required,
        String prompt,
        int selfHostPort,
        String publicHost
) {

    public ResourcePackSettings {
        Objects.requireNonNull(configured, "configured");
        url = url == null ? "" : url.trim();
        sha1 = sha1 == null ? "" : sha1.trim().toLowerCase(Locale.ROOT);
        prompt = prompt == null ? "" : prompt;
        publicHost = publicHost == null ? "" : publicHost.trim();

        if (selfHostPort < 0 || selfHostPort > 65535) {
            throw new IllegalArgumentException("self-host port must be 0-65535, got " + selfHostPort);
        }
    }

    public static ResourcePackSettings defaults() {
        return new ResourcePackSettings(true, true, Optional.empty(),
                "", "", false, "", 8123, "");
    }

    /** The mode in force: what was asked for, or the auto-pick when nothing was. */
    public PackMode mode() {
        if (!enabled) {
            return PackMode.NONE;
        }
        return configured.orElseGet(() -> PackMode.autoPick(hasUrl()));
    }

    public boolean hasUrl() {
        return !url.isEmpty();
    }

    /** Whether the configured hash looks like a SHA-1 at all, before a client rejects it. */
    public boolean hasValidSha1() {
        return sha1.length() == 40 && sha1.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }
}
