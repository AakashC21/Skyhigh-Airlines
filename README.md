# SkyHigh Core

**Digital Check-In System Backend — SkyHigh Airlines**

[![Java](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.3-brightgreen)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-red)](https://redis.io/)
[![Docker](https://img.shields.io/badge/Docker-ready-blue)](https://www.docker.com/)

---

## Overview

SkyHigh Core is the backend engine powering SkyHigh Airlines' digital check-in platform. It provides:

- **Conflict-free seat selection** with 120-second time-bound holds (Redis)
- **Atomic booking confirmation** with pessimistic DB locking (PostgreSQL `SELECT FOR UPDATE`)
- **FIFO waitlist management** using Redis Sorted Sets
- **Baggage validation** with excess fee calculation
- **Abuse detection** via token-bucket rate limiting (Bucket4j)
- **Zombie hold cleanup** via a background scheduler

See [`ARCHITECTURE.md`](ARCHITECTURE.md), [`PRD.md`](PRD.md), and [`WORKFLOW_DESIGN.md`](WORKFLOW_DESIGN.md) for deeper documentation.

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java JDK | 21 | [adoptium.net](https://adoptium.net/) |
| Maven | 3.8+ | [maven.apache.org](https://maven.apache.org/) |
| Docker | 25+ | [docker.com](https://www.docker.com/) |
| Docker Compose | v2.x | Bundled with Docker Desktop |

---

## Environment Variables

The application reads configuration from `application.properties` but all values can be overridden via environment variables (Docker Compose sets these automatically).

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/skyhigh_db` | PostgreSQL connection URL |
| `SPRING_DATASOURCE_USERNAME` | `skyhigh_user` | Database username |
| `SPRING_DATASOURCE_PASSWORD` | `skyhigh_password` | Database password |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis hostname |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis port |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `update` | Schema strategy (`update` / `create-drop`) |
| `SERVER_PORT` | `8080` | HTTP server port |

---

## Running with Docker (Recommended)

The fastest way to run the entire stack — app, Postgres, and Redis — with a single command.

### 1. Start everything

```bash
docker-compose up --build -d
```

This will:
- Pull `postgres:15-alpine` and `redis:7-alpine` images
- Build the application image using the multi-stage `Dockerfile`
- Start all 3 containers with health checks
- Seed demo data (flight `SH-101` with 12 seats) on first run

### 2. Check container status

```bash
docker ps
```

Expected output:

```
CONTAINER ID   IMAGE                  PORTS                    NAMES
xxxxxxxxxxxx   skyhigh-airlines-app   0.0.0.0:8080->8080/tcp   skyhigh_app
xxxxxxxxxxxx   postgres:15-alpine     0.0.0.0:5432->5432/tcp   skyhigh_postgres
xxxxxxxxxxxx   redis:7-alpine         0.0.0.0:6379->6379/tcp   skyhigh_redis
```

### 3. Watch application logs

```bash
docker logs skyhigh_app -f
```

Look for:

```
Started SkyHighCoreApplication in XX seconds
Seeding demo data...
Demo flight SH-101 created with ID=1 and 12 seats
```

### 4. Stop everything

```bash
docker-compose down
```

To also remove volumes (wipes DB data):

```bash
docker-compose down -v
```

---

## Running Locally (Without Docker for the App)

Use this when developing and you want hot-reload.

### 1. Start only the infrastructure

```bash
docker-compose up -d postgres redis
```

### 2. Run the application

```bash
mvn spring-boot:run
```

The server starts at `http://localhost:8080`.

---

## How to Run Tests

### Unit Tests

```bash
mvn test
```

Runs:
- **`SeatServiceTest`** — Mockito-based unit tests for hold/confirm logic
- **`BookingFlowIntegrationTest`** — Concurrency test using H2 in-memory DB (no external deps required)

### Integration Tests (with real DB)

Ensure Postgres + Redis are running, then:

```bash
mvn verify
```

---

## How to Run Background Workers

The cleanup scheduler runs **automatically** as part of the application — no separate process is needed.

**Scheduler details:**
- **Job:** `CleanupScheduler.cleanupExpiredHolds()`
- **Interval:** Every 60 seconds
- **What it does:** Finds seats stuck in `HELD` state for > 125 seconds and resets them to `AVAILABLE`, then triggers waitlist assignment

To verify it is running, watch the logs:

```bash
docker logs skyhigh_app -f | grep -i "cleanup\|zombie\|scheduler"
```

---

## API Quick Reference

Base URL: `http://localhost:8080/api/v1`

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/flights` | List all flights |
| `GET` | `/flights/{id}/seats` | Get real-time seat map |
| `POST` | `/seats/hold` | Hold a seat (120s TTL) |
| `POST` | `/bookings/confirm` | Confirm booking |
| `GET` | `/bookings/{pnr}` | Look up booking by PNR |
| `POST` | `/waitlist/join` | Join flight waitlist |

Full spec: [`API-SPECIFICATION.yml`](API-SPECIFICATION.yml)

---

## Test All Endpoints

A ready-made shell script is included. Run it via Docker:

```bash
docker cp test_endpoints.sh skyhigh_app:/tmp/test.sh
docker exec skyhigh_app sh /tmp/test.sh
```

### Example responses you'll see

**Hold a seat:**
```json
{"status":"HELD","holdReference":"18f070ac-...","message":"Seat held for 120 seconds. Confirm quickly!"}
```

**Confirm booking:**
```json
{"status":"CONFIRMED","bookingReference":"PNR-7EBCAA","seatNumber":"1A"}
```

**Excess baggage — payment required:**
```json
{"error":"Excess baggage fee required","feeAmount":225.00,"currency":"USD"}
```

**Rate limit hit:**
```json
{"error":"Too many requests. Please slow down."}
```

---

## Project Structure

```
src/main/java/com/skyhigh/core/
├── SkyHighCoreApplication.java   ← Entry point
├── config/                       ← Spring beans (Jackson, Rate Limiting, Data seed)
├── controller/                   ← REST layer
├── model/                        ← JPA entities (Flight, Seat, Booking, Passenger)
├── repository/                   ← Data access (JPA repositories)
├── scheduler/                    ← Background jobs (CleanupScheduler)
└── service/                      ← Business logic (SeatService, WaitlistService, BaggageService)
```

See [`PROJECT_STRUCTURE.md`](PROJECT_STRUCTURE.md) for full breakdown.

---

## Documentation Index

| File | Description |
|------|-------------|
| [`README.md`](README.md) | This file — setup and run guide |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | System design, diagrams, trade-offs |
| [`PRD.md`](PRD.md) | Product requirements document |
| [`WORKFLOW_DESIGN.md`](WORKFLOW_DESIGN.md) | Flow diagrams for all key workflows |
| [`PROJECT_STRUCTURE.md`](PROJECT_STRUCTURE.md) | Folder and module explanations |
| [`API-SPECIFICATION.yml`](API-SPECIFICATION.yml) | OpenAPI 3.0 API specification |