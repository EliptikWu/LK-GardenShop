package dev.lk.gardenshop.pack;

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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upgrade story for the bundled pack.
 *
 * <p>Two obvious rules are both wrong here — always overwriting destroys an admin's edited art on
 * every restart, never overwriting means a plugin update ships art nobody sees — so the marker file
 * decides. Each of its three branches is worth pinning: getting one wrong either loses someone's
 * work or leaves the pack permanently stale, and neither shows up as an error.
 */
class PackBundleInstallerTest {

    private Plugin plugin;
    private PackBundleInstaller installer;
    private Path packFolder;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("LKGardenShopTest");
        installer = new PackBundleInstaller(plugin);
        packFolder = plugin.getDataFolder().toPath().resolve("resourcepack");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Path pack() {
        return packFolder.resolve("pack.zip");
    }

    private Path marker() {
        return packFolder.resolve(".bundled-sha1");
    }

    /** The hash of the pack.zip actually built into this jar, whatever it currently is. */
    private String bundledSha() throws IOException {
        try (var stream = plugin.getResource("pack.zip")) {
            assertThat(stream).as("pack.zip should be on the classpath - run ./gradlew packZip").isNotNull();
            return PackBundleInstaller.sha1(stream.readAllBytes());
        }
    }

    @Test
    @DisplayName("first run extracts the pack and records its hash")
    void firstRunExtracts() throws IOException {
        Optional<PackBundleInstaller.InstalledPack> installed = installer.install();

        assertThat(installed).isPresent();
        assertThat(pack()).exists();
        assertThat(installed.get().sha1()).isEqualTo(bundledSha());
        assertThat(installed.get().customised()).isFalse();
        assertThat(Files.readString(marker(), StandardCharsets.UTF_8).trim()).isEqualTo(bundledSha());
    }

    @Test
    @DisplayName("an unchanged pack is left alone")
    void secondRunIsANoOp() throws IOException {
        installer.install();
        long firstWrite = Files.getLastModifiedTime(pack()).toMillis();

        Optional<PackBundleInstaller.InstalledPack> again = installer.install();

        assertThat(again).isPresent();
        assertThat(again.get().customised()).isFalse();
        assertThat(Files.getLastModifiedTime(pack()).toMillis())
                .as("rewriting an identical file would change its hash timestamp for nothing")
                .isEqualTo(firstWrite);
    }

    @Test
    @DisplayName("an edited pack is kept, and reported as customised")
    void editedPackSurvives() throws IOException {
        installer.install();

        // The admin swaps in their own art. The marker still holds the bundled hash, so the file no
        // longer matches either it or the bundle.
        byte[] mine = "my own pack".getBytes(StandardCharsets.UTF_8);
        Files.write(pack(), mine);

        Optional<PackBundleInstaller.InstalledPack> installed = installer.install();

        assertThat(installed).isPresent();
        assertThat(Files.readAllBytes(pack()))
                .as("an admin's art must not be silently replaced")
                .isEqualTo(mine);
        assertThat(installed.get().sha1())
                .as("the hash must describe the file being served, not the one in the jar")
                .isEqualTo(PackBundleInstaller.sha1(mine));
        assertThat(installed.get().customised()).isTrue();
    }

    @Test
    @DisplayName("a plugin update replaces a pack we wrote ourselves")
    void updateReplacesOurOwnCopy() throws IOException {
        installer.install();

        // Simulates the previous version: the file and the marker agree with each other but not
        // with the bundle, which is exactly the shape of "we wrote this, then the plugin updated".
        byte[] older = "the pack from the previous release".getBytes(StandardCharsets.UTF_8);
        Files.write(pack(), older);
        Files.writeString(marker(), PackBundleInstaller.sha1(older), StandardCharsets.UTF_8);

        Optional<PackBundleInstaller.InstalledPack> installed = installer.install();

        assertThat(installed).isPresent();
        assertThat(installed.get().sha1())
                .as("new art in a release has to actually reach players")
                .isEqualTo(bundledSha());
        assertThat(installed.get().customised()).isFalse();
    }

    @Test
    @DisplayName("a deleted marker does not make an unchanged pack look edited")
    void missingMarkerIsRebuilt() throws IOException {
        installer.install();
        Files.delete(marker());

        Optional<PackBundleInstaller.InstalledPack> installed = installer.install();

        assertThat(installed.get().customised()).isFalse();
        assertThat(marker()).exists();
    }

    @Test
    @DisplayName("the hash is 40 lowercase hex characters, which is all the protocol accepts")
    void hashIsWellFormed() {
        // Not cosmetic: this string is handed straight to Adventure's ResourcePackInfo, which
        // rejects anything that is not a 40-character lowercase SHA-1, and a rejection there would
        // surface as "could not send the resource pack" once per join.
        assertThat(PackBundleInstaller.sha1("payload".getBytes(StandardCharsets.UTF_8)))
                .hasSize(40)
                .matches("[0-9a-f]{40}");

        // A digest with leading zero nibbles must still be padded to 40 characters.
        assertThat(PackBundleInstaller.sha1(new byte[0])).isEqualTo("da39a3ee5e6b4b0d3255bfef95601890afd80709");
    }
}
