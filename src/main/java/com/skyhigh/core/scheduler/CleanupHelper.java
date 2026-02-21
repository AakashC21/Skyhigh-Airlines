package com.skyhigh.core.scheduler;

import com.skyhigh.core.model.Seat;
import com.skyhigh.core.repository.SeatRepository;
import com.skyhigh.core.service.WaitlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Helper for CleanupScheduler — processes a single zombie seat in its own
 * REQUIRES_NEW transaction so a failure on one seat cannot roll back others.
 *
 * Extracted from CleanupScheduler to avoid Spring circular-proxy issues with
 * self-injection.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CleanupHelper {

    private final SeatRepository seatRepository;
    private final WaitlistService waitlistService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanupSingleSeat(Seat seat) {
        // Re-fetch inside the new transaction to get latest committed state
        Seat freshSeat = seatRepository.findById(seat.getId()).orElse(null);
        if (freshSeat == null || freshSeat.getStatus() != Seat.SeatStatus.HELD) {
            // Already handled by another process (e.g. user confirmed it)
            return;
        }

        log.info("Releasing zombie hold on seat {} (flight {})",
                freshSeat.getSeatNumber(), freshSeat.getFlight().getId());
        freshSeat.setStatus(Seat.SeatStatus.AVAILABLE);
        seatRepository.save(freshSeat);

        // Pop waitlist AFTER the DB is successfully updated within this transaction.
        // The REQUIRES_NEW transaction commits before returning, so the seat is
        // safely AVAILABLE before a waitlist user is popped.
        String nextUser = waitlistService.popNextUser(freshSeat.getFlight().getId());
        if (nextUser != null) {
            log.info("Waitlist: Seat {} freed → User {} (notification pending v1.1)",
                    freshSeat.getSeatNumber(), nextUser);
        }
    }
}
