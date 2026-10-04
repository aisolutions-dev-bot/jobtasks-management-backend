package com.aisolutions.jobtaskmanagement.jobtask.service.notification;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.entity.Staff;
import com.aisolutions.jobtaskmanagement.jobtask.service.email.JobTaskEmailTemplate;
import com.aisolutions.shared.notification.NotificationPublisher;
import com.aisolutions.shared.notification.NotificationTransaction;
import io.smallrye.mutiny.Uni;

/**
 * Stages Job Task assignment and completion notifications on the shared channel pipeline.
 *
 * <p>Keeps the task email, SMS and WhatsApp content and delegates every delivery
 * request to {@link NotificationPublisher}; a channel is skipped when its
 * recipient detail is absent.
 */
@ApplicationScoped
public class JobTaskNotifier {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final String WHATSAPP_LANGUAGE_CODE = "en";
    private static final String ASSIGNED_TEMPLATE_NAME = "jobtask_assigned_v1";
    private static final String COMPLETED_TEMPLATE_NAME = "jobtask_completed_v1";
    private static final String UNKNOWN_STAFF_NAME = "Unknown";
    private static final String NO_DUE_DATE = "No due date";

    @Inject
    NotificationPublisher notificationPublisher;

    /** Carries task content and resolved recipients without retaining a request identity. */
    public record TaskNotification(JobTask task, Staff assignee, Staff assignor) {}

    /** Skips tasks without an assignee, otherwise delegates to the assignment channel helpers. */
    public Uni<Void> notifyTaskAssigned(NotificationTransaction context, TaskNotification notification) {
        if (notification.assignee() == null) {
            return Uni.createFrom().voidItem();
        }
        return enqueueAssignedEmail(context, notification)
                .flatMap(ignored -> enqueueAssignedSms(context, notification))
                .flatMap(ignored -> enqueueAssignedWhatsapp(context, notification));
    }

    /** Skips tasks without an assignor, otherwise delegates to the completion channel helpers. */
    public Uni<Void> notifyTaskCompleted(NotificationTransaction context, TaskNotification notification) {
        if (notification.assignor() == null) {
            return Uni.createFrom().voidItem();
        }
        return enqueueCompletedEmail(context, notification)
                .flatMap(ignored -> enqueueCompletedSms(context, notification))
                .flatMap(ignored -> enqueueCompletedWhatsapp(context, notification));
    }

    /** Stages the rendered assignment email when the assignee has a company address. */
    private Uni<Void> enqueueAssignedEmail(NotificationTransaction context, TaskNotification notification) {
        String emailAddress = notification.assignee().getEmailCompany();
        if (isAbsent(emailAddress)) {
            return Uni.createFrom().voidItem();
        }
        return notificationPublisher.enqueueEmail(
                context, emailAddress, assignmentSubject(notification), buildAssignedEmailBody(notification));
    }

    /** Stages the assignment SMS when the assignee has a mobile number. */
    private Uni<Void> enqueueAssignedSms(NotificationTransaction context, TaskNotification notification) {
        String mobileNumber = notification.assignee().getTelMobile();
        if (isAbsent(mobileNumber)) {
            return Uni.createFrom().voidItem();
        }
        return notificationPublisher.enqueueSms(context, mobileNumber, buildAssignedSmsText(notification));
    }

    /** Stages the approved assignment template when the assignee has a mobile number. */
    private Uni<Void> enqueueAssignedWhatsapp(NotificationTransaction context, TaskNotification notification) {
        String mobileNumber = notification.assignee().getTelMobile();
        if (isAbsent(mobileNumber)) {
            return Uni.createFrom().voidItem();
        }
        return notificationPublisher.enqueueWhatsappTemplate(
                context,
                mobileNumber,
                ASSIGNED_TEMPLATE_NAME,
                WHATSAPP_LANGUAGE_CODE,
                assignedTemplateComponents(notification));
    }

    /** Stages the rendered completion email when the assignor has a company address. */
    private Uni<Void> enqueueCompletedEmail(NotificationTransaction context, TaskNotification notification) {
        String emailAddress = notification.assignor().getEmailCompany();
        if (isAbsent(emailAddress)) {
            return Uni.createFrom().voidItem();
        }
        return notificationPublisher.enqueueEmail(
                context, emailAddress, completionSubject(notification), buildCompletedEmailBody(notification));
    }

