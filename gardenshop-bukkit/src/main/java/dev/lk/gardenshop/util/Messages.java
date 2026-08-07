package dev.lk.gardenshop.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Player-facing strings, in the configured language.
 *
 * <p>Translations ship <b>inside the jar</b> under {@code lang/messages_<code>.yml} and are chosen
 * with {@code config.yml → language}. They are deliberately not extracted to the plugin folder: this
 * is text, not configuration, and a folder full of files nobody is meant to edit only makes the two
 * files that do matter harder to find.
 *
 * <p>An admin who wants to change the wording is not locked out. A file dropped at
 * {@code lang/messages_<code>.yml} in the plugin folder wins over the bundled one, and any language
 * code works that way — not only the ones shipped.
 *
 * <p>A translation missing a key falls back to <b>English</b>, not to the raw key name. A half-finished
 * translation should read oddly, not show players {@code gui.sell-menu.held.name}.
 */
public final class Messages {

    /** Shipped in the jar. Any other code needs a file in the plugin folder. */
    public static final List<String> BUNDLED_LANGUAGES = List.of("es", "en");

    /** Back-fills missing keys, and the last resort for an unknown language code. */
    public static final String FALLBACK_LANGUAGE = "en";

    private static final String PREFIX_KEY = "prefix";

    private final Plugin plugin;
    private final Logger logger;
    private final AtomicReference<YamlConfiguration> live = new AtomicReference<>(new YamlConfiguration());
    private final AtomicReference<String> activeLanguage = new AtomicReference<>(FALLBACK_LANGUAGE);

    public Messages(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /** The language actually in use, which may differ from the one asked for. */
    public String language() {
        return activeLanguage.get();
    }

    /**
     * Loads a language.
     *
     * <p>A failure leaves the previous messages in place, for the same reason a broken
     * {@code pricing.yml} does not take the shop down.
     *
     * @param language code from {@code config.yml}; unknown falls back to {@value #FALLBACK_LANGUAGE}
     * @return {@code true} when something usable was loaded
     */
    public boolean reload(String language) {
        String requested = normalise(language);
        YamlConfiguration loaded = read(requested);
        String resolved = requested;

        if (loaded == null) {
            logger.warning("No messages found for language '" + requested + "'. Bundled languages are "
                    + BUNDLED_LANGUAGES + "; for anything else drop lang/messages_" + requested
                    + ".yml in the plugin folder. Falling back to " + FALLBACK_LANGUAGE + ".");
            resolved = FALLBACK_LANGUAGE;
            loaded = read(resolved);
        }
        if (loaded == null) {
            logger.severe("Even the bundled " + FALLBACK_LANGUAGE
                    + " messages could not be read. This build is broken.");
            return false;
        }

        // English back-fills whatever a translation has not caught up with yet.
        if (!FALLBACK_LANGUAGE.equals(resolved)) {
            YamlConfiguration fallback = fromJar(FALLBACK_LANGUAGE);
            if (fallback != null) {
                loaded.setDefaults(fallback);
                loaded.options().copyDefaults(false);
            }
        }

        live.set(loaded);
        activeLanguage.set(resolved);
        return true;
    }

    /** Whether this message has been silenced (set to an empty string). */
    public boolean isSilenced(String key) {
        return raw(key).isBlank();
    }

    public Component get(String key, TagResolver... resolvers) {
        return Text.mini(raw(key), withPrefix(resolvers));
    }

    /**
     * The same message with all formatting stripped.
     *
     * <p>For places that need the words as data rather than as display — inside another message's
     * placeholder, for instance, where nested colour codes would leak.
     */
    public String plain(String key, TagResolver... resolvers) {
        return Text.plain(get(key, resolvers));
    }

    /** A multi-line message; also accepts a single string, which yields one line. */
    public List<Component> getList(String key, TagResolver... resolvers) {
        YamlConfiguration yaml = live.get();
        TagResolver[] all = withPrefix(resolvers);

        if (yaml.isList(key)) {
            List<Component> lines = new ArrayList<>();
            for (String line : yaml.getStringList(key)) {
                lines.add(Text.mini(line, all));
            }
            return lines;
        }
        String single = raw(key);
        return single.isBlank() ? List.of() : List.of(Text.mini(single, all));
    }

    public void send(CommandSender target, String key, TagResolver... resolvers) {
        if (isSilenced(key)) {
            return;
        }
        target.sendMessage(get(key, resolvers));
    }

    public void sendList(CommandSender target, String key, TagResolver... resolvers) {
        for (Component line : getList(key, resolvers)) {
            target.sendMessage(line);
        }
    }

    // ------------------------------------------------------------------- loading

    private static String normalise(String language) {
        if (language == null || language.isBlank()) {
            return FALLBACK_LANGUAGE;
        }
        // Only a bare code: a path separator here would read files outside the plugin folder.
        return language.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    private static String fileName(String language) {
        return "messages_" + language + ".yml";
    }

    /** Folder override first, then the jar. Returns {@code null} when neither has this language. */
    private YamlConfiguration read(String language) {
        YamlConfiguration override = fromFolder(language);
        return override != null ? override : fromJar(language);
    }

    private YamlConfiguration fromFolder(String language) {
        File file = new File(new File(plugin.getDataFolder(), "lang"), fileName(language));
        if (!file.exists()) {
            return null;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            logger.info(() -> "Using your " + file.getName() + " from the plugin folder.");
            return yaml;
        } catch (InvalidConfigurationException e) {
            logger.severe(file + " is not valid YAML, ignoring it: " + e.getMessage());
            return null;
        } catch (IOException e) {
            logger.severe(file + " could not be read, ignoring it: " + e.getMessage());
            return null;
        }
    }

    private YamlConfiguration fromJar(String language) {
        try (InputStream stream = plugin.getResource("lang/" + fileName(language))) {
            if (stream == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            logger.warning("Could not read the bundled " + fileName(language) + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * The raw MiniMessage for a key.
     *
     * <p>Deliberately the <b>single-argument</b> {@code getString}. Bukkit only walks the defaults
     * chain when no explicit default is supplied: passing one — as {@code getString(key, key)} does —
     * short-circuits it, which silently killed the English back-fill and made every key a translation
     * had not caught up with print its own name.
     *
     * <p>The key name is still the last resort, because a missing message showing as
     * {@code sell.success} is noticed immediately, where an empty line is not.
     */
    private String raw(String key) {
        String value = live.get().getString(key);
        return value != null ? value : key;
    }

    private TagResolver[] withPrefix(TagResolver[] resolvers) {
        // Same reasoning as raw(): no explicit default, so an override that omits the prefix
        // inherits the bundled one instead of losing it.
        String prefixValue = live.get().getString(PREFIX_KEY);
        TagResolver prefix = Placeholder.component(PREFIX_KEY,
                Text.mini(prefixValue == null ? "" : prefixValue));
        TagResolver[] all = new TagResolver[resolvers.length + 1];
        all[0] = prefix;
        System.arraycopy(resolvers, 0, all, 1, resolvers.length);
        return all;
    }
}
