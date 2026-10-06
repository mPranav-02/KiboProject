package com.kibo.reservation.api;

/** Request headers used by the hold endpoints (contracts/openapi.yaml). */
public final class ApiHeaders {

    public static final String CUSTOMER_ID = "X-Customer-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** 1-64 chars of letters, digits and {@code . _ : -} for both headers. */
    public static final String ID_PATTERN = "^[A-Za-z0-9._:-]{1,64}$";

    private ApiHeaders() {
    }
}
