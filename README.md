# Aegis ResponseNet

A working reservation-recovery service for synthetic emergency-response equipment. It demonstrates what happens when a command is repeated, fulfillment fails, or a worker dies between a database commit and delivery acknowledgement.

**Java 21 · Spring Boot · PostgreSQL · Flyway · JDBC**

Aegis v1 implements a complete, deliberately small workflow: reserve equipment, fulfill or compensate it, and converge a durable read projection. The browser console operates the real API and database. It is separate from the lightweight portfolio illustration.

## Run locally

Install Docker with Compose, then:

~~~sh
cp .env.example .env
# Replace AEGIS_API_KEY in .env with your own random local key.
docker compose up --build
~~~

Open <http://localhost:8080>, enter the configured API key, and create a reservation. Database files live in the named Compose volume and survive container replacement. Demo controls are enabled in the example environment; disable them when sharing a hosted instance.

### Java and an existing PostgreSQL server

Java 21 and Maven 3.9+ are required. Use a dedicated PostgreSQL 17 database.

~~~sh
export DATABASE_URL=jdbc:postgresql://localhost:5432/aegis
export DATABASE_USERNAME=aegis
export DATABASE_PASSWORD=your-local-password
export AEGIS_DEMO_CONTROLS=true
mvn spring-boot:run
~~~

PowerShell:

~~~powershell
$env:DATABASE_URL = 'jdbc:postgresql://localhost:5432/aegis'
$env:DATABASE_USERNAME = 'aegis'
$env:DATABASE_PASSWORD = 'your-local-password'
$env:AEGIS_DEMO_CONTROLS = 'true'
mvn spring-boot:run
~~~

Flyway creates the schema and seeds 20 field kits and 10 radios on first start. Normal startup never resets existing data. The local server binds only to loopback. A non-loopback bind requires an API key.

The checked-in .mvn settings use HTTPS Maven Central and isolate this personal project from machine-specific corporate mirrors. They do not modify global Maven configuration.

## Try the failure paths

1. **Duplicate command:** create a reservation and submit the same form again. The API returns the original reservation without reserving inventory again. Change its quantity but keep the same key to receive HTTP 409.
2. **Compensation:** use a new key and enable simulated fulfillment failure. Watch RESERVED become COMPENSATION_PENDING, then COMPENSATED. Inventory returns exactly once.
3. **Commit-before-ack failure:** pause the worker, create a reservation, arm "After commit, before acknowledgement", and deliver one event. The state transition persists while delivery retries. Resume the worker and observe convergence.
4. **Duplicate event:** replay an acknowledged event. The durable inbox prevents a second business effect.
5. **Real process restart:** run the restart smoke below. It submits a command with the worker disabled, kills Java, starts a new JVM on the same database, and verifies compensation, projection convergence, and command deduplication.

No external emergency service is contacted. "Simulate fulfillment failure" selects a deterministic synthetic fulfillment outcome; the persistence and retry behavior are real.

## Why the failure windows are safe

~~~mermaid
flowchart LR
    A[HTTP command] --> B[Transaction 1: key + inventory + reservation + outbox]
    B --> C[Dispatcher claims a durable lease]
    C --> D[Transaction 2: inbox + business effect + next event + projection]
    D --> E[Transaction 3: acknowledge delivery]
    E --> C
~~~

- A unique idempotency key is bound to a SHA-256 fingerprint of all command fields. PostgreSQL resolves concurrent key insertion; a changed payload gets a conflict.
- Inventory uses one conditional UPDATE with an available-quantity guard. Concurrent requests cannot oversell.
- Reservation and outbox creation share a transaction. A process death after commit leaves a deliverable event.
- Workers claim events using FOR UPDATE SKIP LOCKED and a durable lease. Another worker can reclaim an abandoned lease.
- The consumer inbox and every database effect commit together. If acknowledgement is lost, another delivery sees the inbox entry and safely skips the effect.
- Compensation restores inventory only when the reservation is COMPENSATION_PENDING. Its event and terminal state commit with the restoration.
- Read projections accept only higher aggregate versions. They converge asynchronously and never move backward on duplicate delivery.
- Exceptions retry with exponential backoff, capped at 60 seconds. Repeated handler failures enter the dead-letter state after eight attempts by default; demo controls can requeue them.

This is **at-least-once delivery with idempotent effects inside PostgreSQL**, not an exactly-once claim across arbitrary external services.

## API

All /api routes require X-API-Key when a key is configured.

