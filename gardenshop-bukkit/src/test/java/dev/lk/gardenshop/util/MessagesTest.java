package dev.lk.gardenshop.util;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Language selection, the folder override, and the English back-fill. */
class MessagesTest {

    private Plugin plugin;
    private Messages messages;
    private Path langFolder;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("LKGardenShopTest");
        messages = new Messages(plugin);
        langFolder = plugin.getDataFolder().toPath().resolve("lang");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void writeOverride(String language, String yaml) {
        try {
            Files.createDirectories(langFolder);
            Files.writeString(langFolder.resolve("messages_" + language + ".yml"), yaml,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("both bundled languages load from the jar")
    void bundledLanguagesLoad() {
        for (String language : Messages.BUNDLED_LANGUAGES) {
            assertThat(messages.reload(language)).as("loading %s", language).isTrue();
            assertThat(messages.language()).isEqualTo(language);
            assertThat(messages.plain("no-permission")).isNotBlank().doesNotContain("no-permission");
        }
    }

    @Test
    @DisplayName("English is the shipped default, and it is the fallback too")
    void englishIsDefault() {
        // The list's head is what a config.yml with no 'language' key gets. Keeping it equal to the
        // fallback means an untouched server reads in one language rather than in two: the previous
        // default was Spanish, so any key a translation had not caught up with appeared in English
        // in the middle of Spanish text.
        assertThat(Messages.BUNDLED_LANGUAGES.getFirst())
                .isEqualTo("en")
                .isEqualTo(Messages.FALLBACK_LANGUAGE);

        messages.reload("en");
        assertThat(messages.plain("no-permission")).containsIgnoringCase("permission");
    }

    @Test
    @DisplayName("Spanish is genuinely translated, not a copy of the English file")
    void spanishIsTranslated() {
        assertThat(Messages.BUNDLED_LANGUAGES).contains("es");

        messages.reload("es");
        assertThat(messages.language()).isEqualTo("es");
        // Cheap, but it is the check that catches a file duplicated and never translated -- which
        // LanguageParityTest cannot see, since a copy has exactly the right keys.
        assertThat(messages.plain("no-permission")).containsIgnoringCase("permiso");
        assertThat(messages.plain("sell.nothing"))
                .containsIgnoringCase("cosecha")
                .doesNotContainIgnoringCase("holding");
    }

    @Test
    @DisplayName("an unknown language falls back to English rather than failing to load")
    void unknownLanguageFallsBack() {
        assertThat(messages.reload("fr")).isTrue();
        assertThat(messages.language())
                .as("a typo in config.yml must not leave the plugin mute")
                .isEqualTo(Messages.FALLBACK_LANGUAGE);
        assertThat(messages.plain("no-permission")).containsIgnoringCase("permission");
    }

    @Test
    @DisplayName("a blank or null language is treated as unset, not as a code")
    void blankLanguageIsHandled() {
        assertThat(messages.reload("")).isTrue();
        assertThat(messages.reload(null)).isTrue();
        assertThat(messages.language()).isEqualTo(Messages.FALLBACK_LANGUAGE);
    }

    @Test
    @DisplayName("a file in the plugin folder wins over the bundled one")
    void folderOverrideWins() {
        writeOverride("es", "no-permission: 'MINE'\n");

        assertThat(messages.reload("es")).isTrue();
        assertThat(messages.plain("no-permission")).isEqualTo("MINE");
    }

    @Test
    @DisplayName("an override missing a key falls back to English, not to the raw key name")
    void overrideBackFillsFromEnglish() {
        // This is what keeps a half-finished translation readable: the one key it defines is used,
        // and everything else reads in English rather than printing 'sell.success' at a player.
        writeOverride("es", "no-permission: 'MINE'\n");
        messages.reload("es");

        assertThat(messages.plain("no-permission")).isEqualTo("MINE");
        assertThat(messages.plain("sell.success"))
                .doesNotContain("sell.success")
                .containsIgnoringCase("sold");
    }

    @Test
    @DisplayName("an override for an unbundled language works, so any locale can be added")
    void overrideForNewLanguage() {
        writeOverride("fr", "no-permission: 'Interdit'\n");

        assertThat(messages.reload("fr")).isTrue();
        assertThat(messages.language()).isEqualTo("fr");
        assertThat(messages.plain("no-permission")).isEqualTo("Interdit");
        // Everything it does not define still reads, in English.
        assertThat(messages.plain("sell.nothing")).doesNotContain("sell.nothing");
    }

    @Test
    @DisplayName("a broken override is ignored in favour of the bundled file")
    void brokenOverrideIsIgnored() {
        messages.reload("en");
        writeOverride("en", "no-permission: 'x'\n  bad: [[[\n");

        assertThat(messages.reload("en")).as("a typo in an override must not mute the plugin").isTrue();
        assertThat(messages.plain("no-permission")).isNotBlank().doesNotContain("no-permission");
    }

    @Test
    @DisplayName("a language code cannot escape the lang folder")
    void languageCodeIsSanitised() {
        // A path separator here would read arbitrary files off disk.
        assertThat(messages.reload("../../../etc/passwd")).isTrue();
        assertThat(messages.language()).doesNotContain("/").doesNotContain("..");
    }

    @Test
    @DisplayName("a silenced message sends nothing")
    void silencedMessages() {
        writeOverride("en", "no-permission: ''\n");
        messages.reload("en");

        assertThat(messages.isSilenced("no-permission")).isTrue();
    }

    @Test
    @DisplayName("list messages come back line by line")
    void listMessages() {
        messages.reload("en");

        assertThat(messages.getList("help")).hasSizeGreaterThan(3);
        // A single string is still one line, so callers need no special case.
        assertThat(messages.getList("no-permission")).hasSize(1);
    }
}
