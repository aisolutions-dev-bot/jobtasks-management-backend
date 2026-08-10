package com.aisolutions.jobtaskmanagement.service.jobtask;

import com.aisolutions.jobtaskmanagement.dto.NotificationConfigDTO;
import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.entity.Staff;
import com.aisolutions.jobtaskmanagement.service.email.EmailNotificationService;
import com.aisolutions.jobtaskmanagement.service.notificationconfig.NotificationConfigService;
import com.aisolutions.jobtaskmanagement.service.sms.SmsNotificationService;
import com.aisolutions.jobtaskmanagement.service.whatsapp.WhatsappNotificationService;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.format.DateTimeFormatter;

/**
 * Fires Email/SMS/WhatsApp notifications for Job Task assignment and completion.
 *
 * Every send is gated by the org-api notification toggles (NOTIFICATION-EMAIL/
 * -SMS/-WHATSAPP) and by the recipient having the relevant contact info. All
 * failures are swallowed here — the caller invokes these fire-and-forget so a
 * notification outage never affects the task create/update transaction.
 */
@ApplicationScoped
public class JobTaskNotificationService {

    private static final Logger LOG = Logger.getLogger(JobTaskNotificationService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    @Inject
    NotificationConfigService notificationConfigService;

    @Inject
    EmailNotificationService emailNotificationService;

    @Inject
    SmsNotificationService smsNotificationService;

    @Inject
    WhatsappNotificationService whatsappNotificationService;

    /**
     * Notifies the assignee that a task has been assigned to them.
     *
     * Delegates to {@link #dispatchAssignedNotificationsAcrossChannels} for the actual
     * per-channel gated sends, once the current toggle config is fetched.
     */
    public Uni<Void> notifyTaskAssigned(JobTask task, Staff assignee, Staff assignor) {
        if (assignee == null) {
            LOG.warnf("[JobTaskNotification] Skipping assigned notification — no assignee resolved for task %s", task.getJobTaskId());
            return Uni.createFrom().voidItem();
        }

        return notificationConfigService.getConfig()
                .flatMap(config -> dispatchAssignedNotificationsAcrossChannels(config, task, assignee, assignor))
                .onFailure().invoke(err -> LOG.errorf(err, "[JobTaskNotification] notifyTaskAssigned failed for task %s: %s",
                        task.getJobTaskId(), err.getMessage()))
                .onFailure().recoverWithItem((Void) null);
    }

    /** Fires the assigned-task Email/SMS/WhatsApp sends in parallel, each individually toggle-gated. */
    private Uni<Void> dispatchAssignedNotificationsAcrossChannels(
            NotificationConfigDTO config, JobTask task, Staff assignee, Staff assignor) {
        String assignorName = assignor != null ? assignor.getName() : "Unknown";
        String dueDate = task.getDueDate() != null ? task.getDueDate().format(DATE_FMT) : "No due date";

        return Uni.combine().all().unis(
                sendEmailIfEnabled(config, assignee.getEmailCompany(),
                        "New Task Assigned: " + task.getTaskTitle(),
                        assignedEmailBody(task, assignee, assignorName, dueDate)),
                sendSmsIfEnabled(config, assignee.getTelMobile(),
                        "New task assigned to you: " + task.getTaskTitle() + " (Due: " + dueDate + "). - AI Solutions"),
                sendWhatsappAssignedIfEnabled(config, assignee.getTelMobile(),
                        assignee.getName(), task.getTaskTitle(), task.getJobTaskId(), dueDate)
        ).discardItems();
    }

    /**
     * Notifies the assignor that the assignee has completed the task.
     *
     * Delegates to {@link #dispatchCompletedNotificationsAcrossChannels} for the actual
     * per-channel gated sends, once the current toggle config is fetched.
     */
    public Uni<Void> notifyTaskCompleted(JobTask task, Staff assignor, Staff assignee) {
        if (assignor == null) {
            LOG.warnf("[JobTaskNotification] Skipping completed notification — no assignor resolved for task %s", task.getJobTaskId());
            return Uni.createFrom().voidItem();
        }

        return notificationConfigService.getConfig()
                .flatMap(config -> dispatchCompletedNotificationsAcrossChannels(config, task, assignor, assignee))
                .onFailure().invoke(err -> LOG.errorf(err, "[JobTaskNotification] notifyTaskCompleted failed for task %s: %s",
                        task.getJobTaskId(), err.getMessage()))
                .onFailure().recoverWithItem((Void) null);
    }

    /** Fires the completed-task Email/SMS/WhatsApp sends in parallel, each individually toggle-gated. */
    private Uni<Void> dispatchCompletedNotificationsAcrossChannels(
            NotificationConfigDTO config, JobTask task, Staff assignor, Staff assignee) {
        String assigneeName = assignee != null ? assignee.getName() : "Unknown";

        return Uni.combine().all().unis(
                sendEmailIfEnabled(config, assignor.getEmailCompany(),
                        "Task Completed: " + task.getTaskTitle(),
                        completedEmailBody(task, assignor, assigneeName)),
                sendSmsIfEnabled(config, assignor.getTelMobile(),
                        assigneeName + " completed task: " + task.getTaskTitle() + ". - AI Solutions"),
                sendWhatsappCompletedIfEnabled(config, assignor.getTelMobile(),
                        assignor.getName(), assigneeName, task.getTaskTitle(), task.getJobTaskId())
        ).discardItems();
    }

    // ─── Channel helpers ────────────────────────────────────────────────────

    private Uni<Boolean> sendEmailIfEnabled(NotificationConfigDTO config, String email, String subject, String body) {
        if (!config.isEmailEnabled() || email == null || email.isBlank()) {
            return Uni.createFrom().item(false);
        }
        return emailNotificationService.sendReactive(email, subject, body);
    }

    private Uni<Boolean> sendSmsIfEnabled(NotificationConfigDTO config, String mobile, String text) {
        if (!config.isSmsEnabled() || mobile == null || mobile.isBlank()) {
            return Uni.createFrom().item(false);
        }
        return smsNotificationService.sendReactive(mobile, text);
    }

    private Uni<Boolean> sendWhatsappAssignedIfEnabled(NotificationConfigDTO config, String mobile,
            String assigneeName, String taskTitle, String jobTaskId, String dueDate) {
        if (!config.isWhatsappEnabled() || mobile == null || mobile.isBlank()) {
            return Uni.createFrom().item(false);
        }
        return whatsappNotificationService.sendTaskAssignedNotification(mobile, assigneeName, taskTitle, jobTaskId, dueDate);
    }

    private Uni<Boolean> sendWhatsappCompletedIfEnabled(NotificationConfigDTO config, String mobile,
            String assignorName, String assigneeName, String taskTitle, String jobTaskId) {
        if (!config.isWhatsappEnabled() || mobile == null || mobile.isBlank()) {
            return Uni.createFrom().item(false);
        }
        return whatsappNotificationService.sendTaskCompletedNotification(mobile, assignorName, assigneeName, taskTitle, jobTaskId);
    }

    // ─── Email content ──────────────────────────────────────────────────────

    /** Builds the "task assigned" HTML email body via {@link JobTaskEmailTemplate}. */
    private String assignedEmailBody(JobTask task, Staff assignee, String assignorName, String dueDate) {
        return JobTaskEmailTemplate.buildAssignedEmail(
                assignee.getName(), assignorName, task.getJobTaskId(), task.getTaskTitle(),
                task.getPriority(), dueDate, task.getTaskDescription());
    }

    /** Builds the "task completed" HTML email body via {@link JobTaskEmailTemplate}. */
    private String completedEmailBody(JobTask task, Staff assignor, String assigneeName) {
        String completedDate = task.getCompletedDate() != null ? task.getCompletedDate().format(DATE_FMT) : "";
        return JobTaskEmailTemplate.buildCompletedEmail(
                assignor.getName(), assigneeName, task.getJobTaskId(), task.getTaskTitle(), completedDate);
    }
}
