package dev.bprithiraj.aegis;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
public final class Models {
    private Models() {}
    public record ReserveRequest(@NotBlank @Pattern(regexp = "[A-Z0-9-]{1,64}") String sku,
            @Min(1) @Max(1000) int quantity, boolean simulateFailure) {}
    public record Reservation(UUID id, String sku, int quantity, String status, int version,
            boolean simulateFailure, Instant createdAt, Instant updatedAt) {}
    public record CommandResult(Reservation reservation, boolean replayed) {}
    public record Event(UUID id, UUID reservationId, String eventType, int version, String status,
            int attempts, UUID leaseOwner) {}
}
