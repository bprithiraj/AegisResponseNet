"use strict";
const $ = (id) => document.getElementById(id);
let snapshot;
let loading = false;
const short = (id) => String(id).slice(0, 8);
const pretty = (value) => String(value).toLowerCase().replaceAll("_", " ");
function notice(message, error = false) { $("notice").textContent = message; $("notice").className = error ? "error" : ""; }
async function api(path, method = "GET", body, extra = {}) {
  const headers = { "Content-Type": "application/json", ...extra };
  if ($("api-key").value) headers["X-API-Key"] = $("api-key").value;
  const response = await fetch("/api" + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || "Request failed (" + response.status + ")");
  return result;
}
function cell(row, text, className = "") {
  const element = document.createElement("td"); element.textContent = text; element.className = className; row.append(element); return element;
}
function render(data) {
  snapshot = data;
  $("connection").textContent = "Connected · PostgreSQL state";
  $("metrics").replaceChildren();
  const metrics = [...data.inventory.map((item) => [item.available + " / " + item.total, pretty(item.sku) + " available"]),
    [data.metrics.pending_events, "events awaiting delivery"], [data.metrics.dead_letters, "dead letters"]];
  for (const [value, label] of metrics) {
    const tile = document.createElement("div"); tile.className = "metric";
    const strong = document.createElement("strong"); strong.textContent = value;
    const span = document.createElement("span"); span.textContent = label; tile.append(strong, span); $("metrics").append(tile);
  }
  $("worker-status").textContent = !data.worker.enabled ? "Automatic polling disabled" : data.worker.paused ? "Worker paused" : "Worker running";
  if (data.worker.failBefore || data.worker.failAfter) $("worker-status").textContent += " · failure armed";
  $("pause").textContent = data.worker.paused ? "Resume worker" : "Pause worker";
  $("demo-controls").hidden = !data.demoControls; $("controls-disabled").hidden = data.demoControls;
  $("reservations").replaceChildren();
  for (const item of data.reservations) {
    const projection = data.projections.find((candidate) => candidate.reservation_id === item.id);
    const row = document.createElement("tr");
    cell(row, short(item.id)); cell(row, item.sku); cell(row, item.quantity);
    cell(row, pretty(item.status), "badge"); cell(row, projection ? pretty(projection.status) : "awaiting event", projection ? "badge" : "muted");
    $("reservations").append(row);
  }
  if (!data.reservations.length) { const row = document.createElement("tr"); cell(row, "No reservations yet. Create your first command.").colSpan = 5; $("reservations").append(row); }
  $("events").replaceChildren();
  for (const event of data.events) {
    const row = document.createElement("tr");
    cell(row, pretty(event.event_type)); cell(row, short(event.reservation_id)); cell(row, event.aggregate_version); cell(row, event.attempts);
    const delivery = event.dead_lettered_at ? "dead letter" : event.published_at ? "acknowledged" : event.lease_until ? "leased" : "pending";
    cell(row, delivery, event.dead_lettered_at ? "badge warn" : "badge");
    const action = cell(row, "");
    if (data.demoControls && !event.lease_until) {
      const button = document.createElement("button"); button.className = "secondary"; button.textContent = "Replay";
      button.addEventListener("click", () => run(async () => { await api("/events/" + event.id + "/redeliver", "POST"); notice("Event queued. Its durable inbox prevents duplicate effects."); }));
      action.append(button);
    }
    $("events").append(row);
  }
}
async function refresh(silent = false) {
  try { render(await api("/snapshot")); } catch (error) { $("connection").textContent = "Disconnected"; if (!silent) notice(error.message, true); }
}
async function run(action) {
  if (loading) return; loading = true;
  try { await action(); await refresh(); } catch (error) { notice(error.message, true); }
  finally { loading = false; }
}
function newKey() { $("command-key").value = "demo-" + crypto.randomUUID(); }
$("new-key").addEventListener("click", newKey);
$("connect").addEventListener("click", () => refresh());
$("reservation-form").addEventListener("submit", (event) => {
  event.preventDefault();
  run(async () => {
    const result = await api("/reservations", "POST", { sku: $("sku").value, quantity: Number($("quantity").value), simulateFailure: $("failure").checked },
      { "Idempotency-Key": $("command-key").value });
    notice(result.replayed ? "Command replayed. Existing reservation returned; no inventory was reserved twice." : "Reservation " + short(result.reservation.id) + " committed with its outbox event.");
  });
});
$("pause").addEventListener("click", () => run(async () => { await api("/worker/pause", "POST", { paused: !snapshot.worker.paused }); }));
$("step").addEventListener("click", () => run(async () => { const result = await api("/worker/step", "POST"); notice(result.processed ? "One delivery attempted." : "No eligible event. A retry may still be waiting for its backoff."); }));
$("fault").addEventListener("click", () => run(async () => { await api("/worker/fault", "POST", { stage: $("fault-stage").value, count: 1 }); notice("Failure armed for the next matching delivery."); }));
newKey(); refresh(); setInterval(() => { if (!loading && !document.hidden) refresh(true); }, 2000);
