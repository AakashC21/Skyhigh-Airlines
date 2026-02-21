package com.skyhigh.core.repository;

import com.skyhigh.core.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByBookingReference(String bookingReference);
    boolean existsByFlightIdAndSeatIdAndStatus(Long flightId, Long seatId, Booking.BookingStatus status);
}
