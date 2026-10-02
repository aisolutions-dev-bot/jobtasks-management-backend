package com.aisolutions.jobtaskmanagement.common.notification;

import jakarta.enterprise.context.ApplicationScoped;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.sqlclient.DatabaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the outbox claim row-lock clause from the connected server's real
 * capabilities. MySQL 8 and MariaDB 10.6 parse {@code SKIP LOCKED}; the shared
 * MariaDB 10.5 server rejects it with a syntax error, so the resolver falls back
 * to a plain {@code FOR UPDATE} instead of failing every relay poll.
 */
@ApplicationScoped
public class OutboxRowLockClauseResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRowLockClauseResolver.class);

    private static final int SYNTAX_ERROR_CODE = 1064;
    private static final String SKIP_LOCKED_PROBE = "SELECT 1 FROM DUAL LIMIT 0 FOR UPDATE SKIP LOCKED";

    /** Probes SKIP LOCKED support and delegates the clause mapping to selectRowLockClause. */
    public Uni<OutboxRowLockClause> resolveRowLockClause(Pool pool) {
        return probeSkipLockedSupport(pool).map(this::selectRowLockClause);
    }

    /** Parses the probe against the pool, delegating rejected syntax to recoverUnsupportedSyntax. */
    private Uni<Boolean> probeSkipLockedSupport(Pool pool) {
        return pool.query(SKIP_LOCKED_PROBE)
                .execute()
                .replaceWith(true)
                .onFailure()
                .recoverWithUni(this::recoverUnsupportedSyntax);
    }

    /** Turns a syntax rejection into a false item and re-raises every unrelated failure. */
    private Uni<Boolean> recoverUnsupportedSyntax(Throwable failure) {
        if (isUnsupportedSyntaxFailure(failure)) {
            return Uni.createFrom().item(false);
        }
        return Uni.createFrom().failure(failure);
    }

    /** Maps the probe result to the claim clause and records the selection for operations. */
    OutboxRowLockClause selectRowLockClause(boolean supportsSkipLocked) {
        OutboxRowLockClause clause =
                supportsSkipLocked ? OutboxRowLockClause.FOR_UPDATE_SKIP_LOCKED : OutboxRowLockClause.FOR_UPDATE_ONLY;
        LOGGER.info("Notification outbox claim uses row lock clause '{}'", clause.sqlClause());
        return clause;
    }

    /** Walks wrapped causes for the server's syntax error code. */
    boolean isUnsupportedSyntaxFailure(Throwable failure) {
        Throwable currentFailure = failure;
        while (currentFailure != null) {
            if (currentFailure instanceof DatabaseException databaseFailure
                    && databaseFailure.getErrorCode() == SYNTAX_ERROR_CODE) {
                return true;
            }
            currentFailure = currentFailure.getCause();
        }
        return false;
    }
}
