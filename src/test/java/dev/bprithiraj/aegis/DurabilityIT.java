package dev.bprithiraj.aegis;
import static org.assertj.core.api.Assertions.*;
import dev.bprithiraj.aegis.Models.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"aegis.worker.enabled=false", "aegis.worker.retry-base-ms=0"})
@EnabledIfEnvironmentVariable(named = "AEGIS_INTEGRATION_TESTS", matches = "true")
class DurabilityIT {
    @Autowired ReservationService reservations;
    @Autowired EventConsumer consumer;
    @Autowired OutboxWorker worker;
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;

    @BeforeEach void reset() {
        db.execute("TRUNCATE consumer_inbox, reservation_projection, outbox, command_record, reservation CASCADE");
        db.update("UPDATE inventory SET available = total");
        worker.inject("before-consume", 0); worker.inject("after-consume", 0);
    }
    @Test void replayIsDurableAndPayloadBound() {
        ReserveRequest request = new ReserveRequest("FIELD-KIT", 2, false);
        CommandResult original = reservations.reserve("stable-key", request);
        ReservationService restartedService = new ReservationService(db, tx);
        CommandResult replay = restartedService.reserve("stable-key", request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.reservation().id()).isEqualTo(original.reservation().id());
        assertThat(available()).isEqualTo(18);
        assertThatThrownBy(() -> reservations.reserve("stable-key", new ReserveRequest("FIELD-KIT", 3, false)))
            .isInstanceOf(ApiException.class);
        assertThat(count("command_record")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }
    @Test void concurrentDuplicateCommandsReserveExactlyOnce() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var gate = new CountDownLatch(1);
            List<Future<CommandResult>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(pool.submit(() -> {
                gate.await(); return reservations.reserve("same-key", new ReserveRequest("FIELD-KIT", 2, false));
            }));
            gate.countDown();
            Set<UUID> ids = new HashSet<>();
            for (var call : calls) ids.add(call.get(15, TimeUnit.SECONDS).reservation().id());
            assertThat(ids).hasSize(1);
        }
        assertThat(available()).isEqualTo(18);
        assertThat(count("reservation")).isEqualTo(1);
    }
    @Test void concurrentReservationsNeverOversellAndRejectedCommandsRollBack() throws Exception {
        try (var pool = Executors.newFixedThreadPool(10)) {
            List<Future<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                String key = "distinct-" + i;
                calls.add(pool.submit(() -> {
                    try { reservations.reserve(key, new ReserveRequest("FIELD-KIT", 1, false)); return true; }
                    catch (ApiException conflict) { return false; }
                }));
            }
            int successes = 0;
            for (var call : calls) if (call.get(20, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(20);
        }
        assertThat(available()).isZero();
        assertThat(count("reservation")).isEqualTo(20);
        assertThat(count("command_record")).isEqualTo(20);
    }
    @Test void committedOutboxSurvivesWorkerReplacementAndProjectionConverges() {
        var reservation = reservations.reserve("crash-before-publish", new ReserveRequest("FIELD-KIT", 2, false)).reservation();
        assertThat(count("reservation_projection")).isZero();
        var restarted = new OutboxWorker(db, tx, new EventConsumer(db, tx, new ReservationService(db, tx)), false, 30, 8, 0);
        drain(restarted);
        assertThat(reservations.get(reservation.id()).status()).isEqualTo("COMPLETED");
        assertThat(projectionStatus(reservation.id())).isEqualTo("COMPLETED");
        assertThat(count("consumer_inbox")).isEqualTo(2);
    }
    @Test void crashAfterConsumerCommitBeforeAcknowledgementDoesNotRepeatEffects() {
        var reservation = reservations.reserve("crash-after-consume", new ReserveRequest("FIELD-KIT", 2, false)).reservation();
        Event claimed = worker.claim().orElseThrow();
        assertThat(consumer.consume(claimed)).isTrue();
        // Simulate process death: no acknowledgement; persisted lease expires.
        db.update("UPDATE outbox SET lease_until = now() - interval '1 second' WHERE id = ?", claimed.id());
        drain(new OutboxWorker(db, tx, new EventConsumer(db, tx, reservations), false, 30, 8, 0));
        assertThat(available()).isEqualTo(18);
        assertThat(reservations.get(reservation.id()).version()).isEqualTo(2);
        assertThat(count("outbox")).isEqualTo(2);
        assertThat(count("consumer_inbox")).isEqualTo(2);
    }
    @Test void compensationRetriesAndDuplicateDeliveryRestoreInventoryOnce() {
        var reservation = reservations.reserve("compensate", new ReserveRequest("FIELD-KIT", 3, true)).reservation();
        worker.processNext();
        assertThat(reservations.get(reservation.id()).status()).isEqualTo("COMPENSATION_PENDING");
        worker.inject("after-consume", 1);
        worker.processNext();
        drain(worker);
        assertThat(available()).isEqualTo(20);
        assertThat(projectionStatus(reservation.id())).isEqualTo("COMPENSATED");
        UUID compensation = db.queryForObject("SELECT id FROM outbox WHERE event_type = 'COMPENSATION_REQUESTED'", UUID.class);
        worker.redeliver(compensation); drain(worker);
        assertThat(available()).isEqualTo(20);
        assertThat(reservations.get(reservation.id()).version()).isEqualTo(3);
        assertThat(count("consumer_inbox")).isEqualTo(3);
    }
    @Test void poisonedDeliveryDeadLettersAndCanBeRecovered() {
        reservations.reserve("poison", new ReserveRequest("FIELD-KIT", 1, false));
        worker.inject("before-consume", 8); drain(worker);
        assertThat(db.queryForObject("SELECT count(*) FROM outbox WHERE dead_lettered_at IS NOT NULL", Integer.class)).isEqualTo(1);
        assertThat(count("consumer_inbox")).isZero();
        UUID id = db.queryForObject("SELECT id FROM outbox", UUID.class);
        worker.redeliver(id); drain(worker);
        assertThat(count("consumer_inbox")).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Integer.class)).isZero();
    }
    @Test void competingWorkersCannotClaimTheSameActiveLease() throws Exception {
        for (int i = 0; i < 10; i++) reservations.reserve("claim-" + i, new ReserveRequest("FIELD-KIT", 1, false));
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Optional<Event>>> claims = new ArrayList<>();
            for (int i = 0; i < 12; i++) claims.add(pool.submit(worker::claim));
            Set<UUID> ids = new HashSet<>();
            int claimed = 0;
            for (var claim : claims) {
                Optional<Event> event = claim.get(15, TimeUnit.SECONDS);
                if (event.isPresent()) { claimed++; ids.add(event.get().id()); }
            }
            assertThat(claimed).isEqualTo(10); assertThat(ids).hasSize(10);
        }
    }
    @Test void projectionNeverRegressesWhenOldEventIsDeliveredAgain() {
        var reservation = reservations.reserve("monotonic", new ReserveRequest("FIELD-KIT", 1, false)).reservation();
        drain(worker);
        UUID first = db.queryForObject("SELECT id FROM outbox WHERE aggregate_version = 1", UUID.class);
        worker.redeliver(first); drain(worker);
        assertThat(projectionStatus(reservation.id())).isEqualTo("COMPLETED");
        assertThat(db.queryForObject("SELECT version FROM reservation_projection", Integer.class)).isEqualTo(2);
    }
    private void drain(OutboxWorker target) {
        for (int i = 0; i < 100; i++) if (!target.processNext()) return;
        fail("Worker did not drain in 100 steps");
    }
    private int available() { return db.queryForObject("SELECT available FROM inventory WHERE sku = 'FIELD-KIT'", Integer.class); }
    private int count(String table) { return db.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private String projectionStatus(UUID id) {
        return db.queryForObject("SELECT status FROM reservation_projection WHERE reservation_id = ?", String.class, id);
    }
}
