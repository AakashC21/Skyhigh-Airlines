# SkyHigh Core — Docker Compose Design

**Version:** 1.0.0  
**Last Updated:** 2026-02-20

---

## Stack at a Glance

```
┌────────────────────────────────────────────────────────────┐
│                   docker-compose.yml                       │
│                                                            │
│  ┌──────────┐   ┌──────────┐   ┌──────────────────────┐   │
│  │ postgres │   │  redis   │   │         app          │   │
│  │  :5432   │   │  :6379   │   │        :8080         │   │
│  │          │   │          │   │                      │   │
│  │ Primary  │   │ Hold TTL │   │  REST API            │   │
│  │ durable  │   │ Waitlist │   │+ CleanupScheduler    │   │
│  │ store    │   │ Rate Lim │   │  (embedded worker)   │   │
│  └────┬─────┘   └────┬─────┘   └──────────┬───────────┘   │
│       │              │                    │               │
│       └──────────────┴────────────────────┘               │
│              single Docker bridge network                  │
└────────────────────────────────────────────────────────────┘
```

All three containers communicate over Docker's internal bridge network. Only declared ports are exposed to the host machine.

---

## Service 1 — `postgres`

### What it is
PostgreSQL 15 (Alpine variant). The single source of truth for all **durable business data**.

### What it stores
| Table | Contents |
|-------|---------|
| `flights` | Flight schedule (number, times, aircraft) |
| `seats` | Seat inventory — status (`AVAILABLE` / `HELD` / `CONFIRMED`), version, timestamps |
| `passengers` | Email, name — created on first booking |
| `bookings` | PNR reference, FK to seat + passenger + flight |

### Why PostgreSQL?
- **ACID transactions** — booking confirmation is irreversible; partial writes must never happen.
- **`SELECT FOR UPDATE` (pessimistic row lock)** — the only reliable defence against double-booking under concurrency.
- **Durable schema** — Hibernate DDL auto-generates tables from JPA entities on first startup.
- **Mature tooling** — `pg_isready`, read replicas, Flyway migrations all production-tested.

### Key config decisions

| Setting | Value | Reason |
|---------|-------|--------|
| `image` | `postgres:15-alpine` | Minimum image size (~130 MB); no extra tooling needed |
| `healthcheck` | `pg_isready` | Checks TCP + auth together — app won't start until this passes |
| `volume` | `postgres_data:/var/lib/postgresql/data` | Data survives container restarts and image rebuilds |
| `POSTGRES_DB` | `skyhigh_db` | Isolated database; avoids polluting the default `postgres` DB |
| `restart` | `unless-stopped` | Auto-recovers from crashes without manual intervention |
| `memory limit` | `512 MB` | Conservative for local dev; raise to 4–16 GB in production |

---

## Service 2 — `redis`

### What it is
Redis 7 (Alpine variant). The **ephemeral coordination layer** — fast but temporary state.

### What it stores
| Key Pattern | Type | TTL | Purpose |
|-------------|------|-----|---------|
| `seat_hold:{flightId}:{seatNumber}` | String | **120s** | Holds a seat for one user (value = userId) |
| `waitlist:{flightId}` | Sorted Set | None | FIFO queue; score = join timestamp |
| `rate_limit:{userId}` | String/Hash | Rolling | Bucket4j token bucket state |

### Why Redis?
- **`SETNX` (Set-if-Not-eXists)** is atomic — the single operation that prevents two users from holding the same seat simultaneously.
- **Native TTL** — seat holds expire _automatically_ without any app code running. The 120-second hold window is enforced by Redis itself.
- **Sorted Sets (`ZSET`)** — `ZADD` / `ZPOPMIN` give O(log N) FIFO waitlist operations with zero extra infrastructure.
- **Speed** — sub-millisecond operations vs. multiple-millisecond DB round-trips for the hot hold path.

### Key config decisions

| Setting | Value | Reason |
|---------|-------|--------|
| `--appendonly yes` | AOF persistence | Active holds survive container restarts without data loss |
| `--notify-keyspace-events Ex` | Key expiry events | Publishes expiry notifications; pre-wired for v1.1 real-time waitlist promotion |
| `--maxmemory 256mb` | Memory cap | Prevents Redis growing unbounded; old keys evicted by LRU policy |
| `--maxmemory-policy allkeys-lru` | LRU eviction | Evicts least-recently-used keys when memory is full — safe for hold/waitlist keys |
| `volume` | `redis_data:/data` | Persists AOF log so holds survive restarts |
| `healthcheck` | `redis-cli ping` | Blocks app startup until Redis is truly accepting connections |

---

## Service 3 — `app`

### What it is
The Spring Boot 3.2 / Java 21 application. Serves the REST API **and** runs the embedded background worker (`CleanupScheduler`).

