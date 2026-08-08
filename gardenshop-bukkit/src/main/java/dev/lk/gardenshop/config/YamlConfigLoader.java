package dev.lk.gardenshop.config;

import dev.lk.gardenshop.core.ConfigSnapshot;
import dev.lk.gardenshop.core.ConfigValidator;
import dev.lk.gardenshop.core.config.BandMode;
import dev.lk.gardenshop.core.config.EconomyPreference;
import dev.lk.gardenshop.core.config.EconomySettings;
import dev.lk.gardenshop.core.config.GuiSettings;
import dev.lk.gardenshop.core.config.IconSpec;
import dev.lk.gardenshop.core.config.ItemSettings;
import dev.lk.gardenshop.core.config.MenuLayout;
import dev.lk.gardenshop.core.config.MenuStyle;
import dev.lk.gardenshop.core.config.MutationStacking;
import dev.lk.gardenshop.core.config.PackMode;
import dev.lk.gardenshop.core.config.PricingConfig;
import dev.lk.gardenshop.core.config.PricingMode;
import dev.lk.gardenshop.core.config.ResourcePackSettings;
import dev.lk.gardenshop.core.config.SellingSettings;
import dev.lk.gardenshop.core.config.ValidationReport;
import dev.lk.gardenshop.core.config.WeightBandTable;
import dev.lk.gardenshop.core.config.WeightTable;
import dev.lk.gardenshop.core.domain.Mutation;
import dev.lk.gardenshop.core.domain.Species;
import dev.lk.gardenshop.core.domain.Variant;
import dev.lk.gardenshop.core.domain.WeightBand;
import dev.lk.gardenshop.core.domain.WeightRange;
import dev.lk.gardenshop.core.registry.DropRegistry;
import dev.lk.gardenshop.core.registry.MythicTypeComposer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import dev.lk.gardenshop.util.Messages;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * Turns the four YAML files on disk into one validated {@link ConfigSnapshot}.
 *
 * <p>Nothing here mutates live state. The caller decides whether the result is good
 * enough to publish, which is what keeps a reload atomic — a broken file costs the
 * owner an error message, not a running economy.
 */
public final class YamlConfigLoader {

    public static final String CONFIG_FILE = "config.yml";
    public static final String CROPS_FILE = "crops.yml";
    public static final String PRICING_FILE = "pricing.yml";
    public static final String WEIGHTS_FILE = "weights.yml";
    public static final String GUI_FILE = "gui.yml";
    public static final String BINDINGS_FILE = AdapterBindings.FILE;

    /**
     * The only files written to the plugin folder.
     *
     * <p>{@link #GUI_FILE} and {@link #WEIGHTS_FILE} are deliberately absent: menu slots are tied to
     * the backdrop texture, and the weight ranges are generated from the Mythic pack by
     * {@code scripts/gen-weights.ps1}. Neither is something a server owner should be editing, and a
     * folder full of files nobody is meant to touch only makes the three that matter harder to find.
     * Both are still honoured if a file is dropped in by hand — see {@link #readInternal}.
     */
    private static final List<String> EXTRACTED_FILES = List.of(CONFIG_FILE, CROPS_FILE, PRICING_FILE);

    private final Plugin plugin;
    private final ConfigValidator validator = new ConfigValidator();

