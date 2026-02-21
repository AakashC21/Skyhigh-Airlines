package com.skyhigh.core.service;

import com.skyhigh.core.model.*;
import com.skyhigh.core.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SeatService.
 *
 * Strictness.STRICT_STUBS is intentional: it fails the test if a stub is
 * declared but never invoked, catching stale mocks early — exactly the issue
 * identified in the senior audit.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class SeatServiceTest {

        @Mock
        private SeatRepository seatRepository;
        @Mock
        private BookingRepository bookingRepository;
        @Mock
        private PassengerRepository passengerRepository;
        @Mock
        private StringRedisTemplate redisTemplate;
        @Mock
        private ValueOperations<String, String> valueOperations;

        @InjectMocks
        private SeatService seatService;

        private static final Long FLIGHT_ID = 1L;
        private static final String SEAT_NO = "1A";
        private static final String USER_ID = "user123";
        private static final String HOLD_KEY = "seat_hold:1:1A";
        private static final String EMAIL = "test@skyhigh.com";

        @BeforeEach
        void setUp() {
                lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        }

        // ─── holdSeat() ──────────────────────────────────────────────────────────

        @Test
        void holdSeat_Success_ReturnsUuid() {
                Seat availableSeat = Seat.builder()
                                .id(1L)
                                .seatNumber(SEAT_NO)
                                .status(Seat.SeatStatus.AVAILABLE)
                                .build();

                when(valueOperations.setIfAbsent(eq(HOLD_KEY), eq(USER_ID), any(Duration.class)))
                                .thenReturn(true);
                // FIX: stub the method actually called by the post-fix SeatService
                when(seatRepository.findByFlightIdAndSeatNumberWithLock(FLIGHT_ID, SEAT_NO))
                                .thenReturn(Optional.of(availableSeat));

                String ref = seatService.holdSeat(FLIGHT_ID, SEAT_NO, USER_ID);

                assertNotNull(ref, "holdSeat must return a non-null UUID hold reference");
                assertFalse(ref.isBlank());
                verify(valueOperations).setIfAbsent(eq(HOLD_KEY), eq(USER_ID), any(Duration.class));
                assertEquals(Seat.SeatStatus.HELD, availableSeat.getStatus(),
                                "Seat status must be updated to HELD within the transaction");
        }

    @Test
    void holdSeat_RedisAlreadyLocked_ThrowsAndSkipsDb() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> seatService.holdSeat(FLIGHT_ID, SEAT_NO, USER_ID));

        assertTrue(ex.getMessage().contains("currently held"));
        // Redis short-circuits; no DB round-trip should occur
        verifyNoInteractions(seatRepository);
    }

        @Test
        void holdSeat_SeatConfirmedInDb_ThrowsAndRollsBackRedisKey() {
                Seat confirmedSeat = Seat.builder()
                                .id(2L)
                                .seatNumber(SEAT_NO)
                                .status(Seat.SeatStatus.CONFIRMED)
                                .build();

                when(valueOperations.setIfAbsent(eq(HOLD_KEY), eq(USER_ID), any(Duration.class)))
                                .thenReturn(true);
                when(seatRepository.findByFlightIdAndSeatNumberWithLock(FLIGHT_ID, SEAT_NO))
                                .thenReturn(Optional.of(confirmedSeat));

                IllegalStateException ex = assertThrows(IllegalStateException.class,
                                () -> seatService.holdSeat(FLIGHT_ID, SEAT_NO, USER_ID));

                assertTrue(ex.getMessage().contains("already booked"),
                                "Message must declare the seat is already booked");
                // Redis phantom-hold must be cleaned up to prevent 120-second blockage
                verify(redisTemplate).delete(HOLD_KEY);
        }

        @Test
        void holdSeat_SeatHeldInDb_ThrowsAndRollsBackRedisKey() {
                // Redis key was free (TTL expired between SETNX and DB read),
                // but DB still shows HELD — zombie state.
                Seat heldSeat = Seat.builder()
                                .id(3L)
                                .seatNumber(SEAT_NO)
                                .status(Seat.SeatStatus.HELD)
                                .build();

                when(valueOperations.setIfAbsent(eq(HOLD_KEY), eq(USER_ID), any(Duration.class)))
                                .thenReturn(true);
                when(seatRepository.findByFlightIdAndSeatNumberWithLock(FLIGHT_ID, SEAT_NO))
                                .thenReturn(Optional.of(heldSeat));

                assertThrows(IllegalStateException.class,
                                () -> seatService.holdSeat(FLIGHT_ID, SEAT_NO, USER_ID));

                // Phantom hold must be released so the seat isn't blocked
                verify(redisTemplate).delete(HOLD_KEY);
        }

        // ─── confirmBooking() ────────────────────────────────────────────────────

        @Test
        void confirmBooking_Success_Returns_ConfirmedBooking() {
                Seat heldSeat = Seat.builder()
                                .id(10L)
                                .seatNumber(SEAT_NO)
                                .flight(Flight.builder().id(FLIGHT_ID).build())
                                .status(Seat.SeatStatus.HELD)
                                .build();

                Passenger passenger = Passenger.builder()
                                .id(1L).email(EMAIL).firstName("Alice").lastName("T").build();

                when(valueOperations.get(HOLD_KEY)).thenReturn(USER_ID);
                // FIX: stub the single locked-query method used by confirmBooking()
                when(seatRepository.findByFlightIdAndSeatNumberWithLock(FLIGHT_ID, SEAT_NO))
                                .thenReturn(Optional.of(heldSeat));
                when(passengerRepository.findByEmail(EMAIL)).thenReturn(Optional.of(passenger));

                // SeatService.confirmBooking() calls TransactionSynchronizationManager
                // .registerSynchronization() which requires an active TX context.
                // In a pure unit test there is no real TX, so we initialise one manually.
                TransactionSynchronizationManager.initSynchronization();
                Booking result;
                try {
                        result = seatService.confirmBooking(FLIGHT_ID, SEAT_NO, USER_ID, EMAIL);

                        // Trigger afterCommit() synchronization hooks synchronously so we can verify
                        // the Redis delete call within the same test thread.
                        TransactionSynchronizationManager.getSynchronizations()
                                        .forEach(s -> s.afterCommit());
                } finally {
                        TransactionSynchronizationManager.clearSynchronization();
                }

                assertNotNull(result);
                assertEquals(Booking.BookingStatus.CONFIRMED, result.getStatus());
                assertNotNull(result.getBookingReference());
                assertTrue(result.getBookingReference().startsWith("PNR-"),
                                "Booking reference must start with PNR-");
                assertEquals(Seat.SeatStatus.CONFIRMED, heldSeat.getStatus(),
                                "Seat status must flip to CONFIRMED");

                // Verify the afterCommit hook called Redis delete
                verify(redisTemplate).delete(HOLD_KEY);
        }

    @Test
    void confirmBooking_HoldBelongsToOtherUser_ThrowsIllegalState() {
        when(valueOperations.get(HOLD_KEY)).thenReturn("other_user_999");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> seatService.confirmBooking(FLIGHT_ID, SEAT_NO, USER_ID, EMAIL));

        assertTrue(ex.getMessage().contains("expired") || ex.getMessage().contains("another user"));
        verifyNoInteractions(seatRepository, bookingRepository, passengerRepository);
    }

    @Test
    void confirmBooking_HoldExpired_ThrowsIllegalState() {
        // Redis key TTL elapsed — key is gone
        when(valueOperations.get(HOLD_KEY)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> seatService.confirmBooking(FLIGHT_ID, SEAT_NO, USER_ID, EMAIL));

        verifyNoInteractions(seatRepository, bookingRepository, passengerRepository);
    }

        @Test
        void confirmBooking_SeatAlreadyConfirmedInDb_ThrowsIllegalState() {
                // Concurrent confirmation race: two users bypassed Redis; DB lock detects it
                Seat confirmedSeat = Seat.builder()
                                .id(10L)
                                .seatNumber(SEAT_NO)
                                .flight(Flight.builder().id(FLIGHT_ID).build())
                                .status(Seat.SeatStatus.CONFIRMED)
                                .build();

                when(valueOperations.get(HOLD_KEY)).thenReturn(USER_ID);
                when(seatRepository.findByFlightIdAndSeatNumberWithLock(FLIGHT_ID, SEAT_NO))
                                .thenReturn(Optional.of(confirmedSeat));

                IllegalStateException ex = assertThrows(IllegalStateException.class,
                                () -> seatService.confirmBooking(FLIGHT_ID, SEAT_NO, USER_ID, EMAIL));

                assertTrue(ex.getMessage().contains("confirmed") || ex.getMessage().contains("concurrent"));
                verifyNoInteractions(bookingRepository, passengerRepository);
        }
}
