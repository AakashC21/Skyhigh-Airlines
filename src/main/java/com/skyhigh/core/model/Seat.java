package com.skyhigh.core.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.LocalDateTime;

@Entity
@Table(name = "seats", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"flight_id", "seatNumber"})
}, indexes = {
    @Index(name = "idx_seats_flight_status", columnList = "flight_id, status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_id", nullable = false)
    private Flight flight;

    @Column(nullable = false, length = 5)
    private String seatNumber; // e.g., "1A"

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private SeatClass seatClass; // ECONOMY, BUSINESS, FIRST

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Version
    private Long version; // Optimistic locking support

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
    
    public enum SeatClass {
        ECONOMY, BUSINESS, FIRST
    }
    
    public enum SeatStatus {
        AVAILABLE, HELD, CONFIRMED
    }
}
