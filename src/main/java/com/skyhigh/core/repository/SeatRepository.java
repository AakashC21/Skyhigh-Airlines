package com.skyhigh.core.repository;

import com.skyhigh.core.model.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByFlightId(Long flightId);

    Optional<Seat> findByFlightIdAndSeatNumber(Long flightId, String seatNumber);

    /** Used by CleanupScheduler to find zombie holds */
    List<Seat> findByStatusAndUpdatedAtBefore(Seat.SeatStatus status, LocalDateTime cutoff);

    /** Pessimistic lock by PK — used in legacy path (kept for compatibility) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdWithLock(Long id);

    /**
     * FIX (HIGH): Single locked query that replaces the two-step
     * (findByFlightIdAndSeatNumber → findByIdWithLock) used in SeatService.
     * Eliminates the stale-read window between the plain read and the lock
     * acquisition.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.flight.id = :flightId AND s.seatNumber = :seatNumber")
    Optional<Seat> findByFlightIdAndSeatNumberWithLock(Long flightId, String seatNumber);
}
