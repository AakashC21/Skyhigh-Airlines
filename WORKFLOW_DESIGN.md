# SkyHigh Core — Workflow Design

**Version:** 1.0.0  
**Last Updated:** 2026-02-20

---

## 1. Seat Booking Flow

The primary happy-path check-in lifecycle: a passenger selects, holds, and confirms a seat.

```
  Passenger                 API                  Redis              PostgreSQL
      │                      │                     │                     │
      │── GET /flights ──────►│                     │                     │
      │◄── [SH-101, ...]  ───│                     │                     │
      │                      │                     │                     │
      │── GET /flights/1 ────►│                     │                     │
      │    /seats             │── seatRepository ───────────────────────►│
      │                      │◄── [1A:AVAIL, ...] ──────────────────────│
      │◄── seat map ─────────│                     │                     │
      │                      │                     │                     │
      │  [Selects seat 1A]   │                     │                     │
      │                      │                     │                     │
      │── POST /seats/hold ──►│                     │                     │
      │   flightId:1          │── SETNX ────────────►│                     │
      │   seatNumber:"1A"     │   seat_hold:1:1A    │  (TTL = 120s)       │
      │   userId:"user_001"   │◄── OK ──────────────│                     │
      │                      │── findSeat ──────────────────────────────►│
      │                      │◄── seat (AVAILABLE) ─────────────────────│
      │                      │── save(HELD) ────────────────────────────►│
      │◄── 200 holdRef ───── │                     │                     │
      │                      │                     │                     │
      │   [Within 120s]       │                     │                     │
      │                      │                     │                     │
      │── POST /bookings/ ───►│                     │                     │
      │    confirm            │── GET hold key ─────►│                     │
      │   userId:"user_001"   │◄── "user_001" ──────│                     │
      │   email:"alice@..."   │── SELECT FOR UPDATE ────────────────────►│
      │   baggageWeight:20    │◄── seat row (locked) ────────────────────│
      │   paymentProcessed:   │── INSERT booking ───────────────────────►│
      │    true               │── UPDATE seat=CONFIRMED ────────────────►│
      │                      │◄── commit ───────────────────────────────│
      │                      │── DEL seat_hold:1:1A ►│                     │
      │◄── 201 PNR-7EBCAA────│                     │                     │
      │                      │                     │                     │
```

**Key guarantees:**
- Redis SETNX ensures at most one user holds a seat at a time
- `SELECT FOR UPDATE` ensures at most one booking is created per seat

---

## 2. Seat Expiry Flow

Triggered automatically when a passenger holds a seat but does not confirm within 120 seconds.

```
  Redis TTL (Primary Path)                    DB Scheduler (Safety Net)
  ─────────────────────────                   ─────────────────────────

  t=0s   SETNX seat_hold:1:1A                 [Running every 60s]
         (TTL = 120s)                          │
         DB: seat status → HELD                │
                                               │
  t=120s Redis key auto-expires                │
         (no app involvement)                  │
                                               │
  t=125s                                       │── SELECT seats WHERE
         ◄──── Redis key is gone ─────────     │    status='HELD' AND
                                               │    updated_at < NOW()-125s
                                               │◄── [seat 1A found]
                                               │── UPDATE status='AVAILABLE'
                                               │── WaitlistService.popNextUser(1)
                                               │◄── "user_050"
                                               │── [notify / hold for user_050]
```

**Outcome:** Seat returns to `AVAILABLE` within 125 seconds guaranteed. Waitlist is checked immediately upon release.

---

## 3. Cancellation Flow

> **Note:** Full cancellation is a v1.1 feature. The flow below describes the intended design.

```
  Passenger                 API                  Redis              PostgreSQL
      │                      │                     │                     │
      │── DELETE /bookings/ ►│                     │                     │
      │     {pnr}             │── findByPNR ─────────────────────────────►│
      │                      │◄── Booking (CONFIRMED) ──────────────────│
      │                      │── BEGIN TRANSACTION ────────────────────►│
      │                      │── UPDATE booking=CANCELLED ─────────────►│
      │                      │── UPDATE seat=AVAILABLE ────────────────►│
      │                      │── COMMIT ───────────────────────────────►│
      │                      │── WaitlistService.popNextUser(flightId) ─►Redis
      │                      │◄── nextUserId ───────────────────────────│
      │                      │── [trigger hold for nextUserId]           │
      │◄── 200 cancelled ────│                     │                     │
      │                      │                     │                     │
```

**Invariants:**
- `booking=CANCELLED` and `seat=AVAILABLE` are updated atomically in one transaction
- Waitlist is checked immediately on cancellation (same as expiry flow)

---

## 4. Waitlist Flow

A passenger joins the waitlist when all seats are confirmed. They are automatically promoted when a seat is released.

