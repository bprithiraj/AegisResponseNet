# Release verification

Verification is being run for the initial standalone release.

The implementation includes four unit tests, nine PostgreSQL integration scenarios, and a packaged-process restart smoke. Results will be recorded here after execution. The integration suite must run with AEGIS_INTEGRATION_TESTS=true; a skipped suite does not count as validation.

No load-test, Kafka integration, or external fulfillment claim is made.
