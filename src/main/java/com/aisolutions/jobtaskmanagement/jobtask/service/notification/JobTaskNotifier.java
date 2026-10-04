package com.aisolutions.jobtaskmanagement.jobtask.service.notification;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.entity.Staff;
import com.aisolutions.shared.notification.NotificationPublisher;
import com.aisolutions.shared.notification.NotificationTransaction;
import io.smallrye.mutiny.Uni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stages Job Task assignment and completion notifications on the shared channel pipeline.
 *
 * <p>Keeps task facts for the registry templates and delegates every delivery
 * request to {@link NotificationPublisher}; a channel is skipped when its
 * recipient detail is absent.
 */
@ApplicationScoped
public class JobTaskNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobTaskNotifier.class);

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final String TEMPLATE_LANGUAGE_CODE = "en";
    private static final String ASSIGNED_TEMPLATE_NAME = "jobtask_assigned_v1";
    private static final String COMPLETED_TEMPLATE_NAME = "jobtask_completed_v1";
    private static final String UNKNOWN_STAFF_NAME = "Unknown";
    private static final String NO_DUE_DATE = "No due date";
    private static final String RECIPIENT_MISSING_REASON = "recipient_missing";

    @Inject
    NotificationPublisher notificationPublisher;

    /** Carries task content and resolved recipients without retaining a request identity. */
    public record TaskNotification(JobTask task, Staff assignee, Staff assignor) {}

    /** Skips tasks without an assignee, otherwise delegates to the assignment channel helpers. */
    public Uni<Void> notifyTaskAssigned(NotificationTransaction context, TaskNotification notification) {
        if (notification.assignee() == null) {
            return recordSkippedNotification(context, notification, "all", "assignee_missing");
        }
        return enqueueAssignedEmail(context, notification)
                .flatMap(ignored -> enqueueAssignedSms(context, notification))
                .flatMap(ignored -> enqueueAssignedWhatsapp(context, notification));
    }

    /** Skips tasks without an assignor, otherwise delegates to the completion channel helpers. */
    public Uni<Void> notifyTaskCompleted(NotificationTransaction context, TaskNotification notification) {
        if (notification.assignor() == null) {
            return recordSkippedNotification(context, notification, "all", "assignor_missing");
        }
        return enqueueCompletedEmail(context, notification)
                .flatMap(ignored -> enqueueCompletedSms(context, notification))
                .flatMap(ignored -> enqueueCompletedWhatsapp(context, notification));
    }

    /** Stages the assignment email template when the assignee has a company address. */
    private Uni<Void> enqueueAssignedEmail(NotificationTransaction context, TaskNotification notification) {
        String emailAddress = notification.assignee().getEmailCompany();
        if (isAbsent(emailAddress)) {
            return recordSkippedNotification(context, notification, "email", RECIPIENT_MISSING_REASON);
        }
        return notificationPublisher.enqueueEmailTemplate(
                context, emailAddress, ASSIGNED_TEMPLATE_NAME, TEMPLATE_LANGUAGE_CODE, taskTemplateParameters(notification));
    }

    /** Stages the assignment SMS when the assignee has a mobile number. */
    private Uni<Void> enqueueAssignedSms(NotificationTransaction context, TaskNotification notification) {
        String mobileNumber = notification.assignee().getTelMobile();
        if (isAbsent(mobileNumber)) {
            return recordSkippedNotification(context, notification, "sms", RECIPIENT_MISSING_REASON);
        }
        return notificationPublisher.enqueueSmsTemplate(
                context, mobileNumber, ASSIGNED_TEMPLATE_NAME, TEMPLATE_LANGUAGE_CODE, taskTemplateParameters(notification));
    }

    /** Stages the approved assignment template when the assignee has a mobile number. */
    private Uni<Void> enqueueAssignedWhatsapp(NotificationTransaction context, TaskNotification notification) {
        String mobileNumber = notification.assignee().getTelMobile();
        if (isAbsent(mobileNumber)) {
            return recordSkippedNotification(context, notification, "whatsapp", RECIPIENT_MISSING_REASON);
        }
        return notificationPublisher.enqueueWhatsappTemplate(
                context,
                mobileNumber,
                ASSIGNED_TEMPLATE_NAME,
                TEMPLATE_LANGUAGE_CODE,
                taskTemplateParameters(notification));
    }

    /** Stages the completion email template when the assignor has a company address. */
    private Uni<Void> enqueueCompletedEmail(NotificationTransaction context, TaskNotification notification) {
        String emailAddress = notification.assignor().getEmailCompany();
        if (isAbsent(emailAddress)) {
            return recordSkippedNotification(context, notification, "email", RECIPIENT_MISSING_REASON);
        }
        return notificationPublisher.enqueueEmailTemplate(
                context,
                emailAddress,
                COMPLETED_TEMPLATE_NAME,
                TEMPLATE_LANGUAGE_CODE,
                taskTemplateParameters(notification));
    }

    /** Stages the completion SMS when the assignor has a mobile number. */
    private Uni<Void> enqueueCompletedSms(NotificationTransaction context, TaskNotification notification) {
        String mobileNumber = notification.assignor().getTelMobile();
        if (isAbsent(mobileNumber)) {
            return recordSkippedNotification(context, notification, "sms", RECIPIENT_MISSING_REASON);
        }
        return notificationPublisher.enqueueSmsTemplate(
                context,
                mobileNumber,
                COMPLETED_TEMPLATE_NAME,
                TEMPLATE_LANGUAGE_CODE,
                taskTemplateParameters(notification));
    }

    /** Stages the approved completion template when the assignor has a mobile number. */
    private Uni<Void> enqueueCompletedWhatsapp(NotificationTransaction context, TaskNotification notification) {
        String mobileNumber = notification.assignor().getTelMobile();
        if (isAbsent(mobileNumber)) {
            return recordSkippedNotification(context, notification, "whatsapp", RECIPIENT_MISSING_REASON);
        }
        return notificationPublisher.enqueueWhatsappTemplate(
                context,
                mobileNumber,
                COMPLETED_TEMPLATE_NAME,
                TEMPLATE_LANGUAGE_CODE,
                taskTemplateParameters(notification));
    }

    /** Builds task data for registry templates without rendering channel content. */
    private Map<String, Object> taskTemplateParameters(TaskNotification notification) {
        JobTask task = notification.task();
        String completedDate =
                task.getCompletedDate() == null ? "" : task.getCompletedDate().format(DATE_FORMAT);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("assignee_name", emptyIfNull(resolveAssigneeName(notification)));
        parameters.put("assignor_name", emptyIfNull(
                notification.assignor() == null ? UNKNOWN_STAFF_NAME : notification.assignor().getName()));
        parameters.put("task_title", emptyIfNull(task.getTaskTitle()));
        parameters.put("job_task_id", emptyIfNull(task.getJobTaskId()));
        parameters.put("priority", task.getPriority() == null ? "" : task.getPriority().toString());
        parameters.put("due_date", resolveDueDate(task));
        parameters.put("task_description", emptyIfNull(task.getTaskDescription()));
        parameters.put("completed_date", completedDate);
        return Map.copyOf(parameters);
    }

    /** Renders the task due date or its absence marker. */
    private String resolveDueDate(JobTask task) {
        return task.getDueDate() == null ? NO_DUE_DATE : task.getDueDate().format(DATE_FORMAT);
    }

    /** Renders the assignee name or its absence marker. */
    private String resolveAssigneeName(TaskNotification notification) {
        return notification.assignee() == null
                ? UNKNOWN_STAFF_NAME
                : notification.assignee().getName();
    }

    /** Records the task and channel skip without exposing recipient details or message content. */
    private Uni<Void> recordSkippedNotification(
            NotificationTransaction context, TaskNotification notification, String channel, String reason) {
        LOGGER.info(
                "notification skipped companyId={} channel={} businessIdentity={} reason={}",
                context.companyId(),
                channel,
                notification.task() == null ? null : notification.task().getJobTaskId(),
                reason);
        return Uni.createFrom().voidItem();
    }

    /** Reports whether a recipient detail is absent. */
    private boolean isAbsent(String value) {
        return value == null || value.isBlank();
    }

    /** Replaces null text values so the shared envelope can copy the parameter map. */
    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
