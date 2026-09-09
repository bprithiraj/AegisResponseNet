package dev.bprithiraj.aegis;
import dev.bprithiraj.aegis.Models.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReservationService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public ReservationService(JdbcTemplate db, TransactionTemplate tx) { this.db = db; this.tx = tx; }
    public CommandResult reserve(String key, ReserveRequest request) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key must contain 1-128 letters, digits, dots, underscores, colons, or hyphens.");
        String hash = requestHash(request);
        return tx.execute(ignored -> {
            UUID id = UUID.randomUUID();
            int inserted = db.update("""
                INSERT INTO command_record(idempotency_key, request_hash, reservation_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, key, hash, id);
            if (inserted == 0) {
                Map<String, Object> existing = db.queryForMap(
                    "SELECT request_hash, reservation_id FROM command_record WHERE idempotency_key = ?", key);
                if (!hash.equals(existing.get("request_hash")))
                    throw new ApiException(HttpStatus.CONFLICT, "This idempotency key was used with a different request.");
                return new CommandResult(get((UUID) existing.get("reservation_id")), true);
            }
            int reserved = db.update("""
                UPDATE inventory SET available = available - ? WHERE sku = ? AND available >= ?
                """, request.quantity(), request.sku(), request.quantity());
            if (reserved == 0) throw new ApiException(HttpStatus.CONFLICT, "Unknown SKU or insufficient inventory.");
            db.update("""
                INSERT INTO reservation(id, sku, quantity, status, version, simulate_failure)
                VALUES (?, ?, ?, 'RESERVED', 1, ?)
                """, id, request.sku(), request.quantity(), request.simulateFailure());
            enqueue(id, "RESERVATION_CREATED", 1, "RESERVED");
            return new CommandResult(get(id), false);
        });
    }
    public Reservation get(UUID id) {
        List<Reservation> found = db.query("SELECT * FROM reservation WHERE id = ?",
            ReservationService::mapReservation, id);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Reservation not found.");
        return found.getFirst();
    }
    public Map<String, Object> snapshot() {
        return tx.execute(ignored -> Map.of(
            "inventory", db.queryForList("SELECT * FROM inventory ORDER BY sku"),
            "reservations", db.query("SELECT * FROM reservation ORDER BY created_at DESC LIMIT 100", ReservationService::mapReservation),
            "projections", db.queryForList("SELECT * FROM reservation_projection ORDER BY updated_at DESC LIMIT 100"),
            "events", db.queryForList("""
                SELECT id, reservation_id, event_type, aggregate_version, status, attempts,
                       created_at, available_at, lease_until, published_at, dead_lettered_at, last_error
                FROM outbox ORDER BY created_at DESC LIMIT 100
                """),
            "metrics", db.queryForMap("""
                SELECT count(*) AS total_events,
                count(*) FILTER (WHERE published_at IS NULL AND dead_lettered_at IS NULL) AS pending_events,
                count(*) FILTER (WHERE dead_lettered_at IS NOT NULL) AS dead_letters,
                (SELECT count(*) FROM consumer_inbox) AS consumed_events FROM outbox
                """)));
    }
    void enqueue(UUID reservationId, String type, int version, String status) {
        db.update("INSERT INTO outbox(id, reservation_id, event_type, aggregate_version, status) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), reservationId, type, version, status);
    }
    static Reservation mapReservation(ResultSet rs, int row) throws SQLException {
        return new Reservation(rs.getObject("id", UUID.class), rs.getString("sku"), rs.getInt("quantity"),
            rs.getString("status"), rs.getInt("version"), rs.getBoolean("simulate_failure"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    static String requestHash(ReserveRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (request.sku() + ":" + request.quantity() + ":" + request.simulateFailure()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
