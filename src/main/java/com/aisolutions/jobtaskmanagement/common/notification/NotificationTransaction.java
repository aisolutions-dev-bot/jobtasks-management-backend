package com.aisolutions.jobtaskmanagement.common.notification;

import io.vertx.mutiny.sqlclient.SqlClient;

/** Keeps the verified company identifier with its business transaction. */
public record NotificationTransaction(SqlClient transaction, String companyId) {

    /** Validates the transaction and resolves absent company claims to the main database. */
    public NotificationTransaction {
        java.util.Objects.requireNonNull(transaction, "transaction");
        companyId = companyId == null || companyId.isBlank() ? "db_test2" : companyId;
    }
}
