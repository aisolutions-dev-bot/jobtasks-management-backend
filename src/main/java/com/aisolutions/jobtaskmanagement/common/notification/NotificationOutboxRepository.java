package com.aisolutions.jobtaskmanagement.common.notification;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Persists tenant-tagged requests in the main schema on the shared MySQL server. */
@ApplicationScoped
public class NotificationOutboxRepository {

    @Inject
    Pool defaultPool;

    @Inject
    OutboxRowLockClauseResolver rowLockClauseResolver;

    @ConfigProperty(name = "quarkus.datasource.reactive.url")
    String databaseUrl;

    @ConfigProperty(name = "notification.outbox.enabled", defaultValue = "true")
    boolean enabled;

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);

    private String tableName;
    private OutboxRowLockClause rowLockClause = OutboxRowLockClause.FOR_UPDATE_SKIP_LOCKED;

    /**
     * Resolves the qualified table name, provisions its InnoDB schema and asks
     * {@link OutboxRowLockClauseResolver} which row-lock clause the server accepts.
     */
    void initialize(@Observes StartupEvent startupEvent) {
        tableName = resolveQualifiedTableName();
        if (enabled) {
            provisionTable().await().atMost(STARTUP_TIMEOUT);
            rowLockClause = rowLockClauseResolver
                    .resolveRowLockClause(defaultPool)
                    .await()
                    .atMost(STARTUP_TIMEOUT);
        }
    }

    /** Serializes the envelope and stages it on the business connection. */
    public Uni<Void> enqueue(NotificationTransaction context, NotificationOutboxEvent event) {
        String statement = "INSERT INTO " + tableName + " (NotificationId,CompanyId,Channel,Payload) VALUES (?,?,?,?)";
        return context.transaction()
                .preparedQuery(statement)
                .execute(createParameters(event))
                .replaceWithVoid();
    }

    /** Creates parameters without exposing provider credentials or recipient data in logs. */
    private Tuple createParameters(NotificationOutboxEvent event) {
        return Tuple.of(
                event.envelope().notificationId(),
                event.envelope().companyId(),
                event.channel(),
                io.vertx.core.json.Json.encode(event.envelope()));
    }

    /** Claims the oldest committed events using the row-lock clause the connected server accepted. */
    public Uni<List<NotificationOutboxEvent>> lockBatch(SqlClient transaction, int batchSize) {
        String statement = "SELECT NotificationId,CompanyId,Channel,Payload FROM " + tableName
                + " ORDER BY CreatedDate,NotificationId LIMIT ? " + rowLockClause.sqlClause();
        return transaction.preparedQuery(statement).execute(Tuple.of(batchSize)).map(this::mapEvents);
    }

    /** Removes acknowledged events in one parameterized statement inside the claiming transaction. */
    public Uni<Void> removeAcknowledged(SqlClient transaction, List<NotificationOutboxEvent> events) {
        if (events.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        List<String> placeholders = events.stream().map(event -> "?").toList();
        String statement =
                "DELETE FROM " + tableName + " WHERE NotificationId IN (" + String.join(",", placeholders) + ")";
        Tuple parameters = Tuple.tuple();
        events.forEach(event -> parameters.addString(event.envelope().notificationId()));
        return transaction.preparedQuery(statement).execute(parameters).replaceWithVoid();
    }

    /** Returns the main database pool used to claim staged events independently of user requests. */
    public Pool relayPool() {
        return defaultPool;
    }

    /** Quotes a validated schema identifier from the configured main database URL. */
    private String resolveQualifiedTableName() {
        String path = URI.create(databaseUrl).getPath();
        if (path == null || !path.matches("/[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("The notification outbox requires a valid main database schema");
        }
        return "`" + path.substring(1) + "`.`jobtasks_notification_outbox`";
    }

    /** Creates the durable table with an oldest-first index and transactional storage. */
    private Uni<Void> provisionTable() {
        String statement = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "NotificationId VARCHAR(36) NOT NULL PRIMARY KEY,"
                + "CompanyId VARCHAR(100) NOT NULL,Channel VARCHAR(20) NOT NULL,"
                + "Payload MEDIUMTEXT NOT NULL,"
                + "CreatedDate TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                + "INDEX notification_outbox_pending (CreatedDate,NotificationId)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        return defaultPool.query(statement).execute().replaceWithVoid();
    }

    /** Converts committed rows through mapEvent without serializing tenant data into logs. */
    private List<NotificationOutboxEvent> mapEvents(RowSet<Row> rows) {
        List<NotificationOutboxEvent> events = new ArrayList<>();
        rows.forEach(row -> events.add(mapEvent(row)));
        return events;
    }

    /** Reconstructs the channel and original immutable envelope. */
    private NotificationOutboxEvent mapEvent(Row row) {
        return new NotificationOutboxEvent(
                row.getString("Channel"),
                io.vertx.core.json.Json.decodeValue(row.getString("Payload"), NotificationEnvelope.class));
    }
}
