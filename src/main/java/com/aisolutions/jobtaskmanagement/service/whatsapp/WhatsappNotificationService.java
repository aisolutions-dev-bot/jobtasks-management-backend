package com.aisolutions.jobtaskmanagement.service.whatsapp;

import com.aisolutions.shared.service.whatsapp.MetaWhatsappService;
import com.aisolutions.shared.service.whatsapp.TemplateComponent;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Sends Job Task WhatsApp notifications via the Meta WhatsApp Cloud API.
 *
 * Both templates below must exist and be APPROVED in Meta Business Manager before
 * sends succeed — otherwise Meta returns HTTP 404 / error code 132001 ("template not
 * found"), which is expected until the templates are created (see param tables below).
 *
 * Template: {@code jobtask_assigned_v1} (en) — body named params:
 *   assignee_name, task_title, job_task_id, due_date
 * Template: {@code jobtask_completed_v1} (en) — body named params:
 *   assignor_name, assignee_name, task_title, job_task_id
 */
@ApplicationScoped
public class WhatsappNotificationService {

    private static final Logger LOG = Logger.getLogger(WhatsappNotificationService.class);

    private static final String TASK_ASSIGNED_TEMPLATE_NAME = "jobtask_assigned_v1";
    private static final String TASK_ASSIGNED_LANGUAGE_CODE = "en";

    private static final String TASK_COMPLETED_TEMPLATE_NAME = "jobtask_completed_v1";
    private static final String TASK_COMPLETED_LANGUAGE_CODE = "en";

    @Inject
    MetaWhatsappService metaWhatsappService;

    @Inject
    Vertx vertx;

    /**
     * Sends the "task assigned" WhatsApp template to the assignee.
     *
     * Delegates to {@link #buildTaskAssignedComponents} for the template body params and
     * {@link #dispatchTemplateOnWorkerThread} for the actual blocking Meta API call.
     */
    public Uni<Boolean> sendTaskAssignedNotification(
            String recipientMobileNumber,
            String assigneeName,
            String taskTitle,
            String jobTaskId,
            String dueDate) {

        String normalizedMobile = normalizeMobileNumber(recipientMobileNumber);
        List<TemplateComponent> components = buildTaskAssignedComponents(assigneeName, taskTitle, jobTaskId, dueDate);

        LOG.infof("[WhatsApp] Sending %s — to=%s, assigneeName=%s, jobTaskId=%s",
                TASK_ASSIGNED_TEMPLATE_NAME, normalizedMobile, assigneeName, jobTaskId);

        return dispatchTemplateOnWorkerThread(
                normalizedMobile, TASK_ASSIGNED_TEMPLATE_NAME, TASK_ASSIGNED_LANGUAGE_CODE, components);
    }

    /** Builds the body named-parameters for the {@code jobtask_assigned_v1} template. */
    private List<TemplateComponent> buildTaskAssignedComponents(
            String assigneeName, String taskTitle, String jobTaskId, String dueDate) {
        return List.of(TemplateComponent.bodyNamed(
                new TemplateComponent.NamedParameter("assignee_name", assigneeName),
                new TemplateComponent.NamedParameter("task_title", taskTitle),
                new TemplateComponent.NamedParameter("job_task_id", jobTaskId),
                new TemplateComponent.NamedParameter("due_date", dueDate)
        ));
    }

    /**
     * Sends the "task completed" WhatsApp template to the assignor.
     *
     * Delegates to {@link #buildTaskCompletedComponents} for the template body params and
     * {@link #dispatchTemplateOnWorkerThread} for the actual blocking Meta API call.
     */
    public Uni<Boolean> sendTaskCompletedNotification(
            String recipientMobileNumber,
            String assignorName,
            String assigneeName,
            String taskTitle,
            String jobTaskId) {

        String normalizedMobile = normalizeMobileNumber(recipientMobileNumber);
        List<TemplateComponent> components = buildTaskCompletedComponents(assignorName, assigneeName, taskTitle, jobTaskId);

        LOG.infof("[WhatsApp] Sending %s — to=%s, assignorName=%s, assigneeName=%s, jobTaskId=%s",
                TASK_COMPLETED_TEMPLATE_NAME, normalizedMobile, assignorName, assigneeName, jobTaskId);

        return dispatchTemplateOnWorkerThread(
                normalizedMobile, TASK_COMPLETED_TEMPLATE_NAME, TASK_COMPLETED_LANGUAGE_CODE, components);
    }

    /** Builds the body named-parameters for the {@code jobtask_completed_v1} template. */
    private List<TemplateComponent> buildTaskCompletedComponents(
            String assignorName, String assigneeName, String taskTitle, String jobTaskId) {
        return List.of(TemplateComponent.bodyNamed(
                new TemplateComponent.NamedParameter("assignor_name", assignorName),
                new TemplateComponent.NamedParameter("assignee_name", assigneeName),
                new TemplateComponent.NamedParameter("task_title", taskTitle),
                new TemplateComponent.NamedParameter("job_task_id", jobTaskId)
        ));
    }

    /**
     * Runs the blocking {@code MetaWhatsappService.sendTemplate} call on a Vert.x worker
     * thread, swallowing and logging any failure so the caller always resolves.
     */
    private Uni<Boolean> dispatchTemplateOnWorkerThread(
            String normalizedMobile, String templateName, String languageCode, List<TemplateComponent> components) {
        return vertx.executeBlocking(
                Uni.createFrom().item(() -> {
                    metaWhatsappService.sendTemplate(normalizedMobile, templateName, languageCode, components);
                    return true;
                })
        )
                .onFailure().invoke(err -> LOG.errorf(err, "[WhatsApp] sendTemplate FAILED — to=%s, template=%s, cause=%s",
                        normalizedMobile, templateName, err.getMessage()))
                .onFailure().recoverWithItem(false);
    }

    /** Strips a leading {@code +} so Meta receives a plain E.164 digit string. */
    private String normalizeMobileNumber(String mobileNumber) {
        if (mobileNumber == null) {
            return null;
        }
        return mobileNumber.startsWith("+") ? mobileNumber.substring(1) : mobileNumber;
    }
}
