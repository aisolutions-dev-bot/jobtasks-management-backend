package com.aisolutions.jobtaskmanagement.jobtask.service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.jobtaskmanagement.common.notification.NotificationEnvelope;
import com.aisolutions.jobtaskmanagement.common.notification.NotificationOutboxEvent;
import com.aisolutions.jobtaskmanagement.common.notification.NotificationOutboxRepository;
import com.aisolutions.jobtaskmanagement.common.notification.NotificationTransaction;
import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.entity.Staff;
import com.aisolutions.jobtaskmanagement.jobtask.service.email.JobTaskEmailTemplate;
import io.smallrye.mutiny.Uni;

/** Queues task notifications atomically with assignment and completion changes. */
@ApplicationScoped
public class JobTaskNotificationService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    @Inject
    NotificationOutboxRepository repository;

    /** Carries task content and resolved recipients without retaining a request identity. */
    public record TaskNotification(JobTask task, Staff assignee, Staff assignor) {}

    /** Delegates assignment content to queueAssignedChannels within the supplied transaction. */
    public Uni<Void> notifyTaskAssigned(NotificationTransaction context, TaskNotification notification) {
        if (notification.assignee() == null) {
            return Uni.createFrom().voidItem();
        }
        return queueAssignedChannels(context, notification);
    }

    /** Builds assignment content and sequentially queues each eligible delivery channel. */
    private Uni<Void> queueAssignedChannels(NotificationTransaction context, TaskNotification notification) {
        JobTask task = notification.task();
        Staff recipient = notification.assignee();
        String assignorName = notification.assignor() == null
                ? "Unknown"
                : notification.assignor().getName();
        String dueDate =
                task.getDueDate() == null ? "No due date" : task.getDueDate().format(DATE_FORMAT);
        String body = JobTaskEmailTemplate.buildAssignedEmail(new JobTaskEmailTemplate.AssignedEmailContent(
                recipient.getName(),
                assignorName,
                task.getJobTaskId(),
                task.getTaskTitle(),
                task.getPriority(),
                dueDate,
                task.getTaskDescription()));
        NotificationEnvelope email =
                emailEnvelope(context, recipient, "New Task Assigned: " + task.getTaskTitle(), body);
        NotificationEnvelope sms = textEnvelope(
                context,
                recipient.getTelMobile(),
                "New task assigned to you: " + task.getTaskTitle() + " (Due: " + dueDate + "). - AI Solutions");
        NotificationEnvelope whatsapp = templateEnvelope(
                context, recipient.getTelMobile(), "jobtask_assigned_v1", assignedParameters(notification, dueDate));
        return queueChannels(context, java.util.Arrays.asList(email, sms, whatsapp));
    }

    /** Creates the approved assignment template's named body parameters. */
    private List<Map<String, Object>> assignedParameters(TaskNotification notification, String dueDate) {
        return bodyParameters(List.of(
                namedParameter("assignee_name", notification.assignee().getName()),
                namedParameter("task_title", notification.task().getTaskTitle()),
                namedParameter("job_task_id", notification.task().getJobTaskId()),
                namedParameter("due_date", dueDate)));
    }

    /** Delegates completion content to queueCompletedChannels within the supplied transaction. */
    public Uni<Void> notifyTaskCompleted(NotificationTransaction context, TaskNotification notification) {
        if (notification.assignor() == null) {
            return Uni.createFrom().voidItem();
        }
        return queueCompletedChannels(context, notification);
    }

    /** Builds completion content and sequentially queues each eligible channel. */
    private Uni<Void> queueCompletedChannels(NotificationTransaction context, TaskNotification notification) {
        JobTask task = notification.task();
        Staff recipient = notification.assignor();
        String assigneeName = notification.assignee() == null
                ? "Unknown"
                : notification.assignee().getName();
        String completedDate =
                task.getCompletedDate() == null ? "" : task.getCompletedDate().format(DATE_FORMAT);
        String body = JobTaskEmailTemplate.buildCompletedEmail(new JobTaskEmailTemplate.CompletedEmailContent(
                recipient.getName(), assigneeName, task.getJobTaskId(), task.getTaskTitle(), completedDate));
        NotificationEnvelope email = emailEnvelope(context, recipient, "Task Completed: " + task.getTaskTitle(), body);
        NotificationEnvelope sms = textEnvelope(
                context,
                recipient.getTelMobile(),
                assigneeName + " completed task: " + task.getTaskTitle() + ". - AI Solutions");
        NotificationEnvelope whatsapp = templateEnvelope(
                context,
                recipient.getTelMobile(),
                "jobtask_completed_v1",
                completedParameters(notification, assigneeName));
        return queueChannels(context, java.util.Arrays.asList(email, sms, whatsapp));
    }

    /** Creates the approved completion template's named body parameters. */
    private List<Map<String, Object>> completedParameters(TaskNotification notification, String assigneeName) {
        return bodyParameters(List.of(
                namedParameter("assignor_name", notification.assignor().getName()),
                namedParameter("assignee_name", assigneeName),
                namedParameter("task_title", notification.task().getTaskTitle()),
                namedParameter("job_task_id", notification.task().getJobTaskId())));
    }

    /** Builds an email envelope only when the recipient has an email address. */
    private NotificationEnvelope emailEnvelope(
            NotificationTransaction context, Staff recipient, String subject, String body) {
        if (isAbsent(recipient.getEmailCompany())) {
            return null;
        }
        return new NotificationEnvelope(
                UUID.randomUUID().toString(),
                context.companyId(),
                recipient.getEmailCompany(),
                subject,
                body,
                null,
                null,
                List.of());
    }

    /** Builds the original SMS text envelope. */
    private NotificationEnvelope textEnvelope(NotificationTransaction context, String recipient, String body) {
        if (isAbsent(recipient)) {
            return null;
        }
        return new NotificationEnvelope(
                UUID.randomUUID().toString(), context.companyId(), recipient, null, body, null, null, List.of());
    }

    /** Builds a named-parameter template envelope with Meta's original mobile normalization. */
    private NotificationEnvelope templateEnvelope(
            NotificationTransaction context,
            String recipient,
            String templateName,
            List<Map<String, Object>> components) {
        if (isAbsent(recipient)) {
            return null;
        }
        String normalizedRecipient = recipient;
        normalizedRecipient =
                normalizedRecipient.startsWith("+") ? normalizedRecipient.substring(1) : normalizedRecipient;
        return new NotificationEnvelope(
                UUID.randomUUID().toString(),
                context.companyId(),
                normalizedRecipient,
                null,
                null,
                templateName,
                "en",
                components);
    }

    /** Stages each channel sequentially because a transaction shares one database connection. */
    private Uni<Void> queueChannels(NotificationTransaction context, List<NotificationEnvelope> envelopes) {
        return enqueue(context, "email", envelopes.get(0))
                .flatMap(ignored -> enqueue(context, "sms", envelopes.get(1)))
                .flatMap(ignored -> enqueue(context, "whatsapp", envelopes.get(2)));
    }

    /** Skips absent contacts and persists all other delivery requests without source-side toggle gating. */
    private Uni<Void> enqueue(NotificationTransaction context, String channel, NotificationEnvelope envelope) {
        if (envelope == null) {
            return Uni.createFrom().voidItem();
        }
        return repository.enqueue(context, new NotificationOutboxEvent(channel, envelope));
    }

    /** Identifies contacts that cannot receive delivery requests. */
    private boolean isAbsent(String recipient) {
        return recipient == null || recipient.isBlank();
    }

    /** Wraps named parameters in the Meta body component shape. */
    private List<Map<String, Object>> bodyParameters(List<Map<String, Object>> parameters) {
        return List.of(Map.of("type", "body", "parameters", parameters));
    }

    /** Preserves the specification-defined parameter_name and text wire fields. */
    private Map<String, Object> namedParameter(String parameterName, String value) {
        return Map.of("type", "text", "parameter_name", parameterName, "text", value == null ? "" : value);
    }
}
