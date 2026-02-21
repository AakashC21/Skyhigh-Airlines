# SkyHigh Core — Project Structure

**Version:** 1.0.0  
**Last Updated:** 2026-02-20

---

## Root Directory Layout

```
Skyhigh-Airlines/
│
├── src/
│   ├── main/
│   │   ├── java/com/skyhigh/core/
│   │   │   ├── SkyHighCoreApplication.java      ← App entry point
│   │   │   ├── config/                          ← All Spring config beans
│   │   │   ├── controller/                      ← REST API layer
│   │   │   ├── model/                           ← JPA domain entities
│   │   │   ├── repository/                      ← Data access interfaces
│   │   │   ├── scheduler/                       ← Background jobs
│   │   │   └── service/                         ← Business logic
│   │   └── resources/
│   │       └── application.properties           ← App configuration
│   └── test/
│       ├── java/com/skyhigh/core/service/       ← Unit & integration tests
│       └── resources/
│           └── application-test.properties      ← Test-specific config (H2)
│
├── ARCHITECTURE.md                              ← System design document
├── WORKFLOW_DESIGN.md                           ← Flow diagrams
├── PROJECT_STRUCTURE.md                         ← This file
├── PRD.md                                       ← Product requirements
├── README.md                                    ← Setup & run guide
├── API-SPECIFICATION.yml                        ← OpenAPI 3.0 spec
├── test_endpoints.sh                            ← Endpoint test script
├── Dockerfile                                   ← Multi-stage Docker build
├── docker-compose.yml                           ← Full stack local env
└── pom.xml                                      ← Maven build config
```

---

## Module Breakdown

### `SkyHighCoreApplication.java`
Main class. Bootstraps the Spring Boot application context.  
Annotations: `@SpringBootApplication`, `@EnableScheduling`.

---

### `config/` — Configuration Layer

| File | Purpose |
|------|---------|
| `DataInitializer.java` | Seeds demo flight + seats on first startup (`CommandLineRunner`) |
| `JacksonConfig.java` | Registers `Hibernate6Module` + `JavaTimeModule` for correct JSON serialization |
| `RateLimitingConfig.java` | Defines the Bucket4j token-bucket factory for abuse detection |

**Nothing in `config/` contains business logic.** It only wires infrastructure.

---

### `controller/` — REST API Layer

| File | Purpose |
|------|---------|
| `FlightController.java` | All HTTP endpoints: flights, seats, hold, confirm, waitlist, booking lookup |

**Controllers do not contain business logic.** They validate the HTTP contract (baggage pre-check is an exception — a lightweight guard), delegate to services, and map outcomes to HTTP responses.

**Inner DTOs** (`HoldRequest`, `ConfirmRequest`, `JoinWaitlistRequest`) live as static inner classes of `FlightController` for colocation with their handler methods.

---

### `model/` — Domain Entities

| File | Entity | Key Fields |
|------|--------|-----------|
| `Flight.java` | `flights` table | `flightNumber`, `departureTime`, `arrivalTime` |
| `Seat.java` | `seats` table | `seatNumber`, `seatClass`, `status` (AVAILABLE / HELD / CONFIRMED), `version`, `updatedAt` |
| `Passenger.java` | `passengers` table | `email` (unique), `firstName`, `lastName` |
| `Booking.java` | `bookings` table | `bookingReference` (PNR), `status`, FK to seat + passenger + flight |

**Entities are plain JPA objects.** No service calls, no business rules. Enums are defined as nested types within their owning entity (e.g., `Seat.SeatStatus`, `Seat.SeatClass`).

---

### `repository/` — Data Access Layer

| File | Purpose |
|------|---------|
| `FlightRepository.java` | Find by flight number |
| `SeatRepository.java` | Find by flight and seat number; `findByIdWithLock()` (pessimistic lock); `findByStatusAndUpdatedAtBefore()` (zombie cleanup) |
| `PassengerRepository.java` | Find or create by email |
| `BookingRepository.java` | Find by PNR reference |

All repositories extend `JpaRepository<Entity, Long>` — no custom SQL except the `@Lock` query on `SeatRepository`.

---

### `service/` — Business Logic Layer

**This is where all domain rules live.**

| File | Responsibility |
|------|---------------|
| `SeatService.java` | `holdSeat()`: Redis SETNX lock + DB status check. `confirmBooking()`: Redis hold verification → `SELECT FOR UPDATE` → booking creation → seat update → Redis cleanup |
| `WaitlistService.java` | `joinWaitlist()`: ZADD to Redis ZSET. `getWaitlistPosition()`: ZRANK. `popNextUser()`: ZPOPMIN — dequeues the earliest joined user |
| `BaggageService.java` | `calculateExcessBaggageFee()`: stateless fee calculator (25 kg free, $15/kg over). `simulatePayment()`: mock payment delay |

---

### `scheduler/` — Background Jobs

| File | Purpose |
|------|---------|
| `CleanupScheduler.java` | Runs every 60 seconds. Finds DB rows in `HELD` state older than 125 seconds (zombie holds) and resets them to `AVAILABLE`. Triggers waitlist pop for each released seat. |

The scheduler is the **reliability safety net** — it guarantees eventual consistency even if the primary Redis TTL path fails (e.g., app crash).

---

### `resources/`

| File | Purpose |
|------|---------|
| `application.properties` | Primary config: datasource URL, Redis host, JPA settings, scheduler enable |
| `application-test.properties` | Override for `test` profile: H2 in-memory DB, `ddl-auto=create-drop` |

---

### `test/`

| File | Type | What It Tests |
|------|------|--------------|
| `SeatServiceTest.java` | Unit test (Mockito) | `holdSeat()` success, double-hold rejection, `confirmBooking()` success, expired hold rejection |
| `BookingFlowIntegrationTest.java` | Integration test (`@SpringBootTest`) | Concurrent confirmation race condition — verifies only 1 of 2 threads succeeds |

---

## Dependency Flow

```
FlightController
    │
    ├──► SeatService ──────────────────► SeatRepository      (PostgreSQL)
    │         │                     ──► PassengerRepository  (PostgreSQL)
    │         │                     ──► BookingRepository    (PostgreSQL)
    │         └─────────────────────► StringRedisTemplate    (Redis)
    │
    ├──► BaggageService  (stateless, no external deps)
    │
    └──► WaitlistService ─────────────► StringRedisTemplate  (Redis)

CleanupScheduler ────────────────────► SeatRepository        (PostgreSQL)
                 └───────────────────► WaitlistService        (Redis)
```

**Rule:** Services never call other services except `CleanupScheduler → WaitlistService`. Controllers never touch repositories directly.
