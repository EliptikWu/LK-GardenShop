package dev.lk.gardenshop.config;

import dev.lk.gardenshop.core.ConfigSnapshot;
import dev.lk.gardenshop.core.config.GuiSettings;
import dev.lk.gardenshop.core.config.MenuLayout;
import dev.lk.gardenshop.core.config.PricingMode;
import dev.lk.gardenshop.core.domain.Variant;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.IOException;
import dev.lk.gardenshop.core.config.PackMode;
import dev.lk.gardenshop.core.config.ResourcePackSettings;
import dev.lk.gardenshop.pack.PackBundleInstaller;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the promise {@code /gs reload} makes: a broken file costs you an error
 * message, never a working shop.
 *
 * <p>Files are copied from the classpath into the plugin folder by hand rather than via
 * {@code saveResource}, so these tests exercise the loader itself and not Bukkit's
 * resource plumbing.
 */
class ConfigServiceTest {

    /**
     * Only these three reach the plugin folder now. gui.yml and weights.yml are read from the jar,
     * and the messages moved to lang/ - so this list doubles as an assertion about the surface.
     */
    private static final List<String> FILES = List.of("config.yml", "crops.yml", "pricing.yml");

    private Path dataFolder;
    private ConfigService configs;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin("LKGardenShopTest");
        dataFolder = plugin.getDataFolder().toPath();

        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        FILES.forEach(this::copyBundled);

        configs = new ConfigService(new YamlConfigLoader(plugin), Logger.getLogger("test"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("the shipped defaults load cleanly into the full 180-type matrix")
    void defaultsLoad() {
        assertThat(configs.loadInitial()).isTrue();

        ConfigSnapshot snapshot = configs.snapshot();
        assertThat(snapshot.registry().size()).isEqualTo(180);
        assertThat(snapshot.species()).hasSize(6);
        assertThat(snapshot.pricing().mode()).isEqualTo(PricingMode.HYBRID);
        assertThat(snapshot.weights().size())
                .as("weights.yml should cover every derived type")
                .isEqualTo(180);
        assertThat(snapshot.report().hasErrors()).isFalse();
    }

    @Test
    @DisplayName("Odre's mythic-token is NC, and the registry keys reflect it")
    void odreUsesTheNcToken() {
        configs.loadInitial();

        assertThat(configs.snapshot().registry().contains("growGardenNCDropIceRainLightning")).isTrue();
        assertThat(configs.snapshot().registry().contains("growGardenOdreDrop"))
                .as("there is no such type in the pack")
                .isFalse();
    }

    @Test
    @DisplayName("a reload picks up an edited multiplier without a restart")
    void reloadAppliesChanges() {
        configs.loadInitial();
        BigDecimal before = configs.snapshot().pricing().variantMultiplier(Variant.RAINBOW);

        replaceInPricing("  RAINBOW: 5.0", "  RAINBOW: 50.0");
        ConfigService.ReloadOutcome outcome = configs.reload();

        assertThat(outcome.applied()).isTrue();
        assertThat(before).isEqualByComparingTo(new BigDecimal("5.0"));
        assertThat(configs.snapshot().pricing().variantMultiplier(Variant.RAINBOW))
                .isEqualByComparingTo(new BigDecimal("50.0"));
    }

    @Test
    @DisplayName("malformed YAML is rejected and the previous configuration stays live")
    void brokenYamlKeepsThePreviousSnapshot() {
        configs.loadInitial();
        ConfigSnapshot original = configs.snapshot();

        write("pricing.yml", "mode: HYBRID\n  this: is not\n valid: yaml\n   at all:\n");
        ConfigService.ReloadOutcome outcome = configs.reload();

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.report().hasErrors()).isTrue();
        assertThat(configs.snapshot())
                .as("the live snapshot must be the untouched original")
                .isSameAs(original);
        // And crucially, the shop still prices things.
        assertThat(configs.snapshot().registry().size()).isEqualTo(180);
    }

    @Test
    @DisplayName("a descending band ladder is an error, reported with its file and path")
    void descendingBandsAreRejected() {
        configs.loadInitial();
        ConfigSnapshot original = configs.snapshot();

        // Swaps the "big" rung below "normal", breaking the ascending requirement.
        replaceInPricing("    - { id: big,     max: 2.20", "    - { id: big,     max: 0.90");
        ConfigService.ReloadOutcome outcome = configs.reload();

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.report().errors())
                .anyMatch(issue -> issue.file().equals("pricing.yml")
                        && issue.message().contains("ascending"));
        assertThat(configs.snapshot()).isSameAs(original);
    }

