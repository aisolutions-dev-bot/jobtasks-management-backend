package com.aisolutions.jobtaskmanagement.jobtask.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.*;
import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.jobtask.mapper.JobTaskResponseMapper;
import com.aisolutions.jobtaskmanagement.jobtask.service.JobTaskAuditService.JobTaskAuditChange;
import com.aisolutions.jobtaskmanagement.jobtask.service.JobTaskAuditService.JobTaskAuditContext;
import com.aisolutions.jobtaskmanagement.jobtask.service.notification.JobTaskNotifier;
import com.aisolutions.jobtaskmanagement.jobtask.service.notification.JobTaskNotifier.TaskNotification;
import com.aisolutions.jobtaskmanagement.repository.JobTaskRepository;
import com.aisolutions.jobtaskmanagement.repository.StaffRepository;
import com.aisolutions.jobtaskmanagement.service.auth.AccessControlService;
import com.aisolutions.jobtaskmanagement.util.DeviceInfo;
import com.aisolutions.shared.notification.NotificationTransaction;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import com.aisolutions.shared.tenancy.DefaultTenantCompanyId;
import com.aisolutions.shared.util.DateUtil;
import io.smallrye.mutiny.Uni;

/**
 * Reassigns a Job Task to a new assignee and notifies that assignee.
 *
 * Delegates field and audit mutation to
 * {@link #saveReassignment(NotificationTransaction, JobTask, ReassignmentRequest)}, audit
 * persistence to {@link JobTaskAuditService}, and the notification enqueue to
 * {@link JobTaskNotifier}.
 */
@ApplicationScoped
public class JobTaskReassignmentService {

    @Inject
    JobTaskRepository taskRepo;

    @Inject
    StaffRepository staffRepo;

    @Inject
    JobTaskNotifier jobTaskNotifier;

    @Inject
    JobTaskAuditService auditLogger;

    @Inject
    CompanyPoolManager companyPoolManager;

    @Inject
    AccessControlService accessControlService;

    @Inject
    JobTaskResponseMapper viewAssembler;

    @Inject
    @DefaultTenantCompanyId
    String defaultCompanyId;

    /** Groups reassignment input and audit context without expanding helper signatures. */
    private record ReassignmentRequest(
            ReassignRequest request, String previousAssigneeStaffId, DeviceInfo deviceInfo) {}

    /** Reassigns a task to a new assignee, logs the change, and notifies the new assignee. */
    public Uni<JobTaskResponse> reassign(Long id, ReassignRequest request, DeviceInfo deviceInfo) {
        String companyId = accessControlService.getCurrentCompanyId();
        String notificationCompanyId = resolveNotificationCompanyId(companyId);
        ReassignmentRequest reassignment = new ReassignmentRequest(request, null, deviceInfo);
        return companyPoolManager
                .poolFor(companyId)
                .flatMap(pool -> pool.withTransaction(transaction -> reassignTaskWithinTransaction(
                        new NotificationTransaction(transaction, notificationCompanyId), id, reassignment)));
    }

    /** Loads the task and delegates reassignment fields and side effects to saveReassignment. */
    private Uni<JobTaskResponse> reassignTaskWithinTransaction(
            NotificationTransaction context, Long taskId, ReassignmentRequest reassignment) {
        return taskRepo.findActiveById(context.transaction(), taskId)
                .onItem()
                .ifNull()
                .failWith(() -> new NotFoundException("Task " + taskId + " not found"))
                .flatMap(task -> saveReassignment(context, task, reassignment));
    }

    /** Captures the prior assignee and delegates atomic audit and notification persistence. */
    private Uni<JobTaskResponse> saveReassignment(
            NotificationTransaction context, JobTask task, ReassignmentRequest reassignment) {
        String previousAssigneeStaffId = task.getAssigneeStaffId();
        applyReassignmentFieldChanges(task, reassignment.request());
        return taskRepo.update(context.transaction(), task)
                .flatMap(saved ->
                        logReassignmentAndNotifyNewAssignee(context, saved, previousAssigneeStaffId, reassignment));
    }

    /** Mutates the assignee and audit fields for a reassignment. */
    private void applyReassignmentFieldChanges(JobTask task, ReassignRequest request) {
        task.setAssigneeStaffId(request.getNewAssigneeStaffId());
        task.setLastEditStaff(request.getLastEditStaff());
        task.setLastEdtiDate(DateUtil.nowSGT());
    }

    /** Writes the audit entry, resolves recipients, and delegates assignment enqueue. */
    private Uni<JobTaskResponse> logReassignmentAndNotifyNewAssignee(
            NotificationTransaction context,
            JobTask task,
            String previousAssigneeStaffId,
            ReassignmentRequest reassignment) {
        ReassignRequest request = reassignment.request();
        JobTaskAuditContext auditContext =
                new JobTaskAuditContext(reassignment.deviceInfo(), request.getLastEditStaff());
        JobTaskAuditChange auditChange =
                new JobTaskAuditChange(task.getJobTaskId(), previousAssigneeStaffId, request.getNewAssigneeStaffId());
        return auditLogger
                .logReassignment(context.transaction(), auditContext, auditChange)
                .flatMap(ignored -> notifyNewAssignee(context, task));
    }

    /** Resolves the assignor and new assignee and queues the assignment notification. */
    private Uni<JobTaskResponse> notifyNewAssignee(NotificationTransaction context, JobTask task) {
        return staffRepo
                .findByStaffId(context.transaction(), task.getAssignorStaffId())
                .flatMap(assignor -> staffRepo
                        .findByStaffId(context.transaction(), task.getAssigneeStaffId())
                        .call(newAssignee -> jobTaskNotifier.notifyTaskAssigned(
                                context, new TaskNotification(task, newAssignee, assignor)))
                        .map(newAssignee -> viewAssembler.toResponse(task, assignor, newAssignee)));
    }

    /** Uses the request's company claim, defaulting to the main database when it is absent. */
    private String resolveNotificationCompanyId(String companyId) {
        return companyId == null || companyId.isBlank() ? defaultCompanyId : companyId;
    }
}
