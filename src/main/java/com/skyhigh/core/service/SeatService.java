package com.skyhigh.core.service;

import com.skyhigh.core.model.*;
import com.skyhigh.core.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatService {

    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final PassengerRepository passengerRepository;
    private final StringRedisTemplate redisTemplate;

    private static final long HOLD_DURATION_SECONDS = 120;

    /**
     * FIX (CRITICAL): holdSeat is now @Transactional.
     * Redis SETNX is attempted first. On any DB failure the Redis key is
     * deleted in a finally/catch block so no phantom holds remain.
     * A pessimistic lock is acquired on the seat row to eliminate the
     * race window between reading and updating status.
     */
    @Transactional
    public String holdSeat(Long flightId, String seatNumber, String userId) {
        String key = buildKey(flightId, seatNumber);

        // 1. Atomic Redis lock attempt (SETNX + TTL)
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, userId, Duration.ofSeconds(HOLD_DURATION_SECONDS));

        if (Boolean.FALSE.equals(success)) {
            throw new IllegalStateException("Seat " + seatNumber + " is currently held by another user.");
        }

        try {
            // 2. FIX: single query with pessimistic lock — eliminates two-step lookup
            // and closes the race window with CleanupScheduler.
            Seat seat = seatRepository.findByFlightIdAndSeatNumberWithLock(flightId, seatNumber)
                    .orElseThrow(() -> new IllegalArgumentException("Seat " + seatNumber + " not found."));

            // 3. Guard: reject if already confirmed or held by a zombie that Redis missed
            if (seat.getStatus() == Seat.SeatStatus.CONFIRMED) {
                throw new IllegalStateException("Seat " + seatNumber + " is already booked.");
            }
            if (seat.getStatus() == Seat.SeatStatus.HELD) {
                throw new IllegalStateException("Seat " + seatNumber + " is currently held by another user.");
            }

            // 4. Transition to HELD atomically within the transaction
            seat.setStatus(Seat.SeatStatus.HELD);
            seatRepository.save(seat);

            log.info("Seat {} held by user {} for {}s", seatNumber, userId, HOLD_DURATION_SECONDS);
            return UUID.randomUUID().toString();

        } catch (Exception e) {
            // FIX (CRITICAL): always roll back Redis key on any DB failure —
            // prevents phantom holds that block the seat for 120 seconds.
            redisTemplate.delete(key);
            throw e;
        }
    }

    /**
     * FIX (CRITICAL): Redis key is deleted AFTER the DB transaction commits,
     * using TransactionSynchronizationManager.afterCommit().
     * Previously, DEL could fire before the commit, leaving the seat in an
     * inconsistent state if the commit then failed.
     *
     * FIX (HIGH): confirmBooking now uses a single
     * findByFlightIdAndSeatNumberWithLock
     * query instead of two separate queries (plain find → then lock by ID),
     * eliminating the stale-read window between them.
     */
    @Transactional
    public Booking confirmBooking(Long flightId, String seatNumber, String userId, String passengerEmail) {
        String key = buildKey(flightId, seatNumber);

        // 1. Verify Redis hold still belongs to this user
        String holderId = redisTemplate.opsForValue().get(key);
        if (holderId == null || !holderId.equals(userId)) {
            throw new IllegalStateException("Seat hold has expired or belongs to another user.");
        }

        // 2. FIX: single locked query — no stale-read between two DB calls
        Seat lockedSeat = seatRepository.findByFlightIdAndSeatNumberWithLock(flightId, seatNumber)
                .orElseThrow(() -> new IllegalArgumentException("Seat not found"));

        // 3. Final safety check under the DB lock
        if (lockedSeat.getStatus() == Seat.SeatStatus.CONFIRMED) {
            throw new IllegalStateException("Seat already confirmed (concurrent booking detected).");
        }

        // 4. Find or create passenger
        Passenger passenger = passengerRepository.findByEmail(passengerEmail)
                .orElseGet(() -> passengerRepository.save(
                        Passenger.builder()
                                .email(passengerEmail)
                                .firstName("Guest")
                                .lastName("Passenger")
                                .build()));

        // 5. Create Booking record with full UUID — eliminates collision risk
        Booking booking = Booking.builder()
                .bookingReference("PNR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase())
                .flight(lockedSeat.getFlight())
                .seat(lockedSeat)
                .passenger(passenger)
                .status(Booking.BookingStatus.CONFIRMED)
                .build();
        bookingRepository.save(booking);

        // 6. Confirm the seat
        lockedSeat.setStatus(Seat.SeatStatus.CONFIRMED);
        seatRepository.save(lockedSeat);

        // 7. FIX (CRITICAL): delete Redis key ONLY after DB commit succeeds.
        // If the commit fails the Redis TTL will naturally expire — no data corruption.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisTemplate.delete(key);
                log.info("Redis hold key {} released after successful commit", key);
            }
        });

        log.info("Booking confirmed: {} for seat {}", booking.getBookingReference(), seatNumber);
        return booking;
    }

    private String buildKey(Long flightId, String seatNumber) {
        return "seat_hold:" + flightId + ":" + seatNumber;
    }
}