    @Test
    @DisplayName("a per-crop override naming an unknown crop is an error, not a silent no-op")
    void unknownCropOverrideIsRejected() {
        configs.loadInitial();

        replaceInPricing("per-crop-bands: {}", """
                per-crop-bands:
                  tomato:
                    mode: RATIO
                    interpolate: true
                    bands:
                      - { id: only, max: 1.0, multiplier: 1.0, label: 'Only' }
                """);
        ConfigService.ReloadOutcome outcome = configs.reload();

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.report().errors())
                .anyMatch(issue -> issue.path().equals("per-crop-bands.tomato"));
    }

    @Test
    @DisplayName("the shipped gui.yml loads with every button inside its menu")
    void guiLayoutLoads() {
        configs.loadInitial();
        var gui = configs.snapshot().gui();

        assertThat(gui.enabled()).isTrue();
        assertThat(gui.confirmBulkSale()).as("the safety net is on by default").isTrue();
        assertThat(gui.priceBook().rows()).isEqualTo(6);
        assertThat(gui.sellMenu().slot(GuiSettings.BUTTON_SELL_ALL)).contains(40);

        // Six rows is load-bearing, not a preference: the backdrop art is 222px tall, which is
        // exactly a 6-row chest window. Any other row count and the stand does not line up.
        assertThat(gui.sellMenu().rows())
                .as("the sell menu must match the height shop_gui.png was drawn for")
                .isEqualTo(6);
        assertThat(gui.sellMenu().hasGlyph())
                .as("the sell menu is the one screen with backdrop art")
                .isTrue();
        assertThat(gui.sellMenu().glyphXOffset()).isEqualTo(GuiSettings.GLYPH_X_CENTRED_256);
        assertThat(gui.confirmMenu().hasGlyph())
                .as("no art for the 3-row confirmation screen yet")
                .isFalse();

        // Every declared button must land inside its own menu, or it silently vanishes.
        for (MenuLayout layout : List.of(gui.sellMenu(), gui.confirmMenu(), gui.priceBook())) {
            assertThat(layout.slots().values())
                    .allSatisfy(slot -> assertThat(slot).isBetween(0, layout.size() - 1));
        }
    }

    @Test
    @DisplayName("a button placed outside its menu is an error, not a button that quietly disappears")
    void outOfRangeSlotIsRejected() {
        configs.loadInitial();

        // gui.yml is not extracted, so staging a copy here is exactly what an admin who wants to
        // rearrange the menu would do.
        copyBundled("gui.yml");
        // Slot 40 does not exist in the 3-row confirmation menu.
        replaceIn("gui.yml", "    confirm: 11", "    confirm: 40");
        ConfigService.ReloadOutcome outcome = configs.reload();

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.report().errors())
                .anyMatch(issue -> issue.file().equals("gui.yml")
                        && issue.path().equals("confirm-menu.slots.confirm"));
    }

    @Test
    @DisplayName("a broken gui.yml falls back to the built-in layout instead of closing the shop")
    void brokenGuiIsRecoverable() {
        write("gui.yml", "enabled: true\n  broken: [[[\n");

        assertThat(configs.loadInitial()).isTrue();
        assertThat(configs.snapshot().gui().sellMenu().slot(GuiSettings.BUTTON_SELL_ALL))
                .as("defaults still give a usable menu")
                .isPresent();
        assertThat(configs.snapshot().report().warnings())
                .anyMatch(issue -> issue.file().equals("gui.yml"));
    }

    @Test
    @DisplayName("a broken weights.yml override falls back to the bundled one, not to nothing")
    void brokenWeightsOverrideFallsBackToBundled() {
        // weights.yml is not extracted, so a file here is an override. Breaking it should cost
        // the override, not the data: the jar still has a good copy, and using it beats
        // silently pricing 180 drop types off their crops' base ranges.
        write("weights.yml", "weights:\n  this: is not\n   valid: yaml\n");

        assertThat(configs.loadInitial()).isTrue();
        assertThat(configs.snapshot().registry().size()).isEqualTo(180);
        assertThat(configs.snapshot().weights().size())
                .as("the bundled weights should have taken over")
                .isEqualTo(180);
        assertThat(configs.snapshot().report().warnings())
                .anyMatch(issue -> issue.file().equals("weights.yml"));
    }

    @Test
    @DisplayName("background: '' turns the backdrop off instead of falling back to the default")
    void blankBackgroundDisablesArt() {
        configs.loadInitial();
        assertThat(configs.snapshot().gui().sellMenu().hasGlyph()).isTrue();

        // A present-but-blank key has to mean "none". Treating it as absent would keep the art the
        // admin just tried to remove, with no hint as to why.
        copyBundled("gui.yml");
        replaceIn("gui.yml", "  background: shop", "  background: ''");

        assertThat(configs.reload().applied()).isTrue();
        assertThat(configs.snapshot().gui().sellMenu().hasGlyph()).isFalse();
    }

    @Test
    @DisplayName("an unknown backdrop name is an error, not a silently blank menu")
    void unknownBackgroundIsRejected() {
        configs.loadInitial();

        copyBundled("gui.yml");
        replaceIn("gui.yml", "  background: shop", "  background: stall");

        ConfigService.ReloadOutcome outcome = configs.reload();
        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.report().errors())
                .anyMatch(issue -> issue.path().equals("sell-menu.background"));
    }

    @Test
    @DisplayName("gui.yml and weights.yml are never written to the plugin folder")
    void internalFilesAreNotExtracted() {
        configs.loadInitial();

        // The whole point of the trim: the folder holds only what is worth editing.
        assertThat(dataFolder.resolve("gui.yml")).doesNotExist();
        assertThat(dataFolder.resolve("weights.yml")).doesNotExist();
        assertThat(dataFolder.resolve("messages.yml")).doesNotExist();

        // And they still loaded, from the jar.
        assertThat(configs.snapshot().weights().size()).isEqualTo(180);
        assertThat(configs.snapshot().gui().sellMenu().rows()).isEqualTo(6);
    }

    @Test
    @DisplayName("declaring no crops stops the load rather than starting with nothing sellable")
    void emptyCropsFileIsFatal() {
        // Emptying the section rather than deleting the file, because saveDefaults()
        // would helpfully restore a deleted one before the loader ever saw it missing.
        write("crops.yml", "type-pattern: 'growGarden{crop}{variant}Drop{mutation}'\ncrops: {}\n");

        assertThat(configs.loadInitial()).isFalse();
        assertThat(configs.isLoaded()).isFalse();
    }

    // ------------------------------------------------------------------ shipped pack

    @Test
    @DisplayName("the shipped url and sha1 describe the pack actually built into this jar")
    void shippedPackHashMatchesTheBundledPack() throws IOException {
        configs.loadInitial();
        ResourcePackSettings pack = configs.snapshot().pack();

        // Filled in, so a fresh install delivers the art with nothing to configure.
        assertThat(pack.hasUrl()).isTrue();
        assertThat(pack.hasValidSha1()).isTrue();
        assertThat(pack.mode())
                .as("a url present means external-url is auto-picked, so no open port is needed")
                .isEqualTo(PackMode.EXTERNAL_URL);

        // The drift this catches: someone edits resourcepack/, rebuilds, and the jar now carries
        // art whose glyph offsets the plugin assumes -- while the published release, and therefore
        // every player, still has the old pack. Nothing else would notice.
        try (InputStream bundled = getClass().getClassLoader().getResourceAsStream("pack.zip")) {
            assertThat(bundled).as("pack.zip should be on the classpath - run ./gradlew packZip").isNotNull();
            assertThat(pack.sha1())
                    .as("resource-pack.sha1 in config.yml is stale: publish a new release of "
                            + "pack.zip and update the url and sha1 to match")
                    .isEqualTo(PackBundleInstaller.sha1(bundled.readAllBytes()));
        }
    }

    // --------------------------------------------------------------- adapter bindings

    @Test
    @DisplayName("a bound item id becomes sellable as its crop, with no restart and no crops.yml edit")
    void bindingsReachTheRegistry() {
        write(AdapterBindings.FILE, """
                bindings:
                  - id: 'ia:mygarden:tomato'
                    crop: odre
                """);

        assertThat(configs.loadInitial()).isTrue();

        // The whole point of /gs adapter bind: the file the command writes has to arrive here.
        assertThat(configs.snapshot().registry().findByAdapterId("ia:mygarden:tomato"))
                .isPresent()
                .get()
                .satisfies(drop -> assertThat(drop.species().id()).isEqualTo("odre"));
        assertThat(configs.snapshot().report().hasErrors()).isFalse();
    }

    @Test
    @DisplayName("bindings sit alongside hand-written extra-ids rather than replacing them")
    void bindingsAndExtraIdsCoexist() {
        replaceIn("crops.yml", "    mythic-token: NC",
                "    mythic-token: NC\n    extra-ids: ['nx:by_hand']");
        write(AdapterBindings.FILE, """
                bindings:
                  - id: 'ia:by_command'
                    crop: odre
                """);

        assertThat(configs.loadInitial()).isTrue();
        assertThat(configs.snapshot().registry().findByAdapterId("nx:by_hand")).isPresent();
        assertThat(configs.snapshot().registry().findByAdapterId("ia:by_command")).isPresent();
    }

    @Test
    @DisplayName("a binding for a crop that no longer exists is a warning, not a dead shop")
    void danglingBindingIsAWarning() {
        write(AdapterBindings.FILE, """
                bindings:
                  - id: 'ia:mygarden:tomato'
                    crop: crop_that_was_renamed
                """);

        // The file is machine-written, so the usual cause is a crop renamed months later. Refusing
        // the whole configuration over one stale line would close the shop over a mapping nobody
        // is even using.
        assertThat(configs.loadInitial()).isTrue();
        assertThat(configs.snapshot().report().hasErrors()).isFalse();
        assertThat(configs.snapshot().report().warnings())
                .anySatisfy(issue -> {
                    assertThat(issue.file()).isEqualTo(AdapterBindings.FILE);
                    assertThat(issue.message()).contains("no longer exists");
                });
    }

    @Test
    @DisplayName("a binding that shadows one of the pack's own drops is rejected outright")
    void shadowingBindingIsAnError() {
        write(AdapterBindings.FILE, """
                bindings:
                  - id: 'mythic:growGardenChilliDrop'
                    crop: odre
                """);

        // Aliases are consulted before the Mythic prefix rule, so this would re-point a real drop
        // and pay out Odre's price for a Chilli. That is worth failing the load over.
        assertThat(configs.loadInitial()).isFalse();
    }

    @Test
    @DisplayName("no bindings file means no bindings, and nothing is written to create one")
    void bindingsFileIsOptional() {
        assertThat(configs.loadInitial()).isTrue();

        assertThat(dataFolder.resolve(AdapterBindings.FILE)).doesNotExist();
        assertThat(configs.snapshot().registry().findByAdapterId("ia:anything")).isEmpty();
    }

    // ------------------------------------------------------------------- utilities

    private void copyBundled(String name) {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException("bundled resource " + name + " is missing from the classpath");
            }
            Files.write(dataFolder.resolve(name), stream.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String name, String content) {
        try {
            Files.writeString(dataFolder.resolve(name), content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void replaceInPricing(String needle, String replacement) {
        replaceIn("pricing.yml", needle, replacement);
    }

    private void replaceIn(String file, String needle, String replacement) {
        Path path = dataFolder.resolve(file);
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            assertThat(content).as("the fixture text to replace must exist in %s", file).contains(needle);
            Files.writeString(path, content.replace(needle, replacement), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
