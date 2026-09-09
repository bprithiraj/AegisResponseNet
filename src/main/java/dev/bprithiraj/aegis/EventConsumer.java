package dev.bprithiraj.aegis;
import dev.bprithiraj.aegis.Models.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Inbox insertion, business effects, new outbox entries and projection are one commit. */
@Service
public class EventConsumer {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ReservationService reservations;
    public EventConsumer(JdbcTemplate db, TransactionTemplate tx, ReservationService reservations) {
        this.db = db; this.tx = tx; this.reservations = reservations;
    }
    public boolean consume(Event event) {
        return Boolean.TRUE.equals(tx.execute(ignored -> {
            int firstDelivery = db.update("INSERT INTO consumer_inbox(event_id) VALUES (?) ON CONFLICT DO NOTHING", event.id());
            if (firstDelivery == 0) return false;
            List<Reservation> rows = db.query("SELECT * FROM reservation WHERE id = ? FOR UPDATE",
                ReservationService::mapReservation, event.reservationId());
            Reservation reservation = rows.getFirst();
            if (event.eventType().equals("RESERVATION_CREATED") && reservation.status().equals("RESERVED")) {
                String next = reservation.simulateFailure() ? "COMPENSATION_PENDING" : "COMPLETED";
                transition(reservation, next);
                reservations.enqueue(reservation.id(),
                    reservation.simulateFailure() ? "COMPENSATION_REQUESTED" : "RESERVATION_COMPLETED",
                    reservation.version() + 1, next);
            } else if (event.eventType().equals("COMPENSATION_REQUESTED") && reservation.status().equals("COMPENSATION_PENDING")) {
                db.update("UPDATE inventory SET available = available + ? WHERE sku = ?", reservation.quantity(), reservation.sku());
                transition(reservation, "COMPENSATED");
                reservations.enqueue(reservation.id(), "RESERVATION_COMPENSATED", reservation.version() + 1, "COMPENSATED");
            }
            db.update("""
                INSERT INTO reservation_projection(reservation_id, sku, quantity, status, version)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(reservation_id) DO UPDATE
                SET status = EXCLUDED.status, version = EXCLUDED.version, updated_at = now()
                WHERE reservation_projection.version < EXCLUDED.version
                """, reservation.id(), reservation.sku(), reservation.quantity(), event.status(), event.version());
            return true;
        }));
    }
    private void transition(Reservation reservation, String status) {
        db.update("UPDATE reservation SET status = ?, version = version + 1, updated_at = now() WHERE id = ?",
            status, reservation.id());
    }
}
