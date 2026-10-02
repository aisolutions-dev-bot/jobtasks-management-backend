package com.aisolutions.jobtaskmanagement.common.notification;

/** Row-lock clause appended to the outbox claim query. */
enum OutboxRowLockClause {
    /** Lets concurrent relay instances skip rows another instance already locked. */
    FOR_UPDATE_SKIP_LOCKED("FOR UPDATE SKIP LOCKED"),
    /** Serializes relay instances on the claimed rows when the server rejects SKIP LOCKED. */
    FOR_UPDATE_ONLY("FOR UPDATE");

    private final String sqlClause;

    /** Keeps the clause text the claim query appends verbatim. */
    OutboxRowLockClause(String sqlClause) {
        this.sqlClause = sqlClause;
    }

    /** Returns the clause text appended after the claim query's LIMIT placeholder. */
    String sqlClause() {
        return sqlClause;
    }
}
