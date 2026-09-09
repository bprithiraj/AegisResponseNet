package dev.bprithiraj.aegis;
import dev.bprithiraj.aegis.Models.Event;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final EventConsumer consumer;
    private final boolean enabled;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final long retryBaseMs;
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicInteger failBefore = new AtomicInteger();
    private final AtomicInteger failAfter = new AtomicInteger();
    public OutboxWorker(JdbcTemplate db, TransactionTemplate tx, EventConsumer consumer,
            @Value("${aegis.worker.enabled:true}") boolean enabled,
            @Value("${aegis.worker.lease-seconds:30}") int leaseSeconds,
            @Value("${aegis.worker.max-attempts:8}") int maxAttempts,
            @Value("${aegis.worker.retry-base-ms:1000}") long retryBaseMs) {
        this.db = db; this.tx = tx; this.consumer = consumer; this.enabled = enabled;
        if (leaseSeconds < 1 || maxAttempts < 1 || retryBaseMs < 0 || retryBaseMs > 60000) throw new IllegalArgumentException("Invalid worker settings.");
        this.leaseSeconds = leaseSeconds; this.maxAttempts = maxAttempts; this.retryBaseMs = retryBaseMs;
    }
    @Scheduled(fixedDelayString = "${aegis.worker.poll-ms:500}")
    public void scheduledPoll() {
        if (!enabled || paused.get()) return;
        try { for (int i = 0; i < 16 && processNext(); i++) { /* bounded batch */ } }
        catch (RuntimeException failure) { log.warn("Outbox poll unavailable: {}", failure.getClass().getSimpleName()); }
    }
    public Optional<Event> claim() {
        return tx.execute(ignored -> {
            UUID owner = UUID.randomUUID();
            List<Event> events = db.query("""
                WITH candidate AS (
                    SELECT id FROM outbox
                    WHERE published_at IS NULL AND dead_lettered_at IS NULL
                      AND available_at <= now() AND (lease_until IS NULL OR lease_until <= now())
                    ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE outbox o SET lease_owner = ?, lease_until = now() + (? * interval '1 second'), attempts = attempts + 1
                FROM candidate c WHERE o.id = c.id RETURNING o.*
                """, (rs, n) -> new Event(rs.getObject("id", UUID.class),
                    rs.getObject("reservation_id", UUID.class), rs.getString("event_type"),
                    rs.getInt("aggregate_version"), rs.getString("status"),
                    rs.getInt("attempts"), rs.getObject("lease_owner", UUID.class)), owner, leaseSeconds);
            return events.stream().findFirst();
        });
    }
    public boolean processNext() {
        Optional<Event> claimed = claim();
        if (claimed.isEmpty()) return false;
        Event event = claimed.get();
        try {
            trip(failBefore, "Injected failure before delivery");
            consumer.consume(event);
            trip(failAfter, "Injected failure after consumer commit");
            acknowledge(event);
        } catch (RuntimeException failure) {
            long backoff = Math.min(60_000, retryBaseMs * (1L << Math.min(event.attempts() - 1, 16)));
            db.update("""
                UPDATE outbox SET lease_owner = NULL, lease_until = NULL,
                    available_at = now() + (? * interval '1 millisecond'),
                    dead_lettered_at = CASE WHEN attempts >= ? THEN now() ELSE NULL END, last_error = ?
                WHERE id = ? AND lease_owner = ?
                """, backoff, maxAttempts, failure.getClass().getSimpleName(), event.id(), event.leaseOwner());
            log.warn("Delivery {} attempt {} failed: {}", event.id(), event.attempts(), failure.getClass().getSimpleName());
        }
        return true;
    }
    public void acknowledge(Event event) {
        db.update("""
            UPDATE outbox SET published_at = now(), lease_owner = NULL, lease_until = NULL, last_error = NULL
            WHERE id = ? AND lease_owner = ?
            """, event.id(), event.leaseOwner());
    }
    public void redeliver(UUID id) {
        int changed = db.update("""
            UPDATE outbox SET published_at = NULL, dead_lettered_at = NULL, available_at = now(),
                lease_owner = NULL, lease_until = NULL, attempts = 0, last_error = NULL
            WHERE id = ? AND (lease_until IS NULL OR lease_until <= now())
            """, id);
        if (changed == 0) throw new ApiException(HttpStatus.CONFLICT, "Event missing or currently leased.");
    }
    public void pause(boolean value) { paused.set(value); }
    public void inject(String stage, int count) {
        if (count < 0 || count > 20) throw new ApiException(HttpStatus.BAD_REQUEST, "Failure count must be 0-20.");
        if ("before-consume".equals(stage)) failBefore.set(count);
        else if ("after-consume".equals(stage)) failAfter.set(count);
        else throw new ApiException(HttpStatus.BAD_REQUEST, "Stage must be before-consume or after-consume.");
    }
    public Map<String, Object> state() {
        return Map.of("enabled", enabled, "paused", paused.get(), "failBefore", failBefore.get(), "failAfter", failAfter.get());
    }
    private static void trip(AtomicInteger remaining, String message) {
        int prior = remaining.getAndUpdate(value -> Math.max(0, value - 1));
        if (prior > 0) throw new IllegalStateException(message);
    }
}
