package dev.lk.gardenshop.stats;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory selling stats, flushed to storage on a timer.
 *
 * <p>Sales happen on the main thread and must not wait on a disk write, so updates
 * land in a concurrent map and a scheduled task persists the dirty entries. The
 * trade-off is explicit: a hard crash can lose up to one flush interval of
 * statistics. Nothing here affects balances or items, so that is an acceptable price
 * for keeping the sell path off the disk.
 */
public final class StatsService {

    private final PlayerStatsRepository repository;
    private final Logger logger;
    private final Map<UUID, PlayerStats> cache = new ConcurrentHashMap<>();
    private final boolean enabled;

    public StatsService(PlayerStatsRepository repository, Logger logger, boolean enabled) {
        this.repository = repository;
        this.logger = logger;
        this.enabled = enabled;
    }

    /** Loads existing stats. Call off the main thread. */
    public void load() {
        if (!enabled) {
            return;
        }
        try {
            cache.putAll(repository.loadAll());
            logger.info(() -> "Loaded selling statistics for " + cache.size() + " player(s).");
        } catch (RuntimeException e) {
            logger.warning("Could not load selling statistics: " + e.getMessage());
        }
    }

    public PlayerStats of(UUID uuid) {
        return cache.computeIfAbsent(uuid, PlayerStats::new);
    }

    /**
     * Records a completed sale.
     *
     * @param heaviestBySpecies the heaviest item of each species in this sale, for
     *                          personal-best tracking
     */
    public void recordSale(UUID uuid, BigDecimal earned, int items, Map<String, Double> heaviestBySpecies) {
        if (!enabled) {
            return;
        }
        PlayerStats stats = of(uuid);
        stats.addSale(earned, items);
        heaviestBySpecies.forEach(stats::offerRecordWeight);
    }

    /** Persists dirty entries. Call off the main thread. */
    public void flush() {
        if (!enabled) {
            return;
        }
        List<PlayerStats> dirty = new ArrayList<>();
        for (PlayerStats stats : cache.values()) {
            if (stats.isDirty()) {
                dirty.add(stats);
            }
        }
        if (dirty.isEmpty()) {
            return;
        }

        // Cleared BEFORE the write, not after. Sales land on the main thread while this
        // runs here off-thread: clearing afterwards would mark clean an entry that gained
        // a sale mid-write, and that sale would never be persisted. Clearing first means
        // such a sale simply re-dirties the entry and is picked up next flush.
        dirty.forEach(PlayerStats::markClean);

        // The repository rewrites the whole file, so everything must be handed over,
        // not just the dirty subset.
        try {
            repository.saveAll(List.copyOf(cache.values()));
        } catch (RuntimeException e) {
            dirty.forEach(PlayerStats::markDirty);
            logger.warning("Could not save selling statistics, will retry next flush: " + e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int trackedPlayers() {
        return cache.size();
    }
}