    public YamlConfigLoader(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param snapshot the parsed configuration, or {@code null} when a file was so
     *                 broken that nothing coherent could be assembled
     */
    public record LoadResult(ConfigSnapshot snapshot, ValidationReport report) {

        /** Whether this result is safe to publish. */
        public boolean isUsable() {
            return snapshot != null && !report.hasErrors();
        }
    }

    /** Writes any extracted default that is not on disk yet. Never overwrites. */
    public void saveDefaults() {
        for (String name : EXTRACTED_FILES) {
            File target = new File(plugin.getDataFolder(), name);
            if (!target.exists()) {
                plugin.saveResource(name, false);
            }
        }
    }

    public LoadResult load() {
        ValidationReport.Builder report = ValidationReport.builder();

        YamlConfiguration general = read(CONFIG_FILE, report, Severity.FATAL);
        YamlConfiguration crops = read(CROPS_FILE, report, Severity.FATAL);
        YamlConfiguration pricingYaml = read(PRICING_FILE, report, Severity.FATAL);
        // Not extracted, so read from the jar - unless an admin dropped their own copy in.
        YamlConfiguration weightsYaml = readInternal(WEIGHTS_FILE, report);
        YamlConfiguration guiYaml = readInternal(GUI_FILE, report);

        if (general == null || crops == null || pricingYaml == null) {
            return new LoadResult(null, report.build());
        }

        String language = general.getString("language", Messages.BUNDLED_LANGUAGES.getFirst());
        ItemSettings items = readItemSettings(general, report);
        SellingSettings selling = readSellingSettings(general, report);
        EconomySettings economy = readEconomySettings(general, report);
        ResourcePackSettings pack = readPackSettings(general, report);
        GuiSettings gui = guiYaml == null ? GuiSettings.defaults() : readGuiSettings(guiYaml, report);

        String pattern = crops.getString("type-pattern", MythicTypeComposer.DEFAULT_PATTERN);
        MythicTypeComposer composer;
        try {
            composer = new MythicTypeComposer(pattern);
        } catch (IllegalArgumentException e) {
            report.error(CROPS_FILE, "type-pattern", e.getMessage());
            return new LoadResult(null, report.build());
        }

        // Written by /gs adapter bind, absent on a server that never used it.
        Map<String, List<String>> bindings = readBindings(report);
        List<Species> species = readSpecies(crops, report, bindings);
        warnAboutDanglingBindings(bindings, species, report);
        WeightTable weights = weightsYaml == null
                ? WeightTable.empty()
                : readWeights(weightsYaml, report);

        PricingConfig pricing = readPricing(pricingYaml, report);
        if (pricing == null || species.isEmpty()) {
            if (species.isEmpty()) {
                report.error(CROPS_FILE, "crops", "no usable crop definitions were loaded");
            }
            return new LoadResult(null, report.build());
        }

        DropRegistry registry;
        try {
            registry = composer.buildRegistry(species, weights);
        } catch (IllegalArgumentException e) {
            report.error(CROPS_FILE, "crops", e.getMessage());
            return new LoadResult(null, report.build());
        }

        ValidationReport crossFile = validator.validate(registry, pricing);
        ValidationReport combined = report.build().merge(crossFile);

        ConfigSnapshot snapshot = new ConfigSnapshot(language, pricing, items, selling, economy, gui, pack,
                registry, weights, combined, System.currentTimeMillis());
        return new LoadResult(snapshot, combined);
    }


    // ------------------------------------------------------- config.yml: resource pack

    private ResourcePackSettings readPackSettings(YamlConfiguration general,
                                                  ValidationReport.Builder report) {
        ResourcePackSettings defaults = ResourcePackSettings.defaults();
        ConfigurationSection section = general.getConfigurationSection("resource-pack");
        if (section == null) {
            report.warning(CONFIG_FILE, "resource-pack", "section is missing, using defaults");
            return defaults;
        }

        ConfigReader reader = new ConfigReader(section, CONFIG_FILE, report);
        boolean installed = reader.bool("installed", defaults.installed());
        boolean enabled = reader.bool("enabled", defaults.enabled());

        // Blank is the normal case and means "pick for me", so it must not warn.
        String rawMode = section.getString("mode", "");
        Optional<PackMode> mode = PackMode.parse(rawMode);
        if (!rawMode.isBlank() && mode.isEmpty()) {
            report.error(CONFIG_FILE, "resource-pack.mode", "'" + rawMode
                    + "' is not a known mode. Use bundled-self-host, external-url, none,"
                    + " or leave it empty to pick automatically.");
        }

        String url = section.getString("url", defaults.url());
        String sha1 = section.getString("sha1", defaults.sha1());
        if (!url.isBlank() && (sha1 == null || sha1.isBlank())) {
            report.error(CONFIG_FILE, "resource-pack.sha1",
                    "is required when url is set: a client rejects a pack whose bytes do not match"
                            + " the announced hash. Run './gradlew packZip' to get it.");
        }

        ConfigurationSection selfHost = section.getConfigurationSection("self-host");
        int port = selfHost == null
                ? defaults.selfHostPort()
                : new ConfigReader(selfHost, CONFIG_FILE, report)
                        .positiveInt("port", defaults.selfHostPort());
        String publicHost = selfHost == null ? "" : selfHost.getString("public-host", "");

        try {
            return new ResourcePackSettings(installed, enabled, mode, url, sha1,
                    reader.bool("required", defaults.required()),
                    section.getString("prompt", defaults.prompt()),
                    port, publicHost);
        } catch (IllegalArgumentException e) {
            report.error(CONFIG_FILE, "resource-pack", e.getMessage());
            return defaults;
        }
    }

    // ------------------------------------------------------------------- gui.yml

    private GuiSettings readGuiSettings(YamlConfiguration yaml, ValidationReport.Builder report) {
        GuiSettings defaults = GuiSettings.defaults();
        ConfigReader root = new ConfigReader(yaml, GUI_FILE, report);

        String rawStyle = yaml.getString("style", "");
        MenuStyle style = MenuStyle.parse(rawStyle).orElseGet(() -> {
            if (!rawStyle.isBlank()) {
                report.error(GUI_FILE, "style", "'" + rawStyle
                        + "' is not a known style. Use styled, plain or auto.");
            }
            return defaults.style();
        });

        return new GuiSettings(
                root.bool("enabled", defaults.enabled()),
                style,
                root.bool("confirm-bulk-sale", defaults.confirmBulkSale()),
                root.bool("close-menus-on-reload", defaults.closeMenusOnReload()),
                readLayout(yaml, "sell-menu", defaults.sellMenu(), report),
                readLayout(yaml, "confirm-menu", defaults.confirmMenu(), report),
                readLayout(yaml, "price-book", defaults.priceBook(), report));
    }

    private MenuLayout readLayout(YamlConfiguration yaml, String path, MenuLayout fallback,
                                  ValidationReport.Builder report) {
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) {
            report.warning(GUI_FILE, path, "section is missing, using the built-in layout");
            return fallback;
        }

        int rows = section.getInt("rows", fallback.rows());
        if (rows < 1 || rows > MenuLayout.MAX_ROWS) {
            report.error(GUI_FILE, path + ".rows",
                    "must be between 1 and " + MenuLayout.MAX_ROWS + ", got " + rows);
            rows = fallback.rows();
        }

        Map<String, Integer> slots = new LinkedHashMap<>();
        ConfigurationSection slotSection = section.getConfigurationSection("slots");
        if (slotSection == null) {
            report.warning(GUI_FILE, path + ".slots", "section is missing, using the built-in slots");
            slots.putAll(fallback.slots());
        } else {
            int capacity = rows * MenuLayout.COLUMNS;
            for (String button : slotSection.getKeys(false)) {
                int slot = slotSection.getInt(button, -1);
                if (slot < 0 || slot >= capacity) {
                    report.error(GUI_FILE, path + ".slots." + button,
                            "slot " + slot + " is outside a " + rows + "-row menu (0-"
                                    + (capacity - 1) + ")");
                    continue;
                }
                slots.put(button, slot);
            }
        }

        Map<String, IconSpec> icons = readIcons(section, path, report);

        // The glyph is named, not written as a raw codepoint: a private-use character in a YAML
        // file is invisible in every editor and does not survive an encoding round-trip.
        //
        // A PRESENT key is honoured exactly, including when blank - that is how the backdrop is
        // turned off. Only an absent key falls back, or `background: ''` on the sell menu would
        // silently keep the art it was written to remove.
        char glyph = fallback.glyph();
        if (section.contains("background")) {
            String named = section.getString("background", "").trim();
            if (named.isEmpty()) {
                glyph = MenuLayout.NO_GLYPH;
            } else if ("shop".equalsIgnoreCase(named)) {
                glyph = GuiSettings.GLYPH_SHOP;
            } else if ("shop-list".equalsIgnoreCase(named) || "list".equalsIgnoreCase(named)) {
                glyph = GuiSettings.GLYPH_SHOP_LIST;
            } else {
                report.error(GUI_FILE, path + ".background", "'" + named
                        + "' is not a known backdrop. Use 'shop' or 'list', or leave it empty for"
                        + " none. Adding one means a new bitmap provider in the pack's font/gui.json.");
            }
        }
        int glyphXOffset = section.getInt("background-x-offset", fallback.glyphXOffset());

        try {
            return new MenuLayout(rows, slots, icons,
                    section.getString("filler", fallback.filler()), glyph, glyphXOffset);
        } catch (IllegalArgumentException e) {
            report.error(GUI_FILE, path, e.getMessage());
            return fallback;
        }
    }

