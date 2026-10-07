-- Customer references and request (idempotency) keys are CASE-SENSITIVE (FR-009, FR-022a): "Bob" and "bob" are
-- different customers, "K1" and "k1" are different keys. V1 inherited the table collation utf8mb4_0900_ai_ci
-- (accent- and case-INsensitive), under which customer "Bob" + key "k1" collided with the hold of customer "bob"
-- and key "k1" in the unique index and in lookups, so a replay could return another customer's hold.
-- A binary collation makes equality, the unique key uk_holds_customer_request and every guarded
-- "customer_id = ?" update compare exact bytes.
ALTER TABLE holds
    MODIFY customer_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY request_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

-- data-model.md: max_per_hold defaults to 4 (V1 declared it without a default).
ALTER TABLE drops
    MODIFY max_per_hold INT NOT NULL DEFAULT 4;
