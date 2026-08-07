package dev.lk.gardenshop.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourcePackSettingsTest {

    private static ResourcePackSettings with(Optional<PackMode> mode, String url, boolean enabled) {
        return new ResourcePackSettings(true, enabled, mode, url,
                "0123456789abcdef0123456789abcdef01234567", false, "", 8123, "");
    }

    @Test
    @DisplayName("a configured url means hosting is solved, so auto-pick uses it")
    void autoPicksExternalWhenUrlIsSet() {
        assertThat(with(Optional.empty(), "https://example.test/pack.zip", true).mode())
                .isEqualTo(PackMode.EXTERNAL_URL);
    }

    @Test
    @DisplayName("with no url, auto-pick serves the pack itself")
    void autoPicksSelfHostWithoutUrl() {
        assertThat(with(Optional.empty(), "", true).mode()).isEqualTo(PackMode.BUNDLED_SELF_HOST);
    }

    @Test
    @DisplayName("an explicit mode is never silently overridden by the auto-pick")
    void explicitModeWins() {
        // This is the bug the Optional exists to prevent: asking for self-host while a url happens
        // to be filled in must not quietly become external-url.
        assertThat(with(Optional.of(PackMode.BUNDLED_SELF_HOST), "https://example.test/pack.zip", true).mode())
                .isEqualTo(PackMode.BUNDLED_SELF_HOST);
        assertThat(with(Optional.of(PackMode.EXTERNAL_URL), "", true).mode())
                .isEqualTo(PackMode.EXTERNAL_URL);
    }

    @Test
    @DisplayName("sending disabled beats any mode")
    void disabledSendsNothing() {
        assertThat(with(Optional.of(PackMode.EXTERNAL_URL), "https://example.test/pack.zip", false).mode())
                .isEqualTo(PackMode.NONE);
        assertThat(with(Optional.empty(), "", false).mode()).isEqualTo(PackMode.NONE);
    }

    @Test
    @DisplayName("a hash is only accepted as 40 hex characters")
    void sha1IsValidated() {
        // A wrong hash is worse than none: the client downloads, rejects the bytes, and the player
        // just sees a failed download with no reason given.
        assertThat(with(Optional.empty(), "", true).hasValidSha1()).isTrue();

        assertThat(defaultsWithSha("").hasValidSha1()).isFalse();
        assertThat(defaultsWithSha("abc").hasValidSha1()).isFalse();
        assertThat(defaultsWithSha("z123456789abcdef0123456789abcdef01234567").hasValidSha1()).isFalse();
        assertThat(defaultsWithSha("0123456789abcdef0123456789abcdef012345678").hasValidSha1()).isFalse();
    }

    @Test
    @DisplayName("a hash is normalised to lower case, since that is what the check compares")
    void sha1IsNormalised() {
        assertThat(defaultsWithSha("ABCDEF0123456789ABCDEF0123456789ABCDEF01").sha1())
                .isEqualTo("abcdef0123456789abcdef0123456789abcdef01");
    }

    @Test
    @DisplayName("nulls become blanks rather than exploding later")
    void nullsAreTolerated() {
        ResourcePackSettings settings = new ResourcePackSettings(
                true, true, Optional.empty(), null, null, false, null, 8123, null);

        assertThat(settings.url()).isEmpty();
        assertThat(settings.sha1()).isEmpty();
        assertThat(settings.prompt()).isEmpty();
        assertThat(settings.publicHost()).isEmpty();
        assertThat(settings.hasUrl()).isFalse();
    }

    @Test
    @DisplayName("an impossible port is rejected at construction")
    void portIsRangeChecked() {
        assertThatThrownBy(() -> new ResourcePackSettings(
                true, true, Optional.empty(), "", "", false, "", 70000, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0-65535");
    }

    @Test
    @DisplayName("mode names parse from config, including the shorthands")
    void modeParsing() {
        assertThat(PackMode.parse("external-url")).contains(PackMode.EXTERNAL_URL);
        assertThat(PackMode.parse("url")).contains(PackMode.EXTERNAL_URL);
        assertThat(PackMode.parse("bundled_self_host")).contains(PackMode.BUNDLED_SELF_HOST);
        assertThat(PackMode.parse("self-host")).contains(PackMode.BUNDLED_SELF_HOST);
        assertThat(PackMode.parse("none")).contains(PackMode.NONE);

        // Blank must be empty, not a default: blank is how "pick for me" is expressed.
        assertThat(PackMode.parse("")).isEmpty();
        assertThat(PackMode.parse("hosted")).as("the reference's third mode is not implemented").isEmpty();
    }

    @Test
    @DisplayName("every mode round-trips through its config value")
    void modeConfigValuesRoundTrip() {
        for (PackMode mode : PackMode.values()) {
            assertThat(PackMode.parse(mode.configValue())).contains(mode);
        }
    }

    private static ResourcePackSettings defaultsWithSha(String sha1) {
        return new ResourcePackSettings(true, true, Optional.empty(), "", sha1, false, "", 8123, "");
    }
}