    /** Stages the completion SMS when the assignor has a mobile number. */
    private Uni<Void> enqueueCompletedSms(NotificationTransaction context, TaskNotification notification) {
        String mobileNumber = notification.assignor().getTelMobile();
        if (isAbsent(mobileNumber)) {
            return Uni.createFrom().voidItem();
        }
        return notificationPublisher.enqueueSms(context, mobileNumber, buildCompletedSmsText(notification));
    }

    /** Stages the approved completion template when the assignor has a mobile number. */
    private Uni<Void> enqueueCompletedWhatsapp(NotificationTransaction context, TaskNotification notification) {
        String mobileNumber = notification.assignor().getTelMobile();
        if (isAbsent(mobileNumber)) {
            return Uni.createFrom().voidItem();
        }
        return notificationPublisher.enqueueWhatsappTemplate(
                context,
                mobileNumber,
                COMPLETED_TEMPLATE_NAME,
                WHATSAPP_LANGUAGE_CODE,
                completedTemplateComponents(notification));
    }

    /** Builds the assignment email subject from the task title. */
    private String assignmentSubject(TaskNotification notification) {
        return "New Task Assigned: " + notification.task().getTaskTitle();
    }

    /** Builds the assignment email body with the shared task email template. */
    private String buildAssignedEmailBody(TaskNotification notification) {
        JobTask task = notification.task();
        String assignorName = notification.assignor() == null
                ? UNKNOWN_STAFF_NAME
                : notification.assignor().getName();
        return JobTaskEmailTemplate.buildAssignedEmail(new JobTaskEmailTemplate.AssignedEmailContent(
                notification.assignee().getName(),
                assignorName,
                task.getJobTaskId(),
                task.getTaskTitle(),
                task.getPriority(),
                resolveDueDate(task),
                task.getTaskDescription()));
    }

    /** Builds the assignment SMS text with the resolved due date. */
    private String buildAssignedSmsText(TaskNotification notification) {
        JobTask task = notification.task();
        return "New task assigned to you: " + task.getTaskTitle() + " (Due: " + resolveDueDate(task)
                + "). - AI Solutions";
    }

    /** Builds the approved assignment template's named body parameters. */
    private List<Map<String, Object>> assignedTemplateComponents(TaskNotification notification) {
        JobTask task = notification.task();
        return bodyParameters(List.of(
                namedParameter("assignee_name", notification.assignee().getName()),
                namedParameter("task_title", task.getTaskTitle()),
                namedParameter("job_task_id", task.getJobTaskId()),
                namedParameter("due_date", resolveDueDate(task))));
    }

    /** Builds the completion email subject from the task title. */
    private String completionSubject(TaskNotification notification) {
        return "Task Completed: " + notification.task().getTaskTitle();
    }

    /** Builds the completion email body with the shared task email template. */
    private String buildCompletedEmailBody(TaskNotification notification) {
        JobTask task = notification.task();
        String completedDate =
                task.getCompletedDate() == null ? "" : task.getCompletedDate().format(DATE_FORMAT);
        return JobTaskEmailTemplate.buildCompletedEmail(new JobTaskEmailTemplate.CompletedEmailContent(
                notification.assignor().getName(),
                resolveAssigneeName(notification),
                task.getJobTaskId(),
                task.getTaskTitle(),
                completedDate));
    }

    /** Builds the completion SMS text naming the assignee who finished the task. */
    private String buildCompletedSmsText(TaskNotification notification) {
        return resolveAssigneeName(notification) + " completed task: "
                + notification.task().getTaskTitle() + ". - AI Solutions";
    }

    /** Builds the approved completion template's named body parameters. */
    private List<Map<String, Object>> completedTemplateComponents(TaskNotification notification) {
        JobTask task = notification.task();
        return bodyParameters(List.of(
                namedParameter("assignor_name", notification.assignor().getName()),
                namedParameter("assignee_name", resolveAssigneeName(notification)),
                namedParameter("task_title", task.getTaskTitle()),
                namedParameter("job_task_id", task.getJobTaskId())));
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

    /** Wraps named parameters in the Meta body component shape. */
    private List<Map<String, Object>> bodyParameters(List<Map<String, Object>> parameters) {
        return List.of(Map.of("type", "body", "parameters", parameters));
    }

    /** Preserves the specification-defined parameter_name and text wire fields. */
    private Map<String, Object> namedParameter(String parameterName, String value) {
        return Map.of("type", "text", "parameter_name", parameterName, "text", value == null ? "" : value);
    }

    /** Reports whether a recipient detail is absent. */
    private boolean isAbsent(String value) {
        return value == null || value.isBlank();
    }
}
