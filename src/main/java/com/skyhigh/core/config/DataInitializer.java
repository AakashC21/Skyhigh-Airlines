package com.skyhigh.core.config;

import com.skyhigh.core.model.Flight;
import com.skyhigh.core.model.Seat;
import com.skyhigh.core.repository.FlightRepository;
import com.skyhigh.core.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Seeds the database with sample flight and seat data on startup.
 * Only runs if no flights are found (idempotent).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final FlightRepository flightRepository;
    private final SeatRepository seatRepository;

    @Override
    public void run(String... args) {
        if (flightRepository.count() > 0) {
            log.info("Data already initialized, skipping seed.");
            return;
        }

        log.info("Seeding demo data...");

        Flight flight = flightRepository.save(Flight.builder()
                .flightNumber("SH-101")
                .departureTime(LocalDateTime.now().plusDays(1))
                .arrivalTime(LocalDateTime.now().plusDays(1).plusHours(3))
                .aircraftType("Boeing 737")
                .build());

        // Create seats: 4 Business + 8 Economy
        List<Seat> seats = List.of(
                seat(flight, "1A", Seat.SeatClass.BUSINESS),
                seat(flight, "1B", Seat.SeatClass.BUSINESS),
                seat(flight, "1C", Seat.SeatClass.BUSINESS),
                seat(flight, "1D", Seat.SeatClass.BUSINESS),
                seat(flight, "10A", Seat.SeatClass.ECONOMY),
                seat(flight, "10B", Seat.SeatClass.ECONOMY),
                seat(flight, "10C", Seat.SeatClass.ECONOMY),
                seat(flight, "10D", Seat.SeatClass.ECONOMY),
                seat(flight, "20A", Seat.SeatClass.ECONOMY),
                seat(flight, "20B", Seat.SeatClass.ECONOMY),
                seat(flight, "20C", Seat.SeatClass.ECONOMY),
                seat(flight, "20D", Seat.SeatClass.ECONOMY));
        seatRepository.saveAll(seats);

        log.info("Demo flight SH-101 created with ID={} and {} seats", flight.getId(), seats.size());
    }

    private Seat seat(Flight flight, String number, Seat.SeatClass seatClass) {
        return Seat.builder()
                .flight(flight)
                .seatNumber(number)
                .seatClass(seatClass)
                .status(Seat.SeatStatus.AVAILABLE)
                .build();
    }
}
