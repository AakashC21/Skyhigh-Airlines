# SkyHigh Core — Product Requirements Document

**Product:** SkyHigh Core — Digital Check-In System  
**Version:** 1.0.0  
**Status:** Draft  
**Author:** SkyHigh Engineering  
**Last Updated:** 2026-02-20  
**Audience:** Engineering, Product, QA, Operations

---

## 1. Problem Statement

SkyHigh Airlines' legacy check-in infrastructure is a monolithic, desktop-ticketing-agent-centric flow that cannot scale to meet the demands of modern digital travellers. Key pain points include:

- **No self-service check-in.** Passengers must queue at counters or kiosks, creating bottlenecks up to 90 minutes before departure.
- **Race conditions on seat assignment.** The legacy system has no atomic seat locking, leading to double-bookings on popular routes during peak loads.
- **No waitlist management.** When a passenger releases a seat, no automated mechanism re-assigns it — agents must intervene manually.
- **No digital baggage pre-processing.** Excess baggage is identified and charged only at the physical counter, causing delays and revenue leakage.
- **No abuse prevention.** Bots and automated scripts are able to hold multiple seats simultaneously, degrading the experience for legitimate users.

SkyHigh Core is the backend service that solves all of the above by providing a reliable, concurrent, API-first digital check-in platform.

---

## 2. Goals

### 2.1 Primary Goals

| # | Goal | Priority |
|---|------|----------|
| G1 | Enable passengers to check in digitally without visiting a counter | P0 |
| G2 | Guarantee conflict-free seat assignment under high concurrency | P0 |
| G3 | Automatically assign released seats to waitlisted passengers (FIFO) | P0 |
| G4 | Enforce baggage weight limits and gate excess baggage fees digitally | P1 |
| G5 | Protect the system from bots and abusive check-in patterns | P1 |
| G6 | Achieve sub-second seat map load times for hundreds of concurrent users | P1 |

### 2.2 Non-Goals (Out of Scope for v1.0)

- Real payment gateway integration (baggage fee is mocked; a payment token is accepted)
- Boarding pass generation and barcode/QR issuance
- Loyalty programme and frequent-flyer integrations
- Multi-leg / connecting flight check-in
- Passenger-facing mobile/web UI (API consumers only)
- Airline staff admin dashboard

---

## 3. Functional Requirements

### 3.1 Seat Management

| ID | Requirement |
|----|-------------|
| FR-SM-01 | The system SHALL present a real-time seat map for a given flight, showing `AVAILABLE`, `HELD`, and `CONFIRMED` seats. |
| FR-SM-02 | A passenger SHALL be able to hold exactly one available seat per flight at a time. |
| FR-SM-03 | A seat hold SHALL automatically expire after **120 seconds** if not confirmed. |
| FR-SM-04 | When a hold expires, the seat SHALL transition back to `AVAILABLE` within 5 seconds. |
| FR-SM-05 | Only the passenger who holds a seat SHALL be able to confirm it. |
| FR-SM-06 | A confirmed seat SHALL be immutable and cannot be re-held or re-assigned without explicit cancellation. |
| FR-SM-07 | The system SHALL prevent two passengers from holding the same seat simultaneously (mutual exclusion). |

### 3.2 Booking Confirmation

| ID | Requirement |
|----|-------------|
| FR-BK-01 | A passenger SHALL confirm a booking by providing: flight ID, seat number, user ID, and email. |
| FR-BK-02 | A booking confirmation SHALL produce a unique **PNR reference** (alphanumeric, ≥ 8 chars). |
| FR-BK-03 | The system SHALL reject confirmation if the hold has expired or belongs to a different user. |
| FR-BK-04 | If a passenger does not exist by email, the system SHALL create a Passenger record automatically. |
| FR-BK-05 | Booking status SHALL be queryable by PNR reference. |

### 3.3 Waitlist Management

| ID | Requirement |
|----|-------------|
| FR-WL-01 | A passenger MAY join a waitlist for a flight when all seats are confirmed. |
| FR-WL-02 | The waitlist SHALL be maintained in **FIFO order** based on join timestamp. |
| FR-WL-03 | When a seat becomes available (hold expiry or cancellation), the system SHALL automatically notify / assign to the first user on the waitlist. |
| FR-WL-04 | A passenger SHALL NOT appear on the same flight's waitlist more than once. |
| FR-WL-05 | A passenger's waitlist position SHALL be returned upon joining. |

### 3.4 Baggage Processing

| ID | Requirement |
|----|-------------|
| FR-BG-01 | The system SHALL accept the declared baggage weight during booking confirmation. |
| FR-BG-02 | The free allowance per passenger SHALL be **25 kg**. Weight above this is "excess". |
| FR-BG-03 | Excess baggage fee SHALL be calculated at **$15 USD per kg** above the free limit. |
| FR-BG-04 | If excess baggage fee > $0 and `paymentProcessed = false`, the system SHALL block booking confirmation with HTTP 402 and return the fee amount. |
| FR-BG-05 | If `paymentProcessed = true`, the system SHALL proceed with booking regardless of fee. |

