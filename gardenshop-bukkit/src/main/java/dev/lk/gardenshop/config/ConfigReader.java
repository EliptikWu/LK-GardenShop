package dev.lk.gardenshop.config;

import dev.lk.gardenshop.core.config.ValidationReport;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads typed values out of a YAML section, recording a problem instead of
 * throwing whenever something is missing or malformed.
 *
 * <p>This is what makes {@code /gs reload} genuinely useful: an owner who fat-fingers
 * three multipliers gets told about all three at once, and the server keeps running
 * on the previous configuration meanwhile.
 */
final class ConfigReader {

    private final ConfigurationSection section;
    private final String file;
    private final String pathPrefix;
    private final ValidationReport.Builder report;

    ConfigReader(ConfigurationSection section, String file, ValidationReport.Builder report) {
        this(section, file, "", report);
    }

    private ConfigReader(ConfigurationSection section, String file, String pathPrefix,
                         ValidationReport.Builder report) {
        this.section = section;
        this.file = file;
        this.pathPrefix = pathPrefix;
        this.report = report;
    }

    /** A reader scoped to a child section; returns empty when the child is absent. */
    Optional<ConfigReader> child(String path) {
        ConfigurationSection nested = section.getConfigurationSection(path);
        return nested == null
                ? Optional.empty()
                : Optional.of(new ConfigReader(nested, file, qualify(path), report));
    }

    boolean has(String path) {
        return section.contains(path);
    }

    List<String> keys() {
        return List.copyOf(section.getKeys(false));
    }

    String string(String path, String fallback) {
        String value = section.getString(path);
        if (value == null) {
            warnMissing(path, fallback);
            return fallback;
        }
        return value;
    }

    String requiredString(String path) {
        String value = section.getString(path);
        if (value == null || value.isBlank()) {
            report.error(file, qualify(path), "is required but missing or blank");
            return null;
        }
        return value;
    }

    boolean bool(String path, boolean fallback) {
        if (!section.contains(path)) {
            warnMissing(path, fallback);
            return fallback;
        }
        return section.getBoolean(path, fallback);
    }

    /** A double that must be strictly positive. Reports and falls back otherwise. */
    double positiveDouble(String path, double fallback) {
        if (!section.contains(path)) {
            warnMissing(path, fallback);
            return fallback;
        }
        double value = section.getDouble(path, Double.NaN);
        if (!Double.isFinite(value) || value <= 0.0) {
            report.error(file, qualify(path), "must be a number greater than 0, got: " + section.get(path));
            return fallback;
        }
        return value;
    }

    double nonNegativeDouble(String path, double fallback) {
        if (!section.contains(path)) {
            warnMissing(path, fallback);
            return fallback;
        }
        double value = section.getDouble(path, Double.NaN);
        if (!Double.isFinite(value) || value < 0.0) {
            report.error(file, qualify(path), "must be a number >= 0, got: " + section.get(path));
            return fallback;
        }
        return value;
    }

    int positiveInt(String path, int fallback) {
        if (!section.contains(path)) {
            warnMissing(path, fallback);
            return fallback;
        }
        int value = section.getInt(path, Integer.MIN_VALUE);
        if (value <= 0) {
            report.error(file, qualify(path), "must be a whole number greater than 0, got: " + section.get(path));
            return fallback;
        }
        return value;
    }

    int nonNegativeInt(String path, int fallback) {
        if (!section.contains(path)) {
            warnMissing(path, fallback);
            return fallback;
        }
        int value = section.getInt(path, Integer.MIN_VALUE);
        if (value < 0) {
            report.error(file, qualify(path), "must be a whole number >= 0, got: " + section.get(path));
            return fallback;
        }
        return value;
    }

    /**
     * Reads a money amount or multiplier.
     *
     * <p>Goes through the raw object's string form rather than {@code getDouble} so a
     * value written as {@code 0.1} stays exactly 0.1 instead of becoming
     * 0.1000000000000000055511151231257827.
     */
    BigDecimal decimal(String path, BigDecimal fallback) {
        Object raw = section.get(path);
        if (raw == null) {
            warnMissing(path, fallback);
            return fallback;
        }
        try {
            BigDecimal value = new BigDecimal(raw.toString().trim());
            if (value.signum() < 0) {
                report.error(file, qualify(path), "must not be negative, got: " + raw);
                return fallback;
            }
            return value;
        } catch (NumberFormatException e) {
            report.error(file, qualify(path), "is not a valid number: " + raw);
            return fallback;
        }
    }

    /** Reads an enum-ish value through a parser, warning and falling back on an unknown id. */
    <T> T enumValue(String path, Function<String, Optional<T>> parser, T fallback, String allowed) {
        String raw = section.getString(path);
        if (raw == null) {
            warnMissing(path, fallback);
            return fallback;
        }
        return parser.apply(raw).orElseGet(() -> {
            report.error(file, qualify(path), "'" + raw + "' is not recognised. Allowed values: " + allowed);
            return fallback;
        });
    }

    void error(String path, String message) {
        report.error(file, qualify(path), message);
    }

    void warning(String path, String message) {
        report.warning(file, qualify(path), message);
    }

    private void warnMissing(String path, Object fallback) {
        report.warning(file, qualify(path), "is missing, using the default of " + fallback);
    }

    private String qualify(String path) {
        return pathPrefix.isEmpty() ? path : pathPrefix + "." + path;
    }
}
