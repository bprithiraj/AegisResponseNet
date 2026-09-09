"""Verify real JVM restart durability and HTTP guards against a disposable database."""
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
PORT = int(os.environ.get("AEGIS_SMOKE_PORT", "18080"))
BASE = f"http://127.0.0.1:{PORT}"
KEY = "local-restart-smoke-" + str(uuid.uuid4())
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(path, payload=None, headers=None):
    merged = {"X-API-Key": KEY, "Content-Type": "application/json", **(headers or {})}
    req = urllib.request.Request(BASE + path, data=None if payload is None else json.dumps(payload).encode(), headers=merged)
    with OPENER.open(req, timeout=5) as response:
        return json.load(response)


def expect_status(status, path, payload=None, headers=None):
    try:
        request(path, payload, headers)
    except urllib.error.HTTPError as error:
        assert error.code == status, (error.code, status)
    else:
        raise AssertionError(f"Expected HTTP {status}")


def start(log):
    env = {**os.environ, "PORT": str(PORT), "AEGIS_API_KEY": KEY,
           "AEGIS_BIND_ADDRESS": "127.0.0.1", "AEGIS_WORKER_ENABLED": "true",
           "AEGIS_DEMO_CONTROLS": "true", "AEGIS_LEASE_SECONDS": "2"}
    java = Path(env["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java") if env.get("JAVA_HOME") else "java"
    process = subprocess.Popen([str(java), "-jar", str(ROOT / "target/aegis-responsenet-1.0.0.jar")],
                               env=env, stdout=log, stderr=subprocess.STDOUT,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    deadline = time.monotonic() + int(os.environ.get("AEGIS_STARTUP_TIMEOUT_SECONDS", "180"))
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Java exited during startup; inspect target/restart-smoke.log")
        try:
            request("/actuator/health")
            return process
        except (OSError, urllib.error.URLError):
            time.sleep(0.5)
    process.kill()
    process.wait(timeout=10)
    raise TimeoutError("Application did not start")


def main():
    target = ROOT / "target"
    target.mkdir(exist_ok=True)
    process = None
    with (target / "restart-smoke.log").open("w", encoding="utf-8") as log:
        try:
            process = start(log)
            deadline = time.monotonic() + 60
            while request("/api/snapshot")["metrics"]["pending_events"]:
                if time.monotonic() > deadline:
                    raise TimeoutError("Earlier pending events did not drain")
                time.sleep(0.2)
            request("/api/worker/pause", {"paused": True})
            for path in ("/api/snapshot", "/api;review/snapshot", "/%61pi/snapshot", "/a%70i/snapshot"):
                expect_status(401, path, headers={"X-API-Key": "incorrect"})
            expect_status(400, "/api/reservations",
                          {"sku": "RADIO", "quantity": 0, "simulateFailure": False},
                          {"Idempotency-Key": "invalid-" + str(uuid.uuid4())})
            key = "restart-" + str(uuid.uuid4())
            command = {"sku": "RADIO", "quantity": 1, "simulateFailure": True}
            first = request("/api/reservations", command, {"Idempotency-Key": key})
            reservation_id = first["reservation"]["id"]
            expect_status(409, "/api/reservations", {**command, "quantity": 2}, {"Idempotency-Key": key})
            assert first["reservation"]["status"] == "RESERVED"
            queued_key = "queued-" + str(uuid.uuid4())
            queued = request("/api/reservations", command, {"Idempotency-Key": queued_key})
            queued_id = queued["reservation"]["id"]
            request("/api/worker/fault", {"stage": "after-consume", "count": 1})
            assert request("/api/worker/step", {})["processed"]
            assert request("/api/reservations/" + reservation_id)["status"] == "COMPENSATION_PENDING"
            assert request("/api/reservations/" + queued_id)["status"] == "RESERVED"
            before = request("/api/snapshot")
            assert not any(p["reservation_id"] == queued_id for p in before["projections"])
            # First command has a consumer commit without ack; second only a producer commit.
            process.kill()
            process.wait(timeout=15)
            process = start(log)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                state = request("/api/reservations/" + reservation_id)
                queued_state = request("/api/reservations/" + queued_id)
                snapshot = request("/api/snapshot")
                projected = {p["reservation_id"]: p["status"] for p in snapshot["projections"]}
                if (state["status"] == queued_state["status"] == "COMPENSATED"
                        and projected.get(reservation_id) == projected.get(queued_id) == "COMPENSATED"):
                    break
                time.sleep(0.1)
            else:
                raise AssertionError("Both reservations and projections did not converge after restart")
            replay = request("/api/reservations", command, {"Idempotency-Key": key})
            assert replay["replayed"] and replay["reservation"]["id"] == reservation_id
            result = {"passed": True,
                      "scenario": "producer commit and consumer commit-before-ack -> kill Java -> restart -> compensate -> project -> replay",
                      "reservation_id": reservation_id, "queued_reservation_id": queued_id,
                      "write_status": state["status"], "projection_status": projected[reservation_id],
                      "http_guards": ["API key", "matrix path", "encoded paths", "input validation", "key conflict"]}
            (target / "restart-smoke.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(result, indent=2))
        finally:
            if process and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)


if __name__ == "__main__":
    main()
