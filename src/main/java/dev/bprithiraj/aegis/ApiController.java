package dev.bprithiraj.aegis;
import dev.bprithiraj.aegis.Models.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final ReservationService reservations;
    private final OutboxWorker worker;
    private final boolean demoControls;
    public ApiController(ReservationService reservations, OutboxWorker worker,
            @Value("${aegis.demo-controls:false}") boolean demoControls) {
        this.reservations = reservations; this.worker = worker; this.demoControls = demoControls;
    }
    @PostMapping("/reservations")
    public ResponseEntity<CommandResult> reserve(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ReserveRequest request) {
        CommandResult result = reservations.reserve(key, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }
    @GetMapping("/reservations/{id}")
    public Reservation reservation(@PathVariable UUID id) { return reservations.get(id); }
    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>(reservations.snapshot());
        snapshot.put("worker", worker.state());
        snapshot.put("demoControls", demoControls);
        return snapshot;
    }
    @PostMapping("/worker/pause")
    public Map<String, Object> pause(@RequestBody PauseRequest request) {
        requireDemo(); worker.pause(request.paused()); return worker.state();
    }
    @PostMapping("/worker/step")
    public Map<String, Boolean> step() { requireDemo(); return Map.of("processed", worker.processNext()); }
    @PostMapping("/worker/fault")
    public Map<String, Object> fault(@RequestBody FaultRequest request) {
        requireDemo(); worker.inject(request.stage(), request.count()); return worker.state();
    }
    @PostMapping("/events/{id}/redeliver")
    public Map<String, Boolean> redeliver(@PathVariable UUID id) {
        requireDemo(); worker.redeliver(id); return Map.of("queued", true);
    }
    private void requireDemo() {
        if (!demoControls) throw new ApiException(HttpStatus.NOT_FOUND, "Demo controls are disabled.");
    }
    record PauseRequest(boolean paused) {}
    record FaultRequest(String stage, int count) {}
}
