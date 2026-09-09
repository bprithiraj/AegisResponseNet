CREATE TABLE inventory (
    sku varchar(64) PRIMARY KEY,
    total integer NOT NULL CHECK (total >= 0),
    available integer NOT NULL CHECK (available >= 0 AND available <= total)
);
INSERT INTO inventory(sku, total, available) VALUES ('FIELD-KIT', 20, 20), ('RADIO', 10, 10);

CREATE TABLE reservation (
    id uuid PRIMARY KEY,
    sku varchar(64) NOT NULL REFERENCES inventory(sku),
    quantity integer NOT NULL CHECK (quantity > 0),
    status varchar(32) NOT NULL CHECK (status IN ('RESERVED', 'COMPLETED', 'COMPENSATION_PENDING', 'COMPENSATED')),
    version integer NOT NULL CHECK (version > 0),
    simulate_failure boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE command_record (
    idempotency_key varchar(128) PRIMARY KEY,
    request_hash char(64) NOT NULL,
    reservation_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT command_reservation_fk FOREIGN KEY(reservation_id) REFERENCES reservation(id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE outbox (
    id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL REFERENCES reservation(id),
    event_type varchar(40) NOT NULL CHECK (event_type IN ('RESERVATION_CREATED', 'RESERVATION_COMPLETED', 'COMPENSATION_REQUESTED', 'RESERVATION_COMPENSATED')),
    aggregate_version integer NOT NULL CHECK (aggregate_version > 0),
    status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_owner uuid,
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    published_at timestamptz,
    dead_lettered_at timestamptz,
    last_error varchar(250),
    UNIQUE(reservation_id, aggregate_version)
);
CREATE INDEX outbox_pending_idx ON outbox(available_at, created_at) WHERE published_at IS NULL AND dead_lettered_at IS NULL;
CREATE TABLE consumer_inbox (
    event_id uuid PRIMARY KEY REFERENCES outbox(id),
    processed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE reservation_projection (
    reservation_id uuid PRIMARY KEY REFERENCES reservation(id),
    sku varchar(64) NOT NULL,
    quantity integer NOT NULL,
    status varchar(32) NOT NULL,
    version integer NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
