package com.aisolutions.jobtaskmanagement.common.notification;

import java.time.Duration;
import java.util.List;

import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.mysqlclient.MySQLBuilder;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the outbox claim against MariaDB 10.5, the shared company database
 * version, which rejects {@code SKIP LOCKED} with a syntax error.
 */
@Testcontainers
class NotificationOutboxMariaDbCompatibilityTest {

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);
    private static final String COMPATIBILITY_EVENT_ID = "mariadb-event";

    @Container
    static final MariaDBContainer<?> DATABASE = new MariaDBContainer<>(DockerImageName.parse("mariadb:10.5"))
            .withDatabaseName("outbox_main")
            .withUsername("root")
            .withPassword("outbox-test-password");

    private static Vertx vertx;
    private static Pool mainPool;
    private static NotificationOutboxRepository repository;

    /** Starts MariaDB 10.5 and provisions the outbox through the production repository. */
    @BeforeAll
    static void startDatabasePool() {
        vertx = Vertx.vertx();
        mainPool = createPool();
        repository = new NotificationOutboxRepository();
        repository.defaultPool = mainPool;
        repository.rowLockClauseResolver = new OutboxRowLockClauseResolver();
        repository.databaseUrl = "mysql://" + DATABASE.getHost() + ":" + DATABASE.getFirstMappedPort() + "/"
                + DATABASE.getDatabaseName();
        repository.enabled = true;
        repository.initialize(new StartupEvent());
    }

    /** Closes the pool and the reactive runtime after the compatibility scenario. */
    @AfterAll
    static void closeDatabasePool() {
        mainPool.close().await().atMost(QUERY_TIMEOUT);
        vertx.close().await().atMost(QUERY_TIMEOUT);
    }

    /** Claims and acknowledges a staged event on a server without SKIP LOCKED support. */
    @Test
    void claimsStagedEventWithoutSkipLockedSupport() {
        NotificationOutboxEvent event = stagedEvent();
        stageEvent(event);

        List<NotificationOutboxEvent> claimed = mainPool.withTransaction(
                        connection -> repository.lockBatch(connection, 100))
                .await()
                .atMost(QUERY_TIMEOUT);

        assertThat(claimed).containsExactly(event);
        acknowledgeEvents(claimed);
        assertThat(countOutboxRows()).isZero();
    }

    /** Builds the assignment envelope the relay would publish for the staged row. */
    private NotificationOutboxEvent stagedEvent() {
        return new NotificationOutboxEvent(
                "email",
                new NotificationEnvelope(
                        COMPATIBILITY_EVENT_ID,
                        "tenant-blue",
                        "staff@example.com",
                        "New Task Assigned",
                        "Compatibility body",
                        null,
                        null,
                        null));
    }

    /** Commits the event through the same enqueue path the business transaction uses. */
    private void stageEvent(NotificationOutboxEvent event) {
        mainPool.withTransaction(
                        connection -> repository.enqueue(new NotificationTransaction(connection, "db_test2"), event))
                .await()
                .atMost(QUERY_TIMEOUT);
    }

    /** Removes the claimed events through the same acknowledgement path the relay uses. */
    private void acknowledgeEvents(List<NotificationOutboxEvent> events) {
        mainPool.withTransaction(connection -> repository.removeAcknowledged(connection, events))
                .await()
                .atMost(QUERY_TIMEOUT);
    }

    /** Counts the outbox rows left after acknowledgement. */
    private long countOutboxRows() {
        return mainPool.query("SELECT COUNT(*) AS RowCount FROM jobtasks_notification_outbox")
                .execute()
                .await()
                .atMost(QUERY_TIMEOUT)
                .iterator()
                .next()
                .getLong("RowCount");
    }

    /** Opens a reactive pool on the MariaDB container's main schema. */
    private static Pool createPool() {
        MySQLConnectOptions options = new MySQLConnectOptions()
                .setHost(DATABASE.getHost())
                .setPort(DATABASE.getFirstMappedPort())
                .setDatabase(DATABASE.getDatabaseName())
                .setUser(DATABASE.getUsername())
                .setPassword(DATABASE.getPassword());
        return MySQLBuilder.pool()
                .using(vertx)
                .connectingTo(options)
                .with(new PoolOptions().setMaxSize(3))
                .build();
    }
}
