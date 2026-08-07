package dev.lk.gardenshop.stats;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Stats in a single {@code stats.yml}.
 *
 * <p>Fine for the scale this plugin operates at: one entry per player who has ever
 * sold something, written in a batch every minute rather than on every sale.
 */
public final class YamlStatsRepository implements PlayerStatsRepository {

    private static final String FILE_NAME = "stats.yml";

    private final File file;
    private final Logger logger;

    public YamlStatsRepository(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, FILE_NAME);
        this.logger = logger;
    }

    @Override
    public Map<UUID, PlayerStats> loadAll() {
        Map<UUID, PlayerStats> loaded = new HashMap<>();
        if (!file.exists()) {
            return loaded;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            // Losing stats is regrettable; refusing to start over them is worse.
            logger.warning("Could not read " + FILE_NAME + " (" + e.getMessage()
                    + "). Starting with empty statistics; the file will be overwritten on the next flush.");
            return loaded;
        }

        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return loaded;
        }

        for (String rawUuid : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException e) {
                logger.warning("Skipping malformed uuid '" + rawUuid + "' in " + FILE_NAME);
                continue;
            }
            ConfigurationSection entry = players.getConfigurationSection(rawUuid);
            if (entry == null) {
                continue;
            }

            Map<String, Double> records = new HashMap<>();
            ConfigurationSection weights = entry.getConfigurationSection("record-weights");
            if (weights != null) {
                for (String species : weights.getKeys(false)) {
                    records.put(species, weights.getDouble(species));
                }
            }

            loaded.put(uuid, new PlayerStats(
                    uuid,
                    new BigDecimal(entry.getString("total-earned", "0")),
                    entry.getLong("items-sold"),
                    entry.getLong("sales-count"),
                    records));
        }
        return loaded;
    }

    @Override
    public void saveAll(Collection<PlayerStats> stats) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (PlayerStats entry : stats) {
            String base = "players." + entry.uuid();
            // Stored as a string so large totals survive YAML's double handling.
            yaml.set(base + ".total-earned", entry.totalEarned().toPlainString());
            yaml.set(base + ".items-sold", entry.itemsSold());
            yaml.set(base + ".sales-count", entry.salesCount());
            entry.recordWeights().forEach((species, weight) ->
                    yaml.set(base + ".record-weights." + species, weight));
        }

        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.warning("Could not write " + FILE_NAME + ": " + e.getMessage());
        }
    }
}
