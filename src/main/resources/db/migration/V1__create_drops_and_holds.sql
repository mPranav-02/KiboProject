-- KIBO Limited Drop Reservation Service: initial schema (data-model.md).
-- MySQL is the source of truth. The CHECK constraints are defense in depth for the
-- no-oversell invariant; the primary guard is the atomic conditional UPDATE in the application.
-- All timestamps are UTC, DATETIME(6).

CREATE TABLE drops (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    name               VARCHAR(200)  NOT NULL,
    description        VARCHAR(2000) NULL,
    total_quantity     INT           NOT NULL,
    available_quantity INT           NOT NULL,
    max_per_hold       INT           NOT NULL,
    starts_at          DATETIME(6)   NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    CONSTRAINT pk_drops PRIMARY KEY (id),
    CONSTRAINT ck_drops_total_positive  CHECK (total_quantity > 0),
    CONSTRAINT ck_drops_available_range CHECK (available_quantity >= 0 AND available_quantity <= total_quantity),
    CONSTRAINT ck_drops_max_per_hold    CHECK (max_per_hold >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE holds (
    id          CHAR(36)    NOT NULL,
    drop_id     BIGINT      NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    quantity    INT         NOT NULL,
    status      VARCHAR(16) NOT NULL,
    expires_at  DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_holds PRIMARY KEY (id),
    CONSTRAINT fk_holds_drop FOREIGN KEY (drop_id) REFERENCES drops (id),
    -- Idempotency: one hold per (customer, request key), including concurrent duplicates (FR-009).
    CONSTRAINT uk_holds_customer_request UNIQUE (customer_id, request_key),
    CONSTRAINT ck_holds_quantity CHECK (quantity >= 1),
    CONSTRAINT ck_holds_status   CHECK (status IN ('ACTIVE', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    INDEX ix_holds_status_expires (status, expires_at), -- expiry sweep
    INDEX ix_holds_drop (drop_id)                        -- FK + invariant checks
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
