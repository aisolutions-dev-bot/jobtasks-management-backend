package com.aisolutions.jobtaskmanagement.common.notification;

import java.time.Duration;
import java.util.concurrent.CompletionException;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Query;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mysqlclient.MySQLException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies row-lock clause selection and the startup SKIP LOCKED probe. */
class OutboxRowLockClauseResolverTest {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private final OutboxRowLockClauseResolver resolver = new OutboxRowLockClauseResolver();

    /** Prefers SKIP LOCKED so concurrent relay instances claim disjoint batches. */
    @Test
    void selectsSkipLockedWhenTheServerParsesIt() {
        assertThat(resolver.selectRowLockClause(true)).isEqualTo(OutboxRowLockClause.FOR_UPDATE_SKIP_LOCKED);
    }

    /** Falls back to a plain FOR UPDATE on servers that reject the clause. */
    @Test
    void selectsPlainForUpdateWhenTheServerRejectsIt() {
        assertThat(resolver.selectRowLockClause(false)).isEqualTo(OutboxRowLockClause.FOR_UPDATE_ONLY);
    }

    /** Detects the driver's syntax error code through wrapping causes. */
    @Test
    void detectsSyntaxErrorsThroughWrappedCauses() {
        Throwable wrappedFailure = new CompletionException(new MySQLException("syntax", 1064, "42000"));
        assertThat(resolver.isUnsupportedSyntaxFailure(wrappedFailure)).isTrue();
    }

    /** Keeps unrelated database failures distinct from an unsupported clause. */
    @Test
    void ignoresUnrelatedDatabaseFailures() {
        assertThat(resolver.isUnsupportedSyntaxFailure(new MySQLException("denied", 1045, "28000")))
                .isFalse();
    }

    /** Resolves SKIP LOCKED when the probe parses. */
    @Test
    void resolvesSkipLockedWhenTheProbeParses() {
        Pool pool = poolReturning(Uni.createFrom().item((RowSet<Row>) null));

        assertThat(resolver.resolveRowLockClause(pool).await().atMost(PROBE_TIMEOUT))
                .isEqualTo(OutboxRowLockClause.FOR_UPDATE_SKIP_LOCKED);
    }

    /** Resolves the fallback clause when the probe is rejected as a syntax error. */
    @Test
    void resolvesPlainForUpdateWhenTheProbeIsRejected() {
        Uni<RowSet<Row>> rejection =
                Uni.createFrom().failure(new CompletionException(new MySQLException("syntax", 1064, "42000")));

        assertThat(resolver.resolveRowLockClause(poolReturning(rejection))
                        .await()
                        .atMost(PROBE_TIMEOUT))
                .isEqualTo(OutboxRowLockClause.FOR_UPDATE_ONLY);
    }

    /** Propagates probe failures that are not syntax rejections. */
    @Test
    void propagatesUnrelatedProbeFailures() {
        Uni<RowSet<Row>> rejection = Uni.createFrom().failure(new MySQLException("denied", 1045, "28000"));

        assertThatThrownBy(() -> resolver.resolveRowLockClause(poolReturning(rejection))
                        .await()
                        .atMost(PROBE_TIMEOUT))
                .isInstanceOf(MySQLException.class);
    }

    /** Builds a pool whose claim query resolves to the supplied probe outcome. */
    @SuppressWarnings("unchecked")
    private Pool poolReturning(Uni<RowSet<Row>> probeOutcome) {
        Query<RowSet<Row>> query = mock(Query.class);
        when(query.execute()).thenReturn(probeOutcome);
        Pool pool = mock(Pool.class);
        when(pool.query(anyString())).thenReturn(query);
        return pool;
    }
}
