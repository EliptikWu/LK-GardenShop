package dev.lk.gardenshop.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the flush path, where a sale on the main thread races the writer off-thread.
 *
 * <p>No server needed: statistics touch nothing Bukkit-shaped.
 */
class StatsServiceTest {

    private static final Logger LOGGER = Logger.getLogger("test");
    private final UUID player = UUID.randomUUID();

    /** Records what it was asked to save, and can be made to fail or to interfere. */
    private static final class RecordingRepository implements PlayerStatsRepository {

        private final List<List<BigDecimal>> writes = new ArrayList<>();
        private boolean failing;
        private Runnable duringSave;

        @Override
        public Map<UUID, PlayerStats> loadAll() {
            return Map.of();
        }

        @Override
        public void saveAll(Collection<PlayerStats> stats) {
            if (duringSave != null) {
                duringSave.run();
            }
            if (failing) {
                throw new IllegalStateException("disk on fire");
            }
            writes.add(stats.stream().map(PlayerStats::totalEarned).toList());
        }
    }

    @Test
    @DisplayName("a sale is written on the next flush")
    void flushesASale() {
        RecordingRepository repository = new RecordingRepository();
        StatsService service = new StatsService(repository, LOGGER, true);

        service.recordSale(player, new BigDecimal("100"), 3, Map.of("odre", 1.2));
        service.flush();

        assertThat(repository.writes).hasSize(1);
        assertThat(service.of(player).totalEarned()).isEqualByComparingTo("100");
        assertThat(service.of(player).itemsSold()).isEqualTo(3);
        assertThat(service.of(player).recordWeight("odre")).isEqualTo(1.2);
    }

    @Test
    @DisplayName("a clean cache does not touch storage")
    void noWriteWhenNothingChanged() {
        RecordingRepository repository = new RecordingRepository();
        StatsService service = new StatsService(repository, LOGGER, true);

        service.recordSale(player, new BigDecimal("10"), 1, Map.of());
        service.flush();
        service.flush();

        assertThat(repository.writes).as("second flush had nothing to do").hasSize(1);
    }

    @Test
    @DisplayName("a sale landing mid-write is not lost")
    void saleDuringFlushSurvives() {
        RecordingRepository repository = new RecordingRepository();
        StatsService service = new StatsService(repository, LOGGER, true);

        service.recordSale(player, new BigDecimal("100"), 1, Map.of());
        // Simulates the real race: the flush task is writing off-thread when a sale
        // completes on the main thread. Clearing the dirty flag after the write would
        // mark this entry saved and the second sale would never reach disk.
        repository.duringSave = () -> service.recordSale(player, new BigDecimal("50"), 1, Map.of());

        service.flush();
        repository.duringSave = null;
        service.flush();

        assertThat(service.of(player).totalEarned()).isEqualByComparingTo("150");
        assertThat(repository.writes).as("the interleaved sale forced a second write").hasSize(2);
        assertThat(repository.writes.getLast())
                .as("the final write carries the full total")
                .containsExactly(new BigDecimal("150"));
    }

    @Test
    @DisplayName("a failed write is retried rather than silently dropped")
    void failedWriteIsRetried() {
        RecordingRepository repository = new RecordingRepository();
        StatsService service = new StatsService(repository, LOGGER, true);

        service.recordSale(player, new BigDecimal("100"), 1, Map.of());
        repository.failing = true;
        service.flush();

        assertThat(repository.writes).isEmpty();
        assertThat(service.of(player).isDirty()).as("still pending").isTrue();

        repository.failing = false;
        service.flush();

        assertThat(repository.writes).hasSize(1);
        assertThat(service.of(player).isDirty()).isFalse();
    }

    @Test
    @DisplayName("disabling statistics stops all bookkeeping")
    void disabledDoesNothing() {
        RecordingRepository repository = new RecordingRepository();
        StatsService service = new StatsService(repository, LOGGER, false);

        service.recordSale(player, new BigDecimal("100"), 1, Map.of());
        service.flush();

        assertThat(repository.writes).isEmpty();
        assertThat(service.of(player).totalEarned()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a personal best only moves upwards")
    void recordWeightIsAMaximum() {
        StatsService service = new StatsService(new RecordingRepository(), LOGGER, true);

        service.recordSale(player, BigDecimal.ONE, 1, Map.of("odre", 2.5));
        service.recordSale(player, BigDecimal.ONE, 1, Map.of("odre", 1.0));

        assertThat(service.of(player).recordWeight("odre")).isEqualTo(2.5);
        assertThat(service.of(player).recordWeight("chilli")).isZero();
    }
}