    /**
     * Reads the per-button look.
     *
     * <p>Accepts either shorthand — {@code sell-all: CHEST} — or the full form with
     * {@code material}, {@code model-data} and {@code fallback-material}. Material names are not
     * checked against {@code Material} here: that is a server class, and the menus already fall back
     * with a warning when a name does not resolve.
     */
    private Map<String, IconSpec> readIcons(ConfigurationSection menu, String path,
                                            ValidationReport.Builder report) {
        Map<String, IconSpec> icons = new LinkedHashMap<>();
        ConfigurationSection section = menu.getConfigurationSection("icons");
        if (section == null) {
            return icons;
        }

        for (String button : section.getKeys(false)) {
            ConfigurationSection spec = section.getConfigurationSection(button);
            if (spec == null) {
                icons.put(button, IconSpec.of(section.getString(button, "")));
                continue;
            }

            int modelData = spec.getInt("model-data", -1);
            if (modelData < -1) {
                report.error(GUI_FILE, path + ".icons." + button + ".model-data",
                        "must not be negative, got " + modelData);
                modelData = -1;
            }
            try {
                icons.put(button, new IconSpec(
                        spec.getString("material", ""),
                        modelData < 0 ? OptionalInt.empty() : OptionalInt.of(modelData),
                        spec.getString("fallback-material", "")));
            } catch (IllegalArgumentException e) {
                report.error(GUI_FILE, path + ".icons." + button, e.getMessage());
            }
        }
        return icons;
    }

