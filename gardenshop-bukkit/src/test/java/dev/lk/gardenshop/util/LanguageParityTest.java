package dev.lk.gardenshop.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the shipped translations in step with each other.
 *
 * <p>A key missing from a translation is a silent defect: the plugin falls back to English, so the
 * only symptom is one line of the wrong language buried in an otherwise translated menu. Nobody
 * notices until a player mentions it. A key present in a translation but <em>not</em> in English is
 * worse — it is dead weight nothing will ever read, usually left behind by a rename.
 */
class LanguageParityTest {

    private static YamlConfiguration bundled(String language) {
        String path = "lang/messages_" + language + ".yml";
        try (InputStream stream = LanguageParityTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as("%s is missing from the jar", path).isNotNull();
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Every leaf path, ignoring intermediate sections.
     *
     * <p>Sections are skipped because they carry no text: comparing them would flag a purely
     * structural difference as a translation gap.
     */
    private static Set<String> leafKeys(ConfigurationSection section) {
        Set<String> keys = new TreeSet<>();
        for (String key : section.getKeys(true)) {
            if (!section.isConfigurationSection(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    @Test
    @DisplayName("Spanish and English carry exactly the same keys")
    void translationsAreInStep() {
        Set<String> spanish = leafKeys(bundled("es"));
        Set<String> english = leafKeys(bundled("en"));

        assertThat(spanish).as("keys in English but missing from Spanish").containsAll(english);
        assertThat(english).as("keys in Spanish that English does not have - probably a stale rename")
                .containsAll(spanish);
    }

    @Test
    @DisplayName("both files actually contain messages")
    void neitherIsEmpty() {
        // Guards against the parity check passing trivially because a file failed to parse.
        assertThat(leafKeys(bundled("es"))).hasSizeGreaterThan(80);
        assertThat(leafKeys(bundled("en"))).hasSizeGreaterThan(80);
    }

    @Test
    @DisplayName("the keys the code asks for by name are present in every language")
    void keysUsedInCodeExist() {
        // Not exhaustive - just the ones whose absence would be most visible, and the ones most
        // likely to be forgotten when a menu changes.
        Set<String> required = Set.of(
                "prefix", "no-permission", "players-only", "unknown-subcommand",
                "console.entry", "console.summary", "console.status.ok", "console.status.warn",
                // 'disabled' and not 'off': YAML 1.1 reads a bare off/on/yes/no as a boolean.
                "console.status.disabled",
                "sell.success", "sell.deposit-failed", "sell.nothing",
                "value.header", "value.total",
                "favorite.added", "favorite.removed",
                "reload.success", "reload.failed",
                "prices.header", "prices.crop", "prices.type",
                "gui.sell-menu.title", "gui.sell-menu.sell-all.name",
                "gui.confirm-menu.title", "gui.confirm-menu.total-changed",
                "gui.price-book.title", "gui.price-book.back-to-shop",
                "gui.reason.not-a-crop", "gui.reason.nothing-to-sell",
                "info.header", "info.economy", "info.pack");

        for (String language : Messages.BUNDLED_LANGUAGES) {
            Set<String> keys = leafKeys(bundled(language));
            assertThat(keys).as("keys missing from messages_%s.yml", language).containsAll(required);
        }
    }

    @Test
    @DisplayName("no message key is a YAML boolean in disguise")
    void noBooleanKeys() {
        // 'off', 'on', 'yes' and 'no' are booleans in YAML 1.1, so such a key parses to true/false
        // and every lookup for it misses. This shipped once already.
        Set<String> hazards = Set.of("off", "on", "yes", "no", "true", "false", "y", "n");

        for (String language : Messages.BUNDLED_LANGUAGES) {
            for (String key : leafKeys(bundled(language))) {
                String leaf = key.substring(key.lastIndexOf('.') + 1);
                assertThat(hazards).as("'%s' in messages_%s.yml is read as a boolean by YAML 1.1",
                        key, language).doesNotContain(leaf);
            }
        }
    }
}
