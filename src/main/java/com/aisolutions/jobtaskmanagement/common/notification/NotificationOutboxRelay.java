package com.aisolutions.jobtaskmanagement.common.notification;

import java.time.Duration;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import io.vertx.mutiny.sqlclient.SqlClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Relays committed outbox events and retains failed batches for the next scheduled attempt. */
@ApplicationScoped
public class NotificationOutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationOutboxRelay.class);

    @Inject
    NotificationOutboxRepository repository;

    @Inject
    @Channel("email-notifications")
    MutinyEmitter<NotificationEnvelope> emailEmitter;

    @Inject
    @Channel("sms-notifications")
    MutinyEmitter<NotificationEnvelope> smsEmitter;

    @Inject
    @Channel("whatsapp-notifications")
    MutinyEmitter<NotificationEnvelope> whatsappEmitter;

    @ConfigProperty(name = "notification.outbox.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "notification.outbox.batch-size", defaultValue = "100")
    int batchSize;

    /** Delegates to relayBatch; failures roll back claims and leave the batch available for retry. */
    @Scheduled(
            every = "${notification.outbox.poll-interval:1s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public Uni<Void> relayCommittedNotifications() {
        if (!enabled) {
            return Uni.createFrom().voidItem();
        }
        if (batchSize < 1 || batchSize > 1000) {
            return Uni.createFrom()
                    .failure(new IllegalArgumentException("Outbox batch size must be between 1 and 1000"));
        }
        return repository
                .relayPool()
                .withTransaction(this::relayBatch)
                .onFailure()
                .invoke(this::logRelayFailure);
    }

    /** Claims one batch and delegates broker acknowledgements and deletion to publishBatch. */
    private Uni<Void> relayBatch(SqlClient transaction) {
        return repository.lockBatch(transaction, batchSize).flatMap(events -> publishBatch(transaction, events));
    }

    /** Sends the batch before deleting its rows after every broker acknowledgement succeeds. */
    private Uni<Void> publishBatch(SqlClient transaction, List<NotificationOutboxEvent> events) {
        if (events.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        List<Uni<Void>> acknowledgements =
                events.stream().map(this::publishEvent).toList();
        return Uni.join()
                .all(acknowledgements)
                .andCollectFailures()
                .ifNoItem()
                .after(Duration.ofSeconds(10))
                .fail()
                .flatMap(ignored -> repository.removeAcknowledged(transaction, events));
    }

    /** Publishes a stable event identifier with the company as its Kafka partition key. */
    private Uni<Void> publishEvent(NotificationOutboxEvent event) {
        return resolveEmitter(event.channel())
                .sendMessage(KafkaRecord.of(event.envelope().companyId(), event.envelope()));
    }

    /** Selects the delivery topic without changing the versioned envelope. */
    private MutinyEmitter<NotificationEnvelope> resolveEmitter(String channel) {
        return switch (channel) {
            case "email" -> emailEmitter;
            case "sms" -> smsEmitter;
            case "whatsapp" -> whatsappEmitter;
            default -> throw new IllegalArgumentException("Unknown notification channel");
        };
    }

    /** Records the exception type without exposing activation tokens or recipient details. */
    private void logRelayFailure(Throwable failure) {
        LOGGER.warn(
                "Notification outbox batch retained after failure type={}",
                failure.getClass().getSimpleName());
    }
}