### What it does
- Exposes `/api/v1/*` endpoints (flights, seats, hold, confirm, waitlist, bookings)
- Runs `CleanupScheduler` every 60 seconds (zombie hold cleanup + waitlist trigger)
- Seeds demo data on first startup (`DataInitializer`)

### Key config decisions

| Setting | Value | Reason |
|---------|-------|--------|
| `build: context: .` | Local Dockerfile | Multi-stage build: Maven compile → JRE-only runtime image |
| `depends_on: condition: service_healthy` | Both postgres + redis | Spring DataSource fails immediately if DB isn't ready; this prevents race-condition boot failures |
| `SPRING_DATA_REDIS_HOST: redis` | Docker service name | Containers resolve each other by service name on the internal network — never use `localhost` |
| `SPRING_JPA_HIBERNATE_DDL_AUTO: update` | Schema auto-update | Creates/alters tables from JPA entities on startup; use `validate` in production |
| `SPRING_JPA_OPEN_IN_VIEW: false` | Disabled | Prevents Hibernate sessions staying open across the full HTTP request lifecycle — avoids lazy-loading serialization errors |
| `JAVA_OPTS: -XX:+UseG1GC` | G1 Garbage Collector | Best latency profile for request-response Spring Boot apps |
| `restart: on-failure` | Fault recovery | Restarts only if it crashes (exit code ≠ 0), not on intentional `docker stop` |
| `healthcheck: /actuator/health` | Spring Actuator probe | Checks app, DB connection, and Redis connection all in one call |
| `memory limit: 1 GB` | JVM headroom | Spring Boot + Hibernate + connection pools need ~400 MB; 1 GB gives headroom |

---

## Why No Separate Worker Container?

The `CleanupScheduler` runs as a Spring `@Scheduled` bean **inside the `app` container**. This is the right call for v1.0 because:

| Factor | Shared Container | Separate Container |
|--------|-----------------|-------------------|
| Ops complexity | Low — one image to build and deploy | Higher — two images, two deploy targets |
| Leader election | Not needed (single instance) | Required to avoid duplicate cleanup runs |
| Resource sharing | Shares DB/Redis connection pools with app | Needs its own pools |
| Scheduler load | One DB query per minute — trivial | No justification for isolation |
| v1.0 scale | Single app instance | Overkill |

**When to split:** If the app scales to 3+ instances, the scheduler must run in exactly one of them (or use `ShedLock` / `Quartz Clustered`). At that point, extract it into a dedicated `worker` service with `replica: 1`.

---

## Why No Message Broker?

There is currently no **async event** need in v1.0. All operations are synchronous:
- Hold → Redis SETNX (sync)
- Confirm → DB transaction (sync)
- Waitlist → Redis ZPOPMIN (sync, triggered by scheduler)

A message broker (RabbitMQ / Kafka) would be added when:

| Trigger | Use Case |
|---------|---------|
| Boarding pass generation | Publish `BookingConfirmed` event → downstream PDF service |
| Push notifications | Publish `SeatAvailable` event → notification service |
| Cancellation saga | Publish `BookingCancelled` event → payment refund service |
| Audit log stream | All domain events → Kafka topic for analytics |

The `docker-compose.yml` includes a commented-out `rabbitmq` block ready to activate for v1.1.

---

## Networking

Docker Compose creates a single bridge network: `skyhigh-airlines_default`.

```
skyhigh-airlines_default (bridge)
    │
    ├── skyhigh_postgres   resolves as → postgres
    ├── skyhigh_redis      resolves as → redis
    └── skyhigh_app        resolves as → app
```

- Containers reference each other by **service name** (e.g. `postgres`, `redis`) — not by IP.
- Host machine accesses containers via declared `ports:` mappings only.
- In production, remove host port mappings for `postgres` and `redis` — they should be unreachable from outside the cluster.

---

## Volume Strategy

| Volume | Mounted At | Lifecycle | Purpose |
|--------|----------|-----------|---------|
| `postgres_data` | `/var/lib/postgresql/data` | `docker-compose down -v` to delete | All booking and seat data |
| `redis_data` | `/data` | Same | AOF log (active holds persist restarts) |

> **Warning:** Running `docker-compose down -v` permanently deletes all volumes. Use only when you want a clean slate (e.g., CI teardown).

---

## v1.1 Additions (Planned)

```yaml
# To be added in v1.1

  rabbitmq:         ← async event bus
    image: rabbitmq:3-management-alpine

  worker:           ← dedicated scheduler container
    build: .
    command: java -Dspring.profiles.active=worker -jar app.jar
    deploy:
      replicas: 1   ← exactly 1 to prevent duplicate cleanup runs
```