| Method | Route | Behavior |
| --- | --- | --- |
| POST | /api/reservations | Create or replay a reservation; requires Idempotency-Key |
| GET | /api/reservations/{id} | Read durable write state |
| GET | /api/snapshot | Inventory, recent reservations, projections, events and backlog counts |
| POST | /api/worker/pause | Pause/resume this process; body: {"paused":true} |
| POST | /api/worker/step | Attempt one eligible delivery |
| POST | /api/worker/fault | Arm a transient failure; body: {"stage":"after-consume","count":1} |
| POST | /api/events/{id}/redeliver | Requeue an unleased event; inbox deduplication remains |
| GET | /actuator/health | Storage-aware application health, without internal details |

Worker and redelivery routes return 404 unless AEGIS_DEMO_CONTROLS=true.

~~~sh
curl -X POST http://localhost:8080/api/reservations   -H 'Content-Type: application/json'   -H 'X-API-Key: your-key'   -H 'Idempotency-Key: incident-1042'   -d '{"sku":"FIELD-KIT","quantity":2,"simulateFailure":true}'
~~~

A new command returns 201, a valid replay returns 200, a reused key with different input or insufficient inventory returns 409, malformed input returns 400, and storage unavailability returns 503. Retry transient failures with the same key.

## Verify

Fast validation:

~~~sh
mvn test
~~~

The full integration suite requires a **dedicated disposable database**. It truncates Aegis workflow tables and resets seeded inventory between cases. Never point it at an instance whose reservations you need to retain.

~~~sh
export AEGIS_INTEGRATION_TESTS=true
export DATABASE_URL=jdbc:postgresql://localhost:5432/aegis_test
export DATABASE_USERNAME=aegis
export DATABASE_PASSWORD=your-test-password
mvn verify
python3 scripts/restart_smoke.py
~~~

In PowerShell set the same values with $env:NAME = 'value' and run python scripts/restart_smoke.py.

The tests cover concurrent duplicate commands, oversell prevention, rollback of rejected commands, worker replacement, expired lease recovery after consumer commit, compensation retries, duplicate delivery, dead letters, competing worker claims, monotonic projections, and API-key enforcement. The smoke script additionally terminates and restarts the real packaged Java process.

GitHub Actions provisions PostgreSQL and runs both suites. It uploads Surefire/Failsafe reports and the restart result. A skipped integration suite is not evidence of PostgreSQL durability; enable AEGIS_INTEGRATION_TESTS for that evidence.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| DATABASE_URL | jdbc:postgresql://localhost:5432/aegis | JDBC URL |
| DATABASE_USERNAME | aegis | Database role |
| DATABASE_PASSWORD | aegis-local-only | Local example password; replace it |
| PORT | 8080 | HTTP port |
| AEGIS_BIND_ADDRESS | 127.0.0.1 | Bind interface |
| AEGIS_API_KEY | empty | Shared API key; required for non-loopback binding |
| AEGIS_WORKER_ENABLED | true | Automatic polling |
| AEGIS_WORKER_POLL_MS | 500 | Delay between batches |
| AEGIS_LEASE_SECONDS | 30 | Reclaim timeout for a disappeared worker |
| AEGIS_MAX_ATTEMPTS | 8 | Attempts before dead-lettering a handler failure |
| AEGIS_RETRY_BASE_MS | 1000 | Initial retry delay |
| AEGIS_DEMO_CONTROLS | false | Enable pause, step, injection and replay |

## Scope and limitations

- The dispatcher and consumer use one PostgreSQL database. There is no Kafka deployment or cross-database transaction. A broker adapter would preserve the outbox/inbox contracts, then need separate broker-failure testing.
- Fulfillment is a synthetic local state transition. Calling a real supplier requires its own idempotency contract and reconciliation workflow.
- Authentication is a shared demo key, not user identity, RBAC, or tenant isolation. Put a hosted instance behind TLS and an identity-aware gateway; keep demo controls disabled.
- The console shows the latest 100 reservations/events. There is no retention policy, administrative inventory editor, pagination API, or general emergency dispatch integration.
- Leases permit a delivery to overlap a very slow prior worker. Inbox uniqueness and reservation locking protect database effects; a future external effect needs additional fencing.
- Local unit/integration correctness is not a load-test result. No throughput or latency claim is made.

See [VERIFICATION.md](VERIFICATION.md) for the actual release checks and [the PostgreSQL locking reference](https://www.postgresql.org/docs/current/sql-select.html) for the queue-claim primitive. Spring's [Java compatibility requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html) cover the selected runtime.

## License

MIT. All example incidents, equipment and outcomes are synthetic.
