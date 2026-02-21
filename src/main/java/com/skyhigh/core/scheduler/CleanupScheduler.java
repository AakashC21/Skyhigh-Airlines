package com.skyhigh.core.scheduler;

import com.skyhigh.core.model.Seat;
import com.skyhigh.core.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Zombie Hold Cleanup Job.
 *
 * Safety net for HELD seats whose Redis key expired but DB status was never
 * updated. Runs every 60 seconds. Uses 125s cutoff (120s TTL + 5s buffer).
 *
 * @SchedulerLock ensures only one node in a cluster runs this at a time.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CleanupScheduler {

    private final SeatRepository seatRepository;
    private final CleanupHelper cleanupHelper;

    @Scheduled(fixedRate = 60_000)
    @SchedulerLock(name = "CleanupZombieHolds", lockAtLeastFor = "30s", lockAtMostFor = "50s")
    public void cleanupExpiredHolds() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(125);
        List<Seat> zombieSeats = seatRepository.findByStatusAndUpdatedAtBefore(Seat.SeatStatus.HELD, cutoff);

        if (zombieSeats.isEmpty()) {
            return;
        }

        log.warn("Found {} zombie holds to release", zombieSeats.size());

        for (Seat seat : zombieSeats) {
            try {
                cleanupHelper.cleanupSingleSeat(seat);
            } catch (Exception e) {
                log.error("Failed to cleanup zombie hold on seat {} (flight {}): {}",
                        seat.getSeatNumber(), seat.getFlight().getId(), e.getMessage(), e);
            }
        }
    }
}
