# Development notes

## Transaction boundaries

The application intentionally uses explicit TransactionTemplate blocks so each crash boundary is visible in code.

1. ReservationService.reserve inserts the command key, conditionally decrements inventory, and inserts reservation + outbox in one transaction.
2. OutboxWorker.claim commits a short lease claim. Network work is not performed while the queue row lock is held.
3. EventConsumer.consume commits inbox deduplication, business state, any new outbox event, and the read projection together.
4. OutboxWorker.acknowledge marks delivery after the consumer transaction has committed.

A lost claim is retried after lease expiry. A lost acknowledgement is redelivered. The consumer's unique event key serializes overlapping deliveries. Aggregate row locks serialize different events for the same reservation.

## State machine

~~~text
RESERVED -- fulfillment succeeds --> COMPLETED
    |
    +-- fulfillment fails --> COMPENSATION_PENDING -- restore inventory --> COMPENSATED
~~~

COMPLETED keeps inventory consumed. COMPENSATED returns it. There is no customer cancellation or replenishment API in v1.

## Extending the transport

A future Kafka adapter can publish the immutable event ID and aggregate version from the outbox. Keep business effects and inbox writes in one consumer transaction. Broker acknowledgements belong after publication and must not remove the inbox contract. External effects need their own idempotency or fencing mechanism.

Do not claim an external transport guarantee from the current same-database integration tests.

## Operational checks

The console exposes pending and dead-letter counts to authenticated users. A production extension should add alerts for oldest pending event age, sustained dead letters, pool exhaustion, and projection lag; establish retention and replay policies before discarding inbox/outbox rows.

The current worker pause is process-local and is intended for an isolated demo. It is not a distributed fleet-wide maintenance switch.