    // ---------------------------------------------------------------- config.yml

    private ItemSettings readItemSettings(YamlConfiguration general, ValidationReport.Builder report) {
        ItemSettings defaults = ItemSettings.defaults();
        ConfigurationSection section = general.getConfigurationSection("items");
        if (section == null) {
            report.warning(CONFIG_FILE, "items", "section is missing, using defaults");
            return defaults;
        }

        ConfigReader reader = new ConfigReader(section, CONFIG_FILE, report);
        String format = reader.string("weight-lore-format", defaults.weightLoreFormat());
        if (!format.contains("%s")) {
            reader.error("weight-lore-format",
                    "must contain '%s' where the weight goes, got: " + format);
            format = defaults.weightLoreFormat();
        }

        return new ItemSettings(
                reader.bool("stamp-on-generate", defaults.stampOnGenerate()),
                reader.bool("rewrite-lore", defaults.rewriteLore()),
                reader.bool("legacy-lore-fallback", defaults.legacyLoreFallback()),
                format,
                reader.string("weight-lore-marker", defaults.weightLoreMarker()),
                reader.bool("require-crop-pack", defaults.requireCropPack()));
    }

    private SellingSettings readSellingSettings(YamlConfiguration general, ValidationReport.Builder report) {
        SellingSettings defaults = SellingSettings.defaults();
        ConfigurationSection section = general.getConfigurationSection("selling");
        if (section == null) {
            report.warning(CONFIG_FILE, "selling", "section is missing, using defaults");
            return defaults;
        }

        ConfigReader reader = new ConfigReader(section, CONFIG_FILE, report);
        return new SellingSettings(
                reader.nonNegativeInt("cooldown-seconds", defaults.cooldownSeconds()),
                reader.positiveInt("max-items-per-bulk-sale", defaults.maxItemsPerBulkSale()),
                reader.bool("skip-favorites", defaults.skipFavorites()));
    }