### 3.5 Abuse & Rate Limiting

| ID | Requirement |
|----|-------------|
| FR-AB-01 | Each user/IP SHALL be limited to **50 check-in attempts per second** (token bucket algorithm). |
| FR-AB-02 | Requests exceeding the rate limit SHALL receive HTTP 429 with a `Retry-After` header. |
| FR-AB-03 | Rate limit state SHALL persist across all application instances (distributed enforcement via Redis). |
| FR-AB-04 | Rate limit violations SHALL be logged for audit and monitoring purposes. |

### 3.6 Expiration & Zombie Cleanup

| ID | Requirement |
|----|-------------|
| FR-EX-01 | Seat holds SHALL be stored in Redis with a 120-second TTL as the primary expiration mechanism. |
| FR-EX-02 | A background scheduler SHALL run every **60 seconds** and reset any DB rows stuck in `HELD` state older than 125 seconds (zombie cleanup). |
| FR-EX-03 | Upon zombie cleanup, the scheduler SHALL trigger the waitlist assignment for that seat. |

---

## 4. Non-Functional Requirements

### 4.1 Performance

| ID | Requirement | Target |
|----|-------------|--------|
| NFR-P-01 | Seat map load time (P95) | **< 1 second** at 500 concurrent users |
| NFR-P-02 | Seat hold (POST) latency (P95) | **< 300ms** |
| NFR-P-03 | Booking confirmation latency (P95) | **< 500ms** |
| NFR-P-04 | Throughput | **≥ 500 hold/confirm requests/sec** on 4-core instance |

### 4.2 Scalability

| ID | Requirement |
|----|-------------|
| NFR-SC-01 | The application layer SHALL be **horizontally scalable** (stateless services; all state in Postgres + Redis). |
| NFR-SC-02 | Redis SHALL act as the distributed coordination layer for seat holds and rate limits. |
| NFR-SC-03 | Postgres read replicas MAY be used to scale seat map queries without write contention. |
| NFR-SC-04 | The system SHALL support linear scaling with no code changes up to **10 application instances**. |

### 4.3 Reliability & Availability

| ID | Requirement |
|----|-------------|
| NFR-RL-01 | Target availability: **99.9% uptime** (≤ 8.7 hours/year downtime) |
| NFR-RL-02 | If Redis is unavailable, seat hold attempts SHALL fail fast (timeout ≤ 200ms) and return HTTP 503. |
| NFR-RL-03 | If a seat hold is recorded in Redis but the DB write fails, the Redis key SHALL be rolled back. |
| NFR-RL-04 | The database layer SHALL use ACID transactions with pessimistic locking (`SELECT FOR UPDATE`) for all booking confirmations. |
| NFR-RL-05 | The zombie cleanup scheduler SHALL guarantee eventual consistency of seat state within **125 seconds** of a hold expiry. |

### 4.4 Security

| ID | Requirement |
|----|-------------|
| NFR-SE-01 | All API endpoints SHALL require a valid JWT bearer token (future phase). |
| NFR-SE-02 | User IDs in hold/confirm requests SHALL be validated against the authenticated JWT subject. |
| NFR-SE-03 | PNR references SHALL be opaque and unpredictable (UUID-derived). |
| NFR-SE-04 | No personally identifiable information (PII) SHALL be logged at INFO level or below. |
| NFR-SE-05 | All inter-service communication SHALL use TLS 1.2 or higher in production. |

### 4.5 Observability

| ID | Requirement |
|----|-------------|
| NFR-OB-01 | All critical operations (hold, confirm, expire, waitlist assignment) SHALL produce structured JSON logs with `traceId`, `userId`, `flightId`, `seatNumber`. |
| NFR-OB-02 | The application SHALL expose a `/actuator/health` endpoint for container health probes. |
| NFR-OB-03 | Key business metrics SHALL be exported (seat holds/min, confirmation rate, waitlist depth, expiry rate). |
| NFR-OB-04 | Redis connection failures SHALL trigger `ERROR` level logs with alerting integration. |

### 4.6 Data Integrity

| ID | Requirement |
|----|-------------|
| NFR-DI-01 | At no point SHALL two confirmed bookings reference the same seat+flight combination. |
| NFR-DI-02 | All entity mutations SHALL be idempotency-safe; duplicate confirmation requests with the same PNR SHALL return the existing result. |
| NFR-DI-03 | Seat status transitions SHALL only follow the allowed lifecycle: `AVAILABLE → HELD → CONFIRMED` or `HELD → AVAILABLE`. |

