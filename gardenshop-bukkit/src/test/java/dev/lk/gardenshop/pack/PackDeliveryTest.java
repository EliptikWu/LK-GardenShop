package dev.lk.gardenshop.pack;

import dev.lk.gardenshop.core.config.PackMode;
import dev.lk.gardenshop.core.config.ResourcePackSettings;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How our pack behaves next to the other packs a server may already be sending.
 *
 * <p>Every assertion here is about a failure that is invisible from inside this plugin: the shop
 * would look perfect while ItemsAdder's, Nexo's or MythicMobs' textures quietly vanished, or while a
 * player was shown glyphs they never downloaded.
 */
class PackDeliveryTest {

    private static final String SHA1 = "81898efa863388ea9cd5012128d520cad41a0f23";

    private static ResourcePackSettings settings(String url, boolean required, String prompt) {
        return new ResourcePackSettings(true, true, Optional.of(PackMode.EXTERNAL_URL),
                url, SHA1, required, prompt, 8123, "");
    }

    private static PackDelivery.Resolved resolved(String url) {
        return new PackDelivery.Resolved(PackMode.EXTERNAL_URL, url, SHA1, "test");
    }

    // ------------------------------------------------------------------ the request

    @Test
    @DisplayName("the pack is ADDED to the client's stack, never swapped in for what it has")
    void packIsAddedNotSwappedIn() {
        ResourcePackRequest request = PackDelivery.request(
                resolved("https://example.com/pack.zip"), settings("https://example.com/pack.zip", false, ""));

        // replace(true) - which every deprecated Player#setResourcePack overload does internally by
        // sending a pop-all before its push - would strip the packs ItemsAdder, Nexo, Oraxen or
        // server.properties had already delivered. On this server that includes the crop textures,
        // so the plugin would break the very items it sells.
        assertThat(request.replace())
                .as("our cosmetic backdrop must not evict another plugin's textures")
                .isFalse();
    }

    @Test
    @DisplayName("the pack id is fixed, so changing the url does not orphan the old pack")
    void packIdDoesNotDependOnTheUrl() {
        UUID first = PackDelivery.request(resolved("http://1.2.3.4:8123/pack.zip"),
                settings("http://1.2.3.4:8123/pack.zip", false, "")).packs().getFirst().id();
        UUID second = PackDelivery.request(resolved("http://1.2.3.4:9999/pack.zip"),
                settings("http://1.2.3.4:9999/pack.zip", false, "")).packs().getFirst().id();

        // Bukkit's convenience overloads derive the id from the url. Under that scheme moving the
        // self-host port would push a second pack under a new id and leave the old one applied with
        // no way to remove it, since removal is by id.
        assertThat(first).isEqualTo(PackDelivery.PACK_ID).isEqualTo(second);
    }

    @Test
    @DisplayName("url, hash, required flag and prompt all reach the request")
    void requestCarriesTheSettings() {
        ResourcePackRequest request = PackDelivery.request(resolved("https://example.com/pack.zip"),
                settings("https://example.com/pack.zip", true, "<green>Install it"));

        assertThat(request.packs()).hasSize(1);
        assertThat(request.packs().getFirst().uri()).hasToString("https://example.com/pack.zip");
        assertThat(request.packs().getFirst().hash())
                .as("a hash the client cannot verify against reads to players as a failed download")
                .isEqualTo(SHA1);
        assertThat(request.required()).isTrue();
        assertThat(request.prompt()).isNotNull();
    }

    @Test
    @DisplayName("a blank prompt means no prompt, not an empty line in the dialog")
    void blankPromptIsOmitted() {
        assertThat(PackDelivery.request(resolved("https://example.com/pack.zip"),
                settings("https://example.com/pack.zip", false, "   ")).prompt()).isNull();
    }

    // ------------------------------------------------------------------ the tracker

    /** Which pack a status refers to only became knowable when packs started stacking, in 1.20.3. */
    @Nested
    class TrackerFiltering {

        private ServerMock server;
        private PackTracker tracker;
        private Player player;

        @BeforeEach
        void setUp() {
            server = MockBukkit.mock();
            tracker = new PackTracker();
            player = server.addPlayer();
        }

        @AfterEach
        void tearDown() {
            MockBukkit.unmock();
        }

        private void fire(UUID packId, PlayerResourcePackStatusEvent.Status status) {
            tracker.onStatus(new PlayerResourcePackStatusEvent(player, packId, status));
        }

        @Test
        @DisplayName("another plugin's pack loading does not mark the player as having ours")
        void foreignPackDoesNotCount() {
            fire(UUID.randomUUID(), PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);

            // Counting it would draw our glyphs at a player who never downloaded them, which renders
            // as a row of missing-character boxes.
            assertThat(tracker.hasPack(player.getUniqueId())).isFalse();
        }

        @Test
        @DisplayName("our pack loading does mark the player")
        void ourPackCounts() {
            fire(PackDelivery.PACK_ID, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);

            assertThat(tracker.hasPack(player.getUniqueId())).isTrue();
        }

        @Test
        @DisplayName("another plugin's pack failing does not un-mark a player who has ours")
        void foreignFailureDoesNotUnmark() {
            fire(PackDelivery.PACK_ID, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
            fire(UUID.randomUUID(), PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD);

            assertThat(tracker.hasPack(player.getUniqueId()))
                    .as("a player with our art must keep it when someone else's download breaks")
                    .isTrue();
        }

        @Test
        @DisplayName("our own pack failing after a success does un-mark them")
        void ourFailureUnmarks() {
            fire(PackDelivery.PACK_ID, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
            fire(PackDelivery.PACK_ID, PlayerResourcePackStatusEvent.Status.FAILED_RELOAD);

            assertThat(tracker.hasPack(player.getUniqueId())).isFalse();
        }
    }
}
