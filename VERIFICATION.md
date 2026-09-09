# Release verification

Verified on 10 September 2026 using Java 21.0.4 and a real PostgreSQL 17.11 server on Windows.

| Check | Actual result |
| --- | --- |
| Maven compilation and packaging | Passed; executable Spring Boot JAR produced |
| Unit suite after authentication fix | 5 passed, 0 failures, 0 errors, 0 skipped |
| PostgreSQL durability/concurrency suite | 9 passed, 0 failures, 0 errors, 0 skipped |
| Real packaged-process restart smoke | Passed across both producer-commit and consumer-commit-before-ack windows |
| Live HTTP guards | API key, encoded/matrix paths, input validation and idempotency-key conflict passed |
| Browser script / Python harness syntax | Passed |
| Docker image | Not built locally because Docker is unavailable; included in CI |
| Visual browser QA | Not performed |

The full database suite ran with AEGIS_INTEGRATION_TESTS=true against a dedicated PostgreSQL database. It verified concurrent duplicate commands, oversell prevention, transactional rollback, lost acknowledgement, expired lease recovery, compensation retries, dead-letter recovery, competing worker claims and monotonic projections.

An independent review found that checking the raw /api/ prefix could miss encoded and matrix-parameter routes. The fix authenticates every request except a small exact public-path allowlist. All five unit tests and the live HTTP smoke passed after this change. The nine database scenarios passed before this later HTTP-only change; their database implementation was unchanged.

The process smoke pauses the worker, persists two reservations, and commits one consumer transaction while deliberately withholding its acknowledgement. It then kills Java and starts a new JVM against the same database. Both reservations and their projections reached COMPENSATED, and command replay returned the existing reservation. The test uses real process termination and real database transactions.

Raw results are checked in at [validation/results/local-restart-smoke.json](validation/results/local-restart-smoke.json) and [validation/results/local-tests.json](validation/results/local-tests.json). They contain synthetic identifiers and no credentials. Full local JUnit reports remain under the ignored target directory; CI uploads its own reports.

Local JVM cold startup was unusually slow during the first attempt, which timed out before the workflow ran. The final successful run used AEGIS_STARTUP_TIMEOUT_SECONDS=360; readiness still had to pass. This is a correctness result, not a startup-latency or throughput benchmark.

No Kafka deployment, external fulfillment integration, or production traffic result is claimed.

## Browser verification

The real console was exercised at localhost on 10 September 2026. A simulated fulfillment failure reached COMPENSATED in both the write state and projection. Resubmitting the same command returned the replay message and left inventory unchanged. The console reported zero pending events and zero dead letters. No browser console errors or page-width overflow were observed at the default desktop viewport. See [captured console](validation/screenshots/console.png).

GitHub Actions also passed the PostgreSQL, real process restart and Docker image build checks for source commit 8b99ae00dc4b359a95f6de4898ddb0a74a559073.