    private EconomySettings readEconomySettings(YamlConfiguration general, ValidationReport.Builder report) {
        ConfigurationSection section = general.getConfigurationSection("economy");
        if (section == null) {
            report.warning(CONFIG_FILE, "economy", "section is missing, defaulting to AUTO");
            return EconomySettings.defaults();
        }

        ConfigReader reader = new ConfigReader(section, CONFIG_FILE, report);
        EconomyPreference preference = reader.enumValue("provider", EconomyPreference::byId,
                EconomyPreference.AUTO, allowed(EconomyPreference.values()));
        // Absent is the normal case, so read it without the missing-key warning.
        String currency = section.getString("currency", "");
        return new EconomySettings(preference, currency);
    }

    // ----------------------------------------------------------------- crops.yml

    private List<Species> readSpecies(YamlConfiguration crops, ValidationReport.Builder report,
                                      Map<String, List<String>> bindings) {
        ConfigurationSection section = crops.getConfigurationSection("crops");
        if (section == null) {
            report.error(CROPS_FILE, "crops", "section is missing — declare at least one crop");
            return List.of();
        }

        List<Species> species = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection cropSection = section.getConfigurationSection(id);
            if (cropSection == null) {
                report.error(CROPS_FILE, "crops." + id, "must be a section, not a single value");
                continue;
            }
            readOneSpecies(id, cropSection, report, bindings.getOrDefault(id, List.of()))
                    .ifPresent(species::add);
        }
        return species;
    }

    /**
     * Bindings grouped by the crop they name.
     *
     * <p>Read here rather than merged later so the ids arrive as part of the species, which is what
     * lets {@link MythicTypeComposer} treat a hand-written {@code extra-ids} entry and a bound one
     * as the same thing — including its duplicate and shadowing checks.
     */
    private Map<String, List<String>> readBindings(ValidationReport.Builder report) {
        Map<String, String> loaded = new AdapterBindings(plugin)
                .load(problem -> report.warning(BINDINGS_FILE, "", problem));

        Map<String, List<String>> byCrop = new LinkedHashMap<>();
        for (Map.Entry<String, String> binding : loaded.entrySet()) {
            byCrop.computeIfAbsent(binding.getValue(), crop -> new ArrayList<>())
                    .add(binding.getKey());
        }
        return byCrop;
    }

    /**
     * A binding naming a crop that no longer exists.
     *
     * <p>A warning, not an error. The file is machine-written, so the usual cause is a crop renamed
     * in {@code crops.yml} months after something was bound — and refusing the whole configuration
     * over a stale line would take the shop down for a mapping nobody is using.
     */
    private void warnAboutDanglingBindings(Map<String, List<String>> bindings, List<Species> species,
                                           ValidationReport.Builder report) {
        List<String> known = species.stream().map(Species::id).toList();
        for (Map.Entry<String, List<String>> binding : bindings.entrySet()) {
            if (!known.contains(binding.getKey())) {
                report.warning(BINDINGS_FILE, binding.getKey(),
                        "names a crop that no longer exists in crops.yml, so "
                                + binding.getValue().size() + " item id(s) will not sell. "
                                + "Re-bind them or remove the entries.");
            }
        }
    }

    private Optional<Species> readOneSpecies(String id, ConfigurationSection section,
                                             ValidationReport.Builder report,
                                             List<String> boundIds) {
        String path = "crops." + id;
        ConfigReader reader = new ConfigReader(section, CROPS_FILE, report);

        String token = reader.requiredString("mythic-token");
        if (token == null) {
            report.error(CROPS_FILE, path + ".mythic-token",
                    "is required: it is the fragment spliced into Mythic type names (Odre's is 'NC')");
            return Optional.empty();
        }

        String display = reader.string("display", id);
        BigDecimal baseValue = reader.decimal("base-value", BigDecimal.ONE);
        double baseWeight = reader.positiveDouble("base-weight", 1.0);

        WeightRange range = reader.child("weight-range")
                .flatMap(child -> readRange(child, path + ".weight-range", report))
                .orElse(null);
        if (range == null) {
            report.error(CROPS_FILE, path + ".weight-range",
                    "is required, as { min: <kg>, max: <kg> } for the plain unmutated drop");
            return Optional.empty();
        }

        // Optional, and empty on every server that only grows the Mythic pack. Ids from Mythic or
        // Crucible are matched without being listed, so anything here is from another item plugin.
        // Hand-written entries and ones bound with /gs adapter bind are treated identically.
        List<String> extraIds = new ArrayList<>(section.getStringList("extra-ids"));
        extraIds.addAll(boundIds);

        try {
            return Optional.of(new Species(id, display, token, baseValue, baseWeight, range, extraIds));
        } catch (IllegalArgumentException e) {
            report.error(CROPS_FILE, path, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<WeightRange> readRange(ConfigReader reader, String path,
                                            ValidationReport.Builder report) {
        double min = reader.positiveDouble("min", -1.0);
        double max = reader.positiveDouble("max", -1.0);
        if (min <= 0.0 || max <= 0.0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new WeightRange(min, max));
        } catch (IllegalArgumentException e) {
            report.error(CROPS_FILE, path, e.getMessage());
            return Optional.empty();
        }
    }

    // --------------------------------------------------------------- weights.yml

    private WeightTable readWeights(YamlConfiguration weightsYaml, ValidationReport.Builder report) {
        ConfigurationSection section = weightsYaml.getConfigurationSection("weights");
        if (section == null) {
            report.warning(WEIGHTS_FILE, "weights",
                    "section is missing — every drop will fall back to its crop's base range");
            return WeightTable.empty();
        }

        Map<String, WeightRange> ranges = new HashMap<>();
        for (String type : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(type);
            if (entry == null) {
                report.warning(WEIGHTS_FILE, "weights." + type,
                        "must be { min: <kg>, max: <kg> } — entry ignored");
                continue;
            }
            double min = entry.getDouble("min", -1.0);
            double max = entry.getDouble("max", -1.0);
            try {
                ranges.put(type, new WeightRange(min, max));
            } catch (IllegalArgumentException e) {
                report.warning(WEIGHTS_FILE, "weights." + type, e.getMessage() + " — entry ignored");
            }
        }
        return new WeightTable(ranges);
    }

    // --------------------------------------------------------------- pricing.yml

    private PricingConfig readPricing(YamlConfiguration yaml, ValidationReport.Builder report) {
        ConfigReader root = new ConfigReader(yaml, PRICING_FILE, report);

        PricingMode mode = root.enumValue(
                "mode", PricingMode::byId, PricingMode.HYBRID, allowed(PricingMode.values()));
        MutationStacking stacking = root.enumValue(
                "mutation-stacking", MutationStacking::byId, MutationStacking.ADDITIVE,
                allowed(MutationStacking.values()));
        RoundingMode rounding = root.enumValue(
                "rounding", YamlConfigLoader::parseRounding, RoundingMode.HALF_UP,
                "HALF_UP, HALF_DOWN, HALF_EVEN, UP, DOWN, CEILING, FLOOR");

        BigDecimal global = root.decimal("global-multiplier", BigDecimal.ONE);
        BigDecimal minPayout = root.decimal("min-payout", BigDecimal.ONE);
        BigDecimal maxPayout = root.decimal("max-payout-per-sale", new BigDecimal("10000000"));
        int moneyScale = root.nonNegativeInt("money-scale", 2);
        double exponent = root.nonNegativeDouble("weight-exponent", 2.0);

        WeightBandTable bands = readBandTable(yaml.getConfigurationSection("weight-bands"),
                "weight-bands", report);
        if (bands == null) {
            return null;
        }

        Map<String, WeightBandTable> perCrop = readPerCropBands(yaml, report);
        Map<Variant, BigDecimal> variants = readVariantMultipliers(yaml, report);
        Map<Mutation, BigDecimal> mutations = readMutationMultipliers(yaml, report);
        Map<String, Map<String, BigDecimal>> flatMoney = readFlatMoney(yaml, report);

        try {
            return new PricingConfig(mode, global, minPayout, maxPayout, rounding, moneyScale,
                    bands, perCrop, variants, mutations, stacking, exponent, flatMoney);
        } catch (IllegalArgumentException e) {
            report.error(PRICING_FILE, "", e.getMessage());
            return null;
        }
    }

    /** @return the parsed table, or {@code null} when it is unusable */
    private WeightBandTable readBandTable(ConfigurationSection section, String path,
                                          ValidationReport.Builder report) {
        if (section == null) {
            report.error(PRICING_FILE, path, "section is missing — the weight ladder is required");
            return null;
        }

        ConfigReader reader = new ConfigReader(section, PRICING_FILE, report);
        BandMode mode = reader.enumValue("mode", BandMode::byId, BandMode.RATIO, allowed(BandMode.values()));
        boolean interpolate = reader.bool("interpolate", true);

        List<Map<?, ?>> raw = section.getMapList("bands");
        if (raw.isEmpty()) {
            report.error(PRICING_FILE, path + ".bands", "at least one band is required");
            return null;
        }

        List<WeightBand> bands = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            String bandPath = path + ".bands[" + i + "]";
            readBand(raw.get(i), bandPath, report).ifPresent(bands::add);
        }
        if (bands.size() != raw.size()) {
            return null;
        }

        List<String> problems = WeightBandTable.validate(bands);
        if (!problems.isEmpty()) {
            problems.forEach(problem -> report.error(PRICING_FILE, path + ".bands", problem));
            return null;
        }
        return new WeightBandTable(mode, interpolate, bands);
    }

    private Optional<WeightBand> readBand(Map<?, ?> entry, String path, ValidationReport.Builder report) {
        Object id = entry.get("id");
        Object max = entry.get("max");
        Object multiplier = entry.get("multiplier");
        Object label = entry.get("label");

        if (id == null || max == null || multiplier == null) {
            report.error(PRICING_FILE, path, "needs an id, a max and a multiplier");
            return Optional.empty();
        }

        try {
            return Optional.of(new WeightBand(
                    id.toString(),
                    Double.parseDouble(max.toString()),
                    new BigDecimal(multiplier.toString().trim()),
                    label == null ? id.toString() : label.toString()));
        } catch (NumberFormatException e) {
            report.error(PRICING_FILE, path, "max and multiplier must be numbers, got max=" + max
                    + " multiplier=" + multiplier);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            report.error(PRICING_FILE, path, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, WeightBandTable> readPerCropBands(YamlConfiguration yaml,
                                                          ValidationReport.Builder report) {
        ConfigurationSection section = yaml.getConfigurationSection("per-crop-bands");
        if (section == null) {
            return Map.of();
        }

        Map<String, WeightBandTable> overrides = new LinkedHashMap<>();
        for (String cropId : section.getKeys(false)) {
            ConfigurationSection override = section.getConfigurationSection(cropId);
            if (override == null) {
                report.warning(PRICING_FILE, "per-crop-bands." + cropId,
                        "must be a section with its own bands — entry ignored");
                continue;
            }
            WeightBandTable table = readBandTable(override, "per-crop-bands." + cropId, report);
            if (table != null) {
                overrides.put(cropId, table);
            }
        }
        return overrides;
    }

    private Map<Variant, BigDecimal> readVariantMultipliers(YamlConfiguration yaml,
                                                            ValidationReport.Builder report) {
        EnumMap<Variant, BigDecimal> multipliers = new EnumMap<>(Variant.class);
        ConfigurationSection section = yaml.getConfigurationSection("variants");
        if (section == null) {
            report.warning(PRICING_FILE, "variants", "section is missing — every variant defaults to 1.0");
            return multipliers;
        }

        ConfigReader reader = new ConfigReader(section, PRICING_FILE, report);
        for (String key : section.getKeys(false)) {
            Optional<Variant> variant = Variant.byId(key);
            if (variant.isEmpty()) {
                report.warning(PRICING_FILE, "variants." + key,
                        "'" + key + "' is not a known variant (" + allowed(Variant.values()) + ") — ignored");
                continue;
            }
            multipliers.put(variant.get(), reader.decimal(key, BigDecimal.ONE));
        }
        return multipliers;
    }

    private Map<Mutation, BigDecimal> readMutationMultipliers(YamlConfiguration yaml,
                                                              ValidationReport.Builder report) {
        EnumMap<Mutation, BigDecimal> multipliers = new EnumMap<>(Mutation.class);
        ConfigurationSection section = yaml.getConfigurationSection("mutations");
        if (section == null) {
            report.warning(PRICING_FILE, "mutations", "section is missing — every mutation defaults to 1.0");
            return multipliers;
        }

        ConfigReader reader = new ConfigReader(section, PRICING_FILE, report);
        for (String key : section.getKeys(false)) {
            Optional<Mutation> mutation = Mutation.byId(key);
            if (mutation.isEmpty()) {
                report.warning(PRICING_FILE, "mutations." + key,
                        "'" + key + "' is not a known mutation (" + allowed(Mutation.values()) + ") — ignored");
                continue;
            }
            multipliers.put(mutation.get(), reader.decimal(key, BigDecimal.ONE));
        }
        return multipliers;
    }

    private Map<String, Map<String, BigDecimal>> readFlatMoney(YamlConfiguration yaml,
                                                               ValidationReport.Builder report) {
        ConfigurationSection section = yaml.getConfigurationSection("flat-money");
        if (section == null) {
            return Map.of();
        }

        Map<String, Map<String, BigDecimal>> tables = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection amounts = section.getConfigurationSection(key);
            if (amounts == null) {
                report.warning(PRICING_FILE, "flat-money." + key,
                        "must be a section of band-id to amount — entry ignored");
                continue;
            }
            ConfigReader reader = new ConfigReader(amounts, PRICING_FILE, report);
            Map<String, BigDecimal> byBand = new LinkedHashMap<>();
            for (String bandId : amounts.getKeys(false)) {
                byBand.put(bandId, reader.decimal(bandId, BigDecimal.ZERO));
            }
            tables.put(key, byBand);
        }
        return tables;
    }

    // -------------------------------------------------------------------- shared

    /** Whether a file being unreadable should stop the whole load. */
    private enum Severity {
        /** Nothing coherent can be assembled without this file. */
        FATAL,
        /** The loader has a fallback for this file's absence. */
        RECOVERABLE
    }

    private YamlConfiguration read(String name, ValidationReport.Builder report, Severity severity) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            record(report, severity, name, "file is missing from the plugin folder");
            return null;
        }

        // load(File) rather than loadConfiguration(File): the latter swallows syntax
        // errors and hands back an empty config, which would look like "all defaults"
        // instead of "your YAML is broken".
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (InvalidConfigurationException e) {
            record(report, severity, name, "is not valid YAML: " + firstLineOf(e.getMessage()));
            return null;
        } catch (IOException e) {
            record(report, severity, name, "could not be read: " + e.getMessage());
            return null;
        }
    }

    /**
     * Reads a file that lives in the jar rather than the plugin folder.
     *
     * <p>An admin who drops their own copy into the folder gets theirs; otherwise the bundled one is
     * used. Nothing is ever written out, which is the whole point — the folder stays down to the
     * three files worth editing.
     *
     * @return the parsed YAML, or {@code null} if neither source could be read
     */
    private YamlConfiguration readInternal(String name, ValidationReport.Builder report) {
        File override = new File(plugin.getDataFolder(), name);
        if (override.exists()) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(override);
                plugin.getLogger().info(() -> "Using your " + name + " from the plugin folder"
                        + " instead of the bundled one.");
                return yaml;
            } catch (InvalidConfigurationException e) {
                report.warning(name, "", "is not valid YAML, using the bundled version instead: "
                        + firstLineOf(e.getMessage()));
            } catch (IOException e) {
                report.warning(name, "", "could not be read, using the bundled version instead: "
                        + e.getMessage());
            }
        }

        try (InputStream stream = plugin.getResource(name)) {
            if (stream == null) {
                report.warning(name, "", "is missing from this build, falling back to built-in values");
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            report.warning(name, "", "could not be read from the jar: " + e.getMessage());
            return null;
        }
    }

    private static void record(ValidationReport.Builder report, Severity severity,
                               String file, String message) {
        if (severity == Severity.FATAL) {
            report.error(file, "", message);
        } else {
            report.warning(file, "", message
                    + " — every drop will fall back to its crop's base weight range");
        }
    }

    private static Optional<RoundingMode> parseRounding(String id) {
        try {
            return Optional.of(RoundingMode.valueOf(id.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String allowed(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.joining(", "));
    }

    /** SnakeYAML messages span many lines; the first one carries the location. */
    private static String firstLineOf(String message) {
        if (message == null) {
            return "unknown parse error";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
