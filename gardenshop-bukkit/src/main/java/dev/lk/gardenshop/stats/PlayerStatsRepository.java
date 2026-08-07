package dev.lk.gardenshop.stats;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Where selling stats are kept.
 *
 * <p>An interface rather than a hardcoded YAML file so a server large enough to care
 * can swap in SQLite without the sell flow or the placeholders changing.
 */
public interface PlayerStatsRepository {

    /** Loads everything on startup. Called off the main thread. */
    Map<UUID, PlayerStats> loadAll();

    /** Persists the given entries. Called off the main thread. */
    void saveAll(Collection<PlayerStats> stats);
}
