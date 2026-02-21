# SkyHigh Core — Architecture Document

**Version:** 1.0.0  
**Last Updated:** 2026-02-20  
**Audience:** Engineering, DevOps, Technical Leadership

---

## 1. Architecture Overview

SkyHigh Core is a **stateless, horizontally scalable REST API service** built on Spring Boot 3.2 / Java 21. All mutable state is externalised to PostgreSQL (durable store) and Redis (ephemeral coordination layer). This separation allows any number of application instances to run simultaneously without shared in-process state.

```
┌──────────────────────────────────────────────────────────────────┐
│                        Client Layer                              │
│          (Mobile App / Web Frontend / 3rd-party API)             │
└──────────────────────┬───────────────────────────────────────────┘
                       │ HTTPS
┌──────────────────────▼───────────────────────────────────────────┐
│                    API Gateway / Load Balancer                   │
│            (TLS termination · JWT auth · Rate pre-filter)        │
└──────────────────────┬───────────────────────────────────────────┘
                       │ HTTP
          ┌────────────┼────────────┐
          ▼            ▼            ▼
┌─────────────┐ ┌─────────────┐ ┌─────────────┐
│  App Node 1 │ │  App Node 2 │ │  App Node N │   ← Stateless
│ Spring Boot │ │ Spring Boot │ │ Spring Boot │     instances
└──────┬──────┘ └──────┬──────┘ └──────┬──────┘
       │               │               │
       └───────────────┼───────────────┘
                       │
          ┌────────────┴────────────┐
          ▼                         ▼
┌─────────────────┐       ┌─────────────────────┐
│   PostgreSQL    │       │        Redis         │
│  (Primary +     │       │  (Seat Holds · TTL · │
│   Read Replica) │       │   Waitlist · Rate    │
│                 │       │   Limiting)          │
└─────────────────┘       └─────────────────────┘
```

---

## 2. Component Breakdown

### 2.1 REST API Layer — `FlightController`

Entry point for all client requests. Responsibilities:
- Route and validate incoming HTTP requests
- Enforce baggage pre-check before delegating to `SeatService`
- Return standardised JSON responses with correct HTTP status codes
- No business logic — purely orchestrates service calls

**Key Endpoints:**

| Method | Path | Delegates To |
|--------|------|-------------|
| `GET` | `/api/v1/flights` | `FlightRepository` |
| `GET` | `/api/v1/flights/{id}/seats` | `SeatRepository` |
| `POST` | `/api/v1/seats/hold` | `SeatService.holdSeat()` |
| `POST` | `/api/v1/bookings/confirm` | `BaggageService` → `SeatService.confirmBooking()` |
| `POST` | `/api/v1/waitlist/join` | `WaitlistService` |
| `GET` | `/api/v1/bookings/{pnr}` | `BookingRepository` |

---

### 2.2 Seat Service — `SeatService`

The core domain service. Implements the **two-phase locking protocol**:

```
Phase 1 — Redis Lock (Fast Path):
  SETNX seat_hold:{flightId}:{seatNumber} {userId} EX 120
  └─ Success → proceed to DB check
  └─ Failure → throw 409 Conflict immediately

Phase 2 — Postgres Lock (Safe Path, confirmation only):
  SELECT * FROM seats WHERE id = ? FOR UPDATE
  └─ Verify status != CONFIRMED
  └─ Create booking record
  └─ Update seat status → CONFIRMED
  └─ DELETE Redis key
```

**Why two phases?** Redis provides sub-millisecond mutual exclusion for the hold (fast, non-blocking). PostgreSQL `SELECT FOR UPDATE` guarantees ACID safety during confirmation, protecting against the edge case where Redis and DB diverge.

---

### 2.3 Waitlist Service — `WaitlistService`

Uses a **Redis Sorted Set** (`ZSET`) keyed by `waitlist:{flightId}`.

- **Score** = Unix timestamp at join time → guarantees FIFO ordering
- `ZADD` to join, `ZRANGE … WITHSCORES` to peek, `ZPOPMIN` to dequeue
- Dequeue is triggered by `CleanupScheduler` when a seat is released

```
waitlist:1  →  { "user_050": 1708422000000.0,
                  "user_099": 1708422005000.0, ... }
```

---

### 2.4 Baggage Service — `BaggageService`

Stateless calculation service.

```
Free allowance: 25 kg
Rate:           $15 USD / kg over limit
Fee formula:    max(0, weight - 25) × 15
```

Payment attestation is accepted as `paymentProcessed: true` in the request body. The actual payment transaction is handled by an external payment service (out of scope).

