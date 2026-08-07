package dev.lk.gardenshop.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the startup banner actually renders.
 *
 * <p>A banner is the one piece of output nobody notices is broken: a missing message key just
 * prints its own name, and a mis-nested MiniMessage tag just prints as text. Both look like
 * decoration until someone reads it closely. Run with {@code -PshowTestOutput} to see it in
 * colour.
 */
class ConsoleBannerTest {

    private Messages messages;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin("LKGardenShopTest");

        // Nothing to stage: messages live in the jar under lang/, so this reads the real
        // shipped file rather than a copy the test had to place.
        messages = new Messages(plugin);
        assertThat(messages.reload("en")).isTrue();
        assertThat(messages.language()).isEqualTo("en");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static List<ConsoleBanner.Entry> checklist() {
        return List.of(
                ConsoleBanner.Entry.ok("Crops", "6 species, 180 drop types"),
                ConsoleBanner.Entry.ok("Economy", "Vault -> Essentials"),
                ConsoleBanner.Entry.warn("MythicMobs", "3 configured type(s) missing from the pack"),
                ConsoleBanner.Entry.ok("Menu", "/gardenshop opens the shop"),
                ConsoleBanner.Entry.off("Placeholders", "PlaceholderAPI not installed"),
                ConsoleBanner.Entry.ok("Statistics", "0 player(s) tracked"));
    }

    @Test
    @DisplayName("every banner message key resolves - no raw key names leak through")
    void noMissingKeys() {
        List<Component> lines = new ConsoleBanner(messages).lines("1.0.0", checklist(), 142, true);

        assertThat(lines).isNotEmpty();
        for (Component line : lines) {
            String plain = Text.plain(line);
            // Messages falls back to the key itself when one is missing, so a line that reads
            // "console.header" is the signature of a typo in messages.yml.
            assertThat(plain)
                    .as("a raw message key leaked into the banner")
                    .doesNotContain("console.")
                    .doesNotContain("<gradient")
                    .doesNotContain("</");
        }
    }

    @Test
    @DisplayName("the checklist shows one row per subsystem, with its detail")
    void everySubsystemAppears() {
        List<Component> lines = new ConsoleBanner(messages).lines("1.0.0", checklist(), 142, true);
        String whole = String.join("\n", lines.stream().map(Text::plain).toList());

        assertThat(whole)
                .contains("Crops").contains("180 drop types")
                .contains("Economy").contains("Vault -> Essentials")
                .contains("MythicMobs").contains("missing from the pack")
                .contains("Placeholders").contains("not installed")
                .contains("1.0.0")
                .contains("142");
    }

    @Test
    @DisplayName("problems are counted, so the footer says whether anything needs attention")
    void countsProblems() {
        ConsoleBanner banner = new ConsoleBanner(messages);
        String withProblems = plainOf(banner.lines("1.0.0", checklist(), 10, true));
        String allGood = plainOf(banner.lines("1.0.0",
                List.of(ConsoleBanner.Entry.ok("Crops", "fine")), 10, true));

        // The fixture has one WARN and one OFF.
        assertThat(withProblems).contains("2 thing(s) to look at");
        assertThat(allGood).contains("0 thing(s) to look at");
    }

    @Test
    @DisplayName("labels are padded to a common width so the detail column lines up")
    void detailColumnAligns() {
        List<Component> lines = new ConsoleBanner(messages).lines("1.0.0", checklist(), 10, true);

        List<Integer> detailStarts = lines.stream()
                .map(Text::plain)
                .filter(line -> line.contains("species") || line.contains("Vault")
                        || line.contains("not installed"))
                .map(line -> line.indexOf(detailOf(line)))
                .toList();

        assertThat(detailStarts).hasSize(3);
        assertThat(detailStarts).as("ragged detail column").containsOnly(detailStarts.getFirst());
    }

    @Test
    @DisplayName("turning the banner off leaves exactly one summary line")
    void disabledPrintsOneLine() {
        List<Component> lines = new ConsoleBanner(messages).lines("1.0.0", checklist(), 99, false);

        assertThat(lines).hasSize(1);
        assertThat(Text.plain(lines.getFirst())).contains("1.0.0").contains("99");
    }

    @Test
    @DisplayName("renders it (./gradlew test -PshowTestOutput)")
    void render() {
        List<Component> lines = new ConsoleBanner(messages).lines("1.0.0", checklist(), 142, true);

        System.out.println();
        for (Component line : lines) {
            // The same serializer the Paper console uses, so this is what an admin sees.
            System.out.println(ANSIComponentSerializer.ansi().serialize(line));
        }
        System.out.println();
    }

    private static String plainOf(List<Component> lines) {
        return String.join("\n", lines.stream().map(Text::plain).toList());
    }

    /** The detail text of a checklist row, i.e. everything after the padded label. */
    private static String detailOf(String line) {
        if (line.contains("species")) {
            return "6 species, 180 drop types";
        }
        if (line.contains("Vault")) {
            return "Vault -> Essentials";
        }
        return "PlaceholderAPI not installed";
    }
}