---

## 5. Success Metrics

### 5.1 Business KPIs (6 months post-launch)

| Metric | Baseline (Legacy) | Target |
|--------|-------------------|--------|
| Digital check-in adoption | 0% | ≥ 60% of eligible passengers |
| Counter queue wait time | 25 min avg | ≤ 8 min avg |
| Double-booking incidents per month | 12 | **0** |
| Excess baggage fee collection rate | 60% | ≥ 92% |
| Waitlist seat fill rate | N/A | ≥ 85% of released seats filled via waitlist |

### 5.2 Technical KPIs

| Metric | Target |
|--------|--------|
| P95 seat map load time | < 1s |
| Double-booking events | 0 per release cycle |
| Zombie hold cleanup rate | 100% within 125s |
| Rate-limit false positive rate | < 0.1% of legitimate users blocked |
| System error rate (5xx) | < 0.5% of all requests |
| Deployment frequency | ≥ 2 per week |

---

## 6. Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|----|------|-----------|--------|------------|
| R-01 | Redis outage causes all seat holds to fail | Medium | High | Health checks fail fast; scheduler provides DB fallback; circuit breaker planned for v1.1 |
| R-02 | Clock skew across instances causes hold TTL inconsistency | Low | Medium | Use Redis TTL as single source of truth; avoid relying on application server clocks |
| R-03 | DB contention under heavy `SELECT FOR UPDATE` during peak boarding | Medium | High | Connection pool tuning (HikariCP); pessimistic lock only on confirmation path; read replicas for reads |
| R-04 | Passengers gaming the hold TTL window to monopolise seats | Medium | Medium | Rate limiting (FR-AB-01); user can only hold 1 seat/flight (enforced by Redis key structure) |
| R-05 | Integration with payment service fails mid-confirmation | Low | High | Baggage payment is currently mocked; transactional rollback ensures seat is not confirmed if payment verification fails |
| R-06 | GDPR / data privacy non-compliance on passenger PII | Low | High | PII not logged; email hashed for analytics; full data-deletion API in roadmap |
| R-07 | Zombie cleanup scheduler consumes excessive DB I/O during peak | Low | Medium | Scheduler uses indexed query on `(status, updated_at)`; runs at 60s intervals, not continuously |

---

## 7. Assumptions

| ID | Assumption |
|----|------------|
| A-01 | Each passenger is uniquely identified by their **email address** within the system for v1.0. |
| A-02 | Seat inventory is pre-loaded into the database by the airline operations team before check-in opens. |
| A-03 | Check-in window opens **24 hours** before departure and closes **45 minutes** before departure (enforced at the API gateway layer, not in this service). |
| A-04 | A passenger may only hold **one seat per flight at a time**. |
| A-05 | Baggage fee payment is handled by an external payment service; this service only accepts a `paymentProcessed: true` attestation. |
| A-06 | Redis will be deployed in a **highly available, replicated** configuration in production (e.g., Redis Sentinel or Redis Cluster). |
| A-07 | The JWT authentication layer (API gateway or middleware) will validate tokens **before** requests reach this service. |
| A-08 | Infrastructure is containerised (Docker / Kubernetes) and CI/CD pipelines are managed separately from this service. |
| A-09 | Load testing is the responsibility of the QA/Performance Engineering team and will be conducted pre-launch against a staging environment. |
| A-10 | Cancellation and refund flows are out of scope for v1.0 and will be handled by the existing reservation management system. |

---

## 8. API Contract Summary

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/flights` | List all flights |
| `GET` | `/api/v1/flights/{id}/seats` | Get real-time seat map |
| `POST` | `/api/v1/seats/hold` | Hold a seat (120s TTL) |
| `POST` | `/api/v1/bookings/confirm` | Confirm booking (baggage + payment validation) |
| `POST` | `/api/v1/waitlist/join` | Join flight waitlist |
| `GET` | `/api/v1/bookings/{pnr}` | Look up booking by PNR reference |

---

## 9. Technology Decisions Summary

| Component | Choice | Rationale |
|-----------|--------|-----------|
| Language | Java 21 (LTS) | Virtual threads (Loom) for high concurrency |
| Framework | Spring Boot 3.2 | Production-grade ecosystem, JPA, scheduling |
| Database | PostgreSQL 15 | ACID, `SELECT FOR UPDATE`, JSON support |
| Cache / Lock | Redis 7 | Atomic SETNX for holds, TTL for expiry, ZSET for waitlist |
| Rate Limiting | Bucket4j (token bucket) | Distributed, Redis-backed |
| Containerisation | Docker + Docker Compose | Reproducible local/prod parity |

---

*This PRD reflects the state of requirements as of v1.0.0. Any changes must be reviewed by Engineering and Product before implementation.*
