package dev.lk.gardenshop.config;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one file this plugin writes.
 *
 * <p>It is written by a command and read by the config loader, which makes the round trip the thing
 * worth testing: a binding that saves but does not load back is a command that reports success and
 * changes nothing.
 */
class AdapterBindingsTest {

    private Plugin plugin;
    private AdapterBindings bindings;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("LKGardenShopTest");
        bindings = new AdapterBindings(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Path file() {
        return plugin.getDataFolder().toPath().resolve(AdapterBindings.FILE);
    }

    @Test
    @DisplayName("the file does not exist until something is bound")
    void absentUntilFirstBind() throws IOException {
        // A server that only grows the Mythic pack should never see this file. Extracting it at
        // startup would put a file nobody needs next to the three that matter.
        assertThat(bindings.exists()).isFalse();
        assertThat(bindings.load()).isEmpty();

        bindings.bind("ia:mygarden:tomato", "odre");

        assertThat(bindings.exists()).isTrue();
    }

    @Test
    @DisplayName("a binding survives the round trip")
    void roundTrip() throws IOException {
        bindings.bind("ia:mygarden:tomato", "odre");
        bindings.bind("nx:pepper", "chilli");

        assertThat(new AdapterBindings(plugin).load())
                .containsEntry("ia:mygarden:tomato", "odre")
                .containsEntry("nx:pepper", "chilli")
                .hasSize(2);
    }

    @Test
    @DisplayName("ids are normalised on the way in, so case and spacing cannot cause a double entry")
    void idsAreNormalised() throws IOException {
        bindings.bind("  IA:MyGarden:Tomato  ", "odre");
        bindings.bind("ia:mygarden:tomato", "chilli");

        assertThat(bindings.load())
                .as("the second bind must replace the first, not sit beside it")
                .containsExactly(Map.entry("ia:mygarden:tomato", "chilli"));
    }

    @Test
    @DisplayName("re-binding reports what it replaced, so the command can say so")
    void bindReportsThePrevious() throws IOException {
        assertThat(bindings.bind("ia:x:y", "odre")).isEmpty();
        assertThat(bindings.bind("ia:x:y", "chilli")).contains("odre");
    }

    @Test
    @DisplayName("unbind removes only its own entry and reports the crop")
    void unbind() throws IOException {
        bindings.bind("ia:x:y", "odre");
        bindings.bind("nx:z", "chilli");

        assertThat(bindings.unbind("IA:X:Y")).contains("odre");
        assertThat(bindings.load()).containsExactly(Map.entry("nx:z", "chilli"));
    }

    @Test
    @DisplayName("unbinding something that was never bound writes nothing")
    void unbindMissing() throws IOException {
        bindings.bind("ia:x:y", "odre");
        long written = Files.getLastModifiedTime(file()).toMillis();

        assertThat(bindings.unbind("nx:nothing")).isEmpty();
        assertThat(Files.getLastModifiedTime(file()).toMillis()).isEqualTo(written);
    }

    @Test
    @DisplayName("ids keep their colons and dots, which a map key would have eaten")
    void idsWithSeparatorsSurvive() throws IOException {
        // Bukkit treats '.' in a configuration key as a path separator, and an adapter id is a
        // foreign string we do not get to constrain -- hence a list of pairs rather than a map.
        bindings.bind("ia:my.pack:tomato.ripe", "odre");

        assertThat(new AdapterBindings(plugin).load())
                .containsExactly(Map.entry("ia:my.pack:tomato.ripe", "odre"));
    }

    @Test
    @DisplayName("a broken file is reported and treated as empty, never fatal")
    void brokenFileIsSurvivable() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "bindings: [[[ not yaml\n", StandardCharsets.UTF_8);

        List<String> problems = new ArrayList<>();
        Map<String, String> loaded = bindings.load(problems::add);

        // The worst a machine-written file should be able to cost is the ids it declares.
        assertThat(loaded).isEmpty();
        assertThat(problems).hasSize(1).first().asString().contains(AdapterBindings.FILE);
    }

    @Test
    @DisplayName("entries missing a field are skipped rather than throwing")
    void incompleteEntriesAreSkipped() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), """
                bindings:
                  - id: 'ia:good:one'
                    crop: odre
                  - id: 'ia:no:crop'
                  - crop: chilli
                  - id: ''
                    crop: odre
                """, StandardCharsets.UTF_8);

        assertThat(bindings.load()).containsExactly(Map.entry("ia:good:one", "odre"));
    }

    @Test
    @DisplayName("the written file explains itself, since a human may well open it")
    void fileCarriesItsHeader() throws IOException {
        bindings.bind("ia:x:y", "odre");

        assertThat(Files.readString(file(), StandardCharsets.UTF_8))
                .contains("/gs adapter bind")
                .contains("PLAIN drop");
    }
}