---

### 2.5 Rate Limiting — `RateLimitingConfig` + Bucket4j

Token bucket algorithm, one bucket per user/IP:
- **Capacity:** 50 tokens
- **Refill:** 25 tokens/second
- Enforced at the service layer; in production should be fronted at the API gateway

---

### 2.6 Cleanup Scheduler — `CleanupScheduler`

Background job that runs every **60 seconds**:

```
1. Query: SELECT * FROM seats WHERE status='HELD' AND updated_at < NOW() - 125s
2. For each result:
   a. Set status → AVAILABLE
   b. Call WaitlistService.popNextUser(flightId)
   c. If user found → trigger seat hold for that user (notification in v1.1)
```

This is the **safety net** for zombie holds — situations where the Redis TTL fired but the DB row was never reset (e.g., app crash mid-operation).

---

### 2.7 Data Initializer — `DataInitializer`

Runs once on application startup (`CommandLineRunner`). Seeds:
- Flight `SH-101` (Boeing 737, departs T+1 day)
- 12 seats: 4 `BUSINESS` (1A–1D) + 8 `ECONOMY` (10A–10D, 20A–20D)

Idempotent — skips seeding if flights already exist.

---

## 3. Data Model

```
┌──────────────┐        ┌──────────────────┐
│   flights    │        │      seats       │
│──────────────│        │──────────────────│
│ id (PK)      │◄──┐    │ id (PK)          │
│ flight_number│   └────│ flight_id (FK)   │
│ departure_   │        │ seat_number      │
│   time       │        │ seat_class       │
│ arrival_time │        │ status           │ ← AVAILABLE|HELD|CONFIRMED
│ aircraft_type│        │ version          │ ← optimistic lock version
│ created_at   │        │ updated_at       │ ← used by zombie cleanup
└──────────────┘        └──────────────────┘
                                │
                                │ 1
                        ┌───────▼──────────┐
                        │    bookings      │
                        │──────────────────│
                        │ id (PK)          │
                        │ booking_ref (UQ) │ ← PNR
                        │ flight_id (FK)   │
                        │ seat_id (FK, UQ) │ ← unique constraint
                        │ passenger_id (FK)│
                        │ status           │ ← CONFIRMED|CANCELLED
                        └──────────────────┘
                                │
                        ┌───────▼──────────┐
                        │   passengers     │
                        │──────────────────│
                        │ id (PK)          │
                        │ email (UQ)       │
                        │ first_name       │
                        │ last_name        │
                        └──────────────────┘
```

---

## 4. Concurrency Handling

### 4.1 The Race Condition Problem

Without locking, two simultaneous requests can both read a seat as `AVAILABLE`, both proceed, and create two bookings for the same seat — a **double-booking**.

### 4.2 Two-Phase Locking Solution

```
User A ──────────────────────────────────────────────────────────────►
         SETNX ✅  │                    confirmBooking()
         (Redis)   │                    SELECT FOR UPDATE ✅
                   │                    INSERT booking
                   │                    UPDATE seat status
                   │                    DEL Redis key

User B ──────────────────────────────────────────────────────────────►
         SETNX ❌ (Redis already set)
         → 409 Conflict returned immediately
```

### 4.3 Seat Lifecycle State Machine

```
              ┌───────────────────────────────────────┐
              │            AVAILABLE                  │
              └───────┬───────────────────────────────┘
                      │  holdSeat() — Redis SETNX
                      ▼
              ┌───────────────────────────────────────┐
              │              HELD                     │◄──────────────┐
              └───────┬───────────────────────────────┘               │
                      │                                               │
          ┌───────────┼─────────────────┐                            │
          │           │                 │                            │
          │ confirmBooking()   TTL expires (120s)         Zombie cleanup
          │           │        or crash                   (125s fallback)
          ▼           ▼                 ▼                            │
  ┌────────────┐  ┌──────────────────────┐                          │
  │ CONFIRMED  │  │      AVAILABLE       │──────────────────────────┘
  └────────────┘  └──────────────────────┘
  (terminal)
```

### 4.4 Handling Partial Failures

| Failure Point | Behaviour |
|---------------|-----------|
| Redis down during hold | Return 503; no DB write |
| DB write fails after Redis lock | Redis key rolled back in `finally` block |
| App crashes after Redis lock, before DB commit | Zombie cleanup reclaims seat within 125s |
| App crashes after DB commit | Booking is durable; Redis key will expire naturally |

---

## 5. Scaling Strategy

### 5.1 Horizontal Scaling (Application Layer)

