package com.skyhigh.core.service;

import com.skyhigh.core.model.Booking;
import com.skyhigh.core.model.Flight;
import com.skyhigh.core.model.Seat;
import com.skyhigh.core.repository.BookingRepository;
import com.skyhigh.core.repository.FlightRepository;
import com.skyhigh.core.repository.SeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Tag;

@Tag("integration")
@SpringBootTest

@ActiveProfiles("test")
class BookingFlowIntegrationTest {

    @Autowired
    private SeatService seatService;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private FlightRepository flightRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setup() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        
        bookingRepository.deleteAll();
        seatRepository.deleteAll();
        flightRepository.deleteAll();

        Flight flight = Flight.builder()
                .flightNumber("SH-TEST")
                .departureTime(LocalDateTime.now().plusDays(1))
                .arrivalTime(LocalDateTime.now().plusDays(1).plusHours(2))
                .aircraftType("TestPlane")
                .build();
        flightRepository.save(flight);

        Seat seat = Seat.builder()
                .flight(flight)
                .seatNumber("1A")
                .seatClass(Seat.SeatClass.BUSINESS)
                .status(Seat.SeatStatus.HELD) // Assume held for test
                .build();
        seatRepository.save(seat);
    }

    @Test
    void testConcurrentConfirmation() throws InterruptedException {
        // Simulates 2 users trying to confirm the SAME seat at the exact same moment.
        // We mock Redis to say "Yes, you hold the lock" for BOTH (simulating a race
        // condition or logic error).
        // The DB Pessimistic Lock MUST stop one of them.

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        String seatNumber = "1A";
        Long flightId = flightRepository.findByFlightNumber("SH-TEST").get().getId();

        // Relaxed Redis mock to allow both threads to pass the "Check Redis" step
        when(valueOperations.get(anyString())).thenReturn("user123");

        Runnable confirmTask = () -> {
            try {
                seatService.confirmBooking(flightId, seatNumber, "user123", "test@test.com");
                successCount.incrementAndGet();
            } catch (Exception e) {
                // Expected failure for one thread
            } finally {
                latch.countDown();
            }
        };

        for (int i = 0; i < threadCount; i++) {
            executor.submit(confirmTask);
        }

        latch.await();

        // Assert: Only 1 should succeed
        assertEquals(1, successCount.get());

        // Assert: DB state is correct
        assertEquals(1, bookingRepository.count());
    }
}