```
  Passenger A           Passenger B (waitlisted)         System
      │                         │                           │
      │                         │── POST /waitlist/join ───►│
      │                         │   flightId:1              │── ZADD waitlist:1
      │                         │   userId:"user_050"       │    score=timestamp
      │                         │◄── 202 position:1 ────────│
      │                         │                           │
      │  [Passenger A's hold expires OR cancels]            │
      │                         │                           │
      │                         │                 CleanupScheduler (t=60s)
      │                         │                           │── SELECT zombie seats
      │                         │                           │◄── seat 1A
      │                         │                           │── UPDATE seat=AVAILABLE
      │                         │                           │── ZPOPMIN waitlist:1
      │                         │                           │◄── "user_050"
      │                         │                           │
      │                         │                     [v1.1: Push notification]
      │                         │◄── "Your seat is ready!" ─│
      │                         │                           │
      │                         │── POST /seats/hold ───────►│
      │                         │   seatNumber:"1A"          │
      │                         │◄── 200 holdRef ────────────│
      │                         │                           │
```

**FIFO guarantee:** Redis Sorted Set scores are Unix timestamps. `ZPOPMIN` always returns the earliest joiner.

**Duplicate guard:** `addIfAbsent` (atomic `ZADD NX`) is used — if the user already exists in the set, the operation returns false/400. This eliminates the TOCTOU (Time-of-Check to Time-of-Use) window.

---

## 5. Baggage + Payment Flow

Integrated into the confirmation flow. Executed synchronously before DB operations.

```
  Passenger              FlightController         BaggageService
      │                         │                       │
      │── POST /bookings/ ──────►│                       │
      │    confirm                │── calculateFee(40kg) ►│
      │    baggageWeight: 40      │                       │── max(0, 40-25) × $15
      │    paymentProcessed:      │                       │── = $225.00
      │     false                 │◄── $225.00 ───────────│
      │                          │                       │
      │                   [fee > 0 AND paymentProcessed=false]
      │◄── 402 Payment Required ─│                       │
      │    {fee: 225.00,          │                       │
      │     currency: "USD",      │                       │
      │     hint: "Retry with     │                       │
      │     paymentProcessed:true"│                       │
      │     after paying}         │                       │
      │                          │                       │
      │  [User pays externally]   │                       │
      │                          │                       │
      │── POST /bookings/ ──────►│                       │
      │    confirm                │── calculateFee(40kg) ►│
      │    baggageWeight: 40      │◄── $225.00 ───────────│
      │    paymentProcessed:      │                       │
      │     true           [fee > 0 BUT paymentProcessed=true]
      │                          │── SeatService.confirmBooking()
      │◄── 201 PNR-XXXX ─────────│
      │                          │
```

**Weight Decision Matrix:**

| Declared Weight | Fee | paymentProcessed | Result |
|----------------|-----|-----------------|--------|
| ≤ 25 kg | $0 | any | ✅ Proceed |
| > 25 kg | > $0 | `false` | ❌ 402 — fee shown |
| > 25 kg | > $0 | `true` | ✅ Proceed |
| 0 kg | $0 | any | ✅ Proceed (no baggage) |

---

## 6. Abuse Detection Flow

Rate limiting protects against bots hammering the hold/confirm endpoints.

```
  Suspicious Client               RateLimitingConfig (Bucket4j)
      │                                       │
      │── POST /seats/hold (request 1) ──────►│── tryConsume(1 token)
      │◄── 200 OK ───────────────────────────│   [50 tokens remaining]
      │                                       │
      │── POST /seats/hold (request 2) ──────►│── tryConsume(1 token)
      │◄── 200 OK ───────────────────────────│   [49 tokens remaining]
      │      ...                              │      ...
      │                                       │
      │── POST /seats/hold (request 51) ─────►│── tryConsume(1 token)
      │                                       │   [0 tokens, REJECTED]
      │◄── 429 Too Many Requests ─────────────│
      │    {Retry-After: 1s}                  │
      │                                       │
      │   [Waits 1 second — 25 tokens refill] │
      │                                       │
      │── POST /seats/hold (request 52) ─────►│── tryConsume(1 token)
      │◄── 200 OK ───────────────────────────│   [24 tokens remaining]
      │                                       │
```

**Token Bucket Parameters:**

| Parameter | Value | Meaning |
|-----------|-------|---------|
| Capacity | 50 tokens | Max burst size |
| Refill rate | 25 tokens/sec | Sustained throughput allowed |
| Scope | Per user/IP | Each client has independent bucket |
| Persistence | Redis (v1.1) | Distributed enforcement across nodes |

**Audit logging:** Every 429 response triggers a WARN log entry with `userId`, `endpoint`, and timestamp for security review.