All application nodes are **stateless**. Adding nodes requires:
1. Deploy new container with the same environment variables
2. Register with load balancer

No configuration changes, no data migration, no coordination required.

```
                    ┌─────────────────────────┐
                    │      Load Balancer       │
                    │   (Round Robin / Least   │
                    │       Connections)       │
                    └────────────┬────────────┘
              ┌─────────────────┼──────────────────┐
              ▼                 ▼                  ▼
        ┌──────────┐      ┌──────────┐      ┌──────────┐
        │  Node 1  │      │  Node 2  │      │  Node N  │
        └────┬─────┘      └────┬─────┘      └────┬─────┘
             │                 │                  │
             └─────────────────┼──────────────────┘
                               │
                    ┌──────────┴──────────┐
                    ▼                     ▼
               PostgreSQL              Redis
```

### 5.2 Read Scaling (Database Layer)

Seat map reads (`GET /flights/{id}/seats`) are **read-heavy and cacheable**:

```
Request → Check Redis cache (key: seatmap:{flightId})
              │
     ┌────────┴────────┐
     │ HIT             │ MISS
     ▼                 ▼
  Return cached   Query Postgres
  response        (Read Replica)
                       │
                  Cache result
                  (TTL: 5s)
                       │
                  Return response
```

Cache TTL of 5 seconds is a deliberate trade-off: slight eventual consistency on seat map display vs. massive DB load reduction.

### 5.3 Write Scaling (Database Layer)

All writes (hold, confirm, cleanup) go to the **primary** Postgres node. Key controls:
- **HikariCP connection pool:** 10 connections per instance (configurable)
- **Pessimistic locking scope:** Only on single row during confirmation (`SELECT FOR UPDATE` on `seats.id`)
- **Short transactions:** Lock is held only for the duration of the confirm operation (<50ms typical)

### 5.4 Redis Scaling

Redis runs in **standalone mode** for development. In production:

```
Redis Sentinel (HA)          Redis Cluster (Sharding)
──────────────────           ──────────────────────────
Primary ◄─ Replica 1         Shard 1: seat_hold:1:*
Primary ◄─ Replica 2         Shard 2: seat_hold:2:*
Sentinel monitors            Shard 3: waitlist:*
auto-promotes replica        Hash-slot based routing
on primary failure
```

---

## 6. Request Flow Diagrams

### 6.1 Seat Hold Flow

```
Client          FlightController       SeatService         Redis       PostgreSQL
  │                    │                    │                │               │
  │── POST /seats/hold ►│                    │                │               │
  │                    │── holdSeat() ──────►│                │               │
  │                    │                    │── SETNX ───────►│               │
  │                    │                    │◄── OK / FAIL ───│               │
  │                    │                    │                │               │
  │                    │              [If OK]                │               │
  │                    │                    │── findByFlight ──────────────►│
  │                    │                    │◄── Seat entity ───────────────│
  │                    │                    │── save(HELD) ────────────────►│
  │                    │                    │◄── OK ────────────────────────│
  │                    │◄── holdReference ──│                │               │
  │◄── 200 holdRef ────│                    │                │               │
```

### 6.2 Booking Confirmation Flow

```
Client          FlightController    BaggageService    SeatService     Redis     PostgreSQL
  │                    │                  │                │            │             │
  │── POST /confirm ──►│                  │                │            │             │
  │                    │── calcFee() ────►│                │            │             │
  │                    │◄── fee ──────────│                │            │             │
  │                    │                  │                │            │             │
  │           [fee>0 & !paid]             │                │            │             │
  │◄── 402 fee amt ────│                  │                │            │             │
  │                    │                  │                │            │             │
  │           [fee=0 OR paid=true]        │                │            │             │
  │                    │── confirmBooking() ──────────────►│            │             │
  │                    │                  │                │── GET ────►│             │
  │                    │                  │                │◄── userId ─│             │
  │                    │                  │                │── SELECT FOR UPDATE ────►│
  │                    │                  │                │◄── Seat row (locked) ────│
  │                    │                  │                │── INSERT booking ───────►│
  │                    │                  │                │── UPDATE seat CONFIRMED ►│
  │                    │                  │                │◄── commit ───────────────│
  │                    │                  │                │── DEL key ►│             │
  │                    │◄── Booking ───────────────────────│            │             │
  │◄── 201 PNR ────────│                  │                │            │             │
```

---

## 7. Technology Stack

