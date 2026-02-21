package com.skyhigh.core.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skyhigh.core.model.*;
import com.skyhigh.core.repository.*;
import com.skyhigh.core.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class FlightController {

    private final SeatRepository seatRepository;
    private final FlightRepository flightRepository;
    private final SeatService seatService;
    private final BaggageService baggageService;
    private final WaitlistService waitlistService;
    private final BookingRepository bookingRepository;

    // ─────────────────────────────────────────────────────────────
    // GET /api/v1/flights
    // ─────────────────────────────────────────────────────────────
    @GetMapping("/flights")
    public ResponseEntity<List<Flight>> getAllFlights() {
        return ResponseEntity.ok(flightRepository.findAll());
    }

    // ─────────────────────────────────────────────────────────────
    // GET /api/v1/flights/{flightId}/seats
    // ─────────────────────────────────────────────────────────────
    @GetMapping("/flights/{flightId}/seats")
    public ResponseEntity<?> getSeatMap(@PathVariable Long flightId) {
        List<Seat> seats = seatRepository.findByFlightId(flightId);
        if (seats.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Flight not found or has no seats"));
        }
        return ResponseEntity.ok(seats);
    }

    // ─────────────────────────────────────────────────────────────
    // POST /api/v1/seats/hold
    // ─────────────────────────────────────────────────────────────
    @PostMapping("/seats/hold")
    public ResponseEntity<?> holdSeat(@RequestBody @Valid HoldRequest request) {
        try {
            String ref = seatService.holdSeat(request.flightId, request.seatNumber, request.userId);
            return ResponseEntity.ok(Map.of(
                    "status", "HELD",
                    "holdReference", ref,
                    "message", "Seat held for 120 seconds. Confirm quickly!"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // POST /api/v1/bookings/confirm
    // ─────────────────────────────────────────────────────────────
    @PostMapping("/bookings/confirm")
    public ResponseEntity<?> confirmBooking(@RequestBody @Valid ConfirmRequest request) {
        // Baggage validation
        if (request.baggageWeight > 0) {
            BigDecimal fee = baggageService.calculateExcessBaggageFee(request.baggageWeight);
            if (fee.compareTo(BigDecimal.ZERO) > 0 && !request.paymentProcessed) {
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(Map.of(
                        "error", "Excess baggage fee required before check-in",
                        "feeAmount", fee,
                        "currency", "USD",
                        "hint", "Retry with paymentProcessed=true after paying"));
            }
        }
        try {
            Booking booking = seatService.confirmBooking(
                    request.flightId, request.seatNumber, request.userId, request.email);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "status", "CONFIRMED",
                    "bookingReference", booking.getBookingReference(),
                    "seatNumber", request.seatNumber));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // POST /api/v1/waitlist/join
    // ─────────────────────────────────────────────────────────────
    @PostMapping("/waitlist/join")
    public ResponseEntity<?> joinWaitlist(@RequestBody @Valid JoinWaitlistRequest request) {
        // FIX (HIGH): use atomic ZADD NX — eliminates TOCTOU race between ZRANK
        // check and ZADD that allowed duplicate waitlist entries.
        boolean added = waitlistService.joinWaitlistIfAbsent(request.flightId, request.userId);
        if (!added) {
            Long position = waitlistService.getWaitlistPosition(request.flightId, request.userId);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Already on waitlist at position " + (position != null ? position + 1 : 1)));
        }
        Long newPosition = waitlistService.getWaitlistPosition(request.flightId, request.userId);
        return ResponseEntity.accepted().body(Map.of(
                "status", "WAITLISTED",
                "position", newPosition != null ? newPosition + 1 : 1,
                "message", "You will be notified when a seat becomes available"));
    }

    // ─────────────────────────────────────────────────────────────
    // GET /api/v1/bookings/{reference}
    // ─────────────────────────────────────────────────────────────
    @GetMapping("/bookings/{reference}")
    public ResponseEntity<?> getBooking(@PathVariable String reference) {
        return bookingRepository.findByBookingReference(reference)
                .<ResponseEntity<?>>map(b -> ResponseEntity.ok(Map.of(
                        "bookingReference", b.getBookingReference(),
                        "status", b.getStatus())))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Booking not found")));
    }

    // ─────────────────────────────────────────────────────────────
    // Inner Request DTOs — FIX (HIGH): all fields now validated
    // ─────────────────────────────────────────────────────────────

    public static class HoldRequest {
        @JsonProperty
        @NotNull(message = "flightId is required")
        @Positive(message = "flightId must be a positive number")
        public Long flightId;

        @JsonProperty
        @NotBlank(message = "seatNumber is required")
        @Size(min = 2, max = 5, message = "seatNumber must be 2–5 characters (e.g. 1A, 20B)")
        public String seatNumber;

        @JsonProperty
        @NotBlank(message = "userId is required")
        public String userId;
    }

    public static class ConfirmRequest {
        @JsonProperty
        @NotNull(message = "flightId is required")
        @Positive(message = "flightId must be a positive number")
        public Long flightId;

        @JsonProperty
        @NotBlank(message = "seatNumber is required")
        @Size(min = 2, max = 5)
        public String seatNumber;

        @JsonProperty
        @NotBlank(message = "userId is required")
        public String userId;

        @JsonProperty
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        public String email;

        @JsonProperty
        @Min(value = 0, message = "baggageWeight cannot be negative")
        @Max(value = 500, message = "baggageWeight cannot exceed 500 kg")
        public double baggageWeight;

        @JsonProperty
        public boolean paymentProcessed;
    }

    public static class JoinWaitlistRequest {
        @JsonProperty
        @NotNull(message = "flightId is required")
        @Positive(message = "flightId must be a positive number")
        public Long flightId;

        @JsonProperty
        @NotBlank(message = "userId is required")
        public String userId;
    }
}
