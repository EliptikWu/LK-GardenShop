package dev.lk.gardenshop.stats;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A player's lifetime selling record.
 *
 * <p>Mutable and internally synchronised: updates arrive on the main thread during a
 * sale, while the flush task reads them off-thread. The lock is held only for field
 * copies, never across I/O.
 */
public final class PlayerStats {

    private final UUID uuid;
    private BigDecimal totalEarned;
    private long itemsSold;
    private long salesCount;
    private final Map<String, Double> recordWeights;
    private volatile boolean dirty;

    public PlayerStats(UUID uuid) {
        this(uuid, BigDecimal.ZERO, 0L, 0L, Map.of());
    }

    public PlayerStats(UUID uuid, BigDecimal totalEarned, long itemsSold, long salesCount,
                       Map<String, Double> recordWeights) {
        this.uuid = uuid;
        this.totalEarned = totalEarned;
        this.itemsSold = itemsSold;
        this.salesCount = salesCount;
        this.recordWeights = new HashMap<>(recordWeights);
    }

    public UUID uuid() {
        return uuid;
    }

    public synchronized BigDecimal totalEarned() {
        return totalEarned;
    }

    public synchronized long itemsSold() {
        return itemsSold;
    }

    public synchronized long salesCount() {
        return salesCount;
    }

    /** Heaviest harvest ever sold per species id. */
    public synchronized Map<String, Double> recordWeights() {
        return Map.copyOf(recordWeights);
    }

    public synchronized double recordWeight(String speciesId) {
        return recordWeights.getOrDefault(speciesId, 0.0);
    }

    public synchronized void addSale(BigDecimal earned, int items) {
        totalEarned = totalEarned.add(earned);
        itemsSold += items;
        salesCount++;
        dirty = true;
    }

    /** Records a new personal best, if it beats the old one. */
    public synchronized void offerRecordWeight(String speciesId, double weightKg) {
        Double previous = recordWeights.get(speciesId);
        if (previous == null || weightKg > previous) {
            recordWeights.put(speciesId, weightKg);
            dirty = true;
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    void markClean() {
        dirty = false;
    }

    /** Re-flags an entry whose write failed, so the next flush retries it. */
    void markDirty() {
        dirty = true;
    }
}