| Layer | Technology | Version | Reason |
|-------|-----------|---------|--------|
| Language | Java | 21 LTS | Virtual threads (Project Loom) for high-concurrency without callback hell |
| Framework | Spring Boot | 3.2.3 | Production-grade; JPA, scheduling, validation, actuator built-in |
| ORM | Hibernate / Spring Data JPA | 6.4 | Pessimistic locking, repository pattern |
| Database | PostgreSQL | 15 | ACID transactions, `SELECT FOR UPDATE`, proven at scale |
| Cache & Locks | Redis | 7 | Atomic SETNX, TTL-native, ZSET for FIFO waitlist |
| Rate Limiting | Bucket4j | 8.7 | Token bucket algorithm, Redis-compatible |
| Build | Maven | 3.9 | Dependency management, Spring Boot plugin |
| Containerisation | Docker + Compose | 25+ | Reproducible dev/prod environment parity |
| Serialization | Jackson + Hibernate6Module | 2.15 | Handles Hibernate lazy proxy serialization |

---

## 8. Trade-offs

### 8.1 Redis TTL vs. Database-Driven Expiry

| Approach | Pros | Cons |
|----------|------|------|
| **Redis TTL (chosen)** | Automatic, no polling, sub-ms check | Redis is a single point of failure; TTL can drift on clock skew |
| DB-only scheduler | No Redis dependency for expiry | Delays up to scheduler interval; DB polling load |
| Redis Keyspace Events | Real-time expiry notification | Requires `notify-keyspace-events Ex` config; at-least-once delivery |

**Decision:** Redis TTL as primary + DB scheduler as safety net gives the best of all three without depending on keyspace event delivery guarantees.

---

### 8.2 Pessimistic vs. Optimistic Locking

| Approach | Pros | Cons |
|----------|------|------|
| **Pessimistic (`FOR UPDATE`) (chosen)** | Guarantees no double-booking; no retry logic needed | Row-level DB lock held during transaction; lower throughput under extreme contention |
| Optimistic (version field) | Higher throughput; no DB locks | Requires retry loops; higher app complexity; can starve under heavy load |

**Decision:** For seat confirmation — which is a critical, irreversible operation — pessimistic locking is the correct trade-off. The Redis pre-filter (SETNX) means `SELECT FOR UPDATE` is only reached by one user per seat at a time, so lock contention is effectively zero in practice.

---

### 8.3 In-Process Rate Limiting vs. API Gateway

| Approach | Pros | Cons |
|----------|------|------|
| **In-process Bucket4j (chosen for v1.0)** | Simple, no infrastructure dependency | State is per-instance unless Redis-backed; bypassed if hitting multiple nodes |
| API Gateway (e.g., Kong, NGINX) | Enforced before app; single config | External dependency; more ops complexity |

**Decision:** In-process for v1.0 with Redis-backed state. Moving to API gateway layer is in the v1.1 roadmap for stricter enforcement.

---

### 8.4 Seat Map Caching (5s TTL)

| TTL | Benefit | Risk |
|-----|---------|------|
| 0s (no cache) | Always real-time | Full DB load on every request; fails NFR-P-01 under load |
| **5s (chosen)** | 95%+ cache hit rate on busy flights | User may briefly see a stale seat as AVAILABLE |
| 30s+ | Lowest DB load | Confusing UX; user attempts hold on seat already taken |

**Decision:** 5 seconds is consistent with the UX expectation that the seat map page refreshes every few seconds. The hold step will correctly reject a stale selection.

---

## 9. Security Considerations

- **Authentication:** JWT validation is delegated to the API Gateway. This service trusts the `userId` field in request bodies for v1.0; v1.1 will validate against the JWT `sub` claim.
- **PNR Unpredictability:** References are UUID-derived (`PNR-XXXXXX`), not sequential integers.
- **PII in Logs:** Emails and user IDs are not logged at `INFO` level. Only IDs appear in `DEBUG` traces.
- **Redis Key Namespace:** All keys are namespaced (`seat_hold:`, `waitlist:`) to prevent collisions in shared Redis instances.

---

## 10. Future Architecture Considerations (v1.1+)

| Feature | Approach |
|---------|----------|
| JWT validation in-service | Spring Security + JWT filter |
| Real-time seat map updates | WebSocket / Server-Sent Events |
| Boarding pass generation | Downstream microservice, async via message queue |
| Multi-region deployment | Active-passive Postgres replication; Redis Cluster per region |
| Circuit breaker for Redis | Resilience4j `@CircuitBreaker` on Redis calls |
| Distributed tracing | OpenTelemetry → Jaeger / Zipkin |
| Cancellation flow | Saga pattern with compensating transactions |

---

*This document reflects the architecture as of v1.0.0. For design change proposals, open an RFC and update this document before merging.*
