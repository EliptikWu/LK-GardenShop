package dev.lk.gardenshop.core.config;

import java.util.Locale;
import java.util.Optional;

/** How the resource pack reaches players, from {@code config.yml → resource-pack.mode}. */
public enum PackMode {

    /**
     * The plugin serves its own bundled pack over a small built-in HTTP server.
     *
     * <p>Needs a TCP port that is <b>open to the internet</b>, separate from the Minecraft port.
     * On a managed host that means asking for an extra allocation.
     */
    BUNDLED_SELF_HOST,

    /** The owner hosts the zip and supplies {@code url} + {@code sha1}. Needs no open port. */
    EXTERNAL_URL,

    /** Nothing is sent. Another plugin serves the pack, or the server ships none. */
    NONE;

    /**
     * Resolves the mode when {@code mode} is left blank, which is the normal case.
     *
     * <p>A configured {@code url} is taken as the owner having already solved hosting, so it wins.
     * Otherwise the plugin falls back to serving the pack itself, which works with no setup beyond
     * an open port.
     */
    public static PackMode autoPick(boolean hasUrl) {
        return hasUrl ? EXTERNAL_URL : BUNDLED_SELF_HOST;
    }

    public static Optional<PackMode> parse(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return switch (id.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "bundled-self-host", "self-host", "bundled" -> Optional.of(BUNDLED_SELF_HOST);
            case "external-url", "external", "url" -> Optional.of(EXTERNAL_URL);
            case "none", "off", "disabled" -> Optional.of(NONE);
            default -> Optional.empty();
        };
    }

    public String configValue() {
        return switch (this) {
            case BUNDLED_SELF_HOST -> "bundled-self-host";
            case EXTERNAL_URL -> "external-url";
            case NONE -> "none";
        };
    }
}
