package com.aisolutions.jobtaskmanagement.jobtask.service;

import java.time.LocalDate;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import com.aisolutions.jobtaskmanagement.common.notification.NotificationTransaction;
import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.*;
import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.jobtask.mapper.JobTaskResponseMapper;
import com.aisolutions.jobtaskmanagement.jobtask.service.JobTaskNotificationService.TaskNotification;
import com.aisolutions.jobtaskmanagement.repository.JobTaskRepository;
import com.aisolutions.jobtaskmanagement.repository.StaffRepository;
import com.aisolutions.jobtaskmanagement.service.auth.AccessControlService;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import com.aisolutions.shared.util.DateUtil;
import io.smallrye.mutiny.Uni;

/**
 * Applies Job Task status transitions and queues completion notifications atomically.
 *
 * Delegates field mutation to {@link #applyStatusFieldChanges(JobTask, UpdateStatusRequest)},
 * completion recipients to {@link #enrichAndNotifyOnCompletion(NotificationTransaction, JobTask, String)},
 * and response mapping to {@link JobTaskResponseMapper}.
 */
@ApplicationScoped
public class JobTaskStatusService {

    @Inject
    JobTaskRepository taskRepo;

    @Inject
    StaffRepository staffRepo;

    @Inject
    JobTaskNotificationService notificationService;

    @Inject
    CompanyPoolManager companyPoolManager;

    @Inject
    AccessControlService accessControlService;

    @Inject
    JobTaskResponseMapper viewAssembler;

    /** Updates a task's status, adjusting started and completed dates for the new status. */
    public Uni<JobTaskResponse> updateStatus(Long id, UpdateStatusRequest request) {
        String companyId = accessControlService.getCurrentCompanyId();
        return companyPoolManager
                .poolFor(companyId)
                .flatMap(pool -> pool.withTransaction(transaction ->
                        updateTaskStatus(new NotificationTransaction(transaction, companyId), id, request)));
    }

    /** Loads the task and delegates status mutation to saveTaskStatus. */
    private Uni<JobTaskResponse> updateTaskStatus(
            NotificationTransaction context, Long taskId, UpdateStatusRequest request) {
        return taskRepo.findActiveById(context.transaction(), taskId)
                .onItem()
                .ifNull()
                .failWith(() -> new NotFoundException("Task " + taskId + " not found"))
                .flatMap(task -> saveTaskStatus(context, task, request));
    }

    /** Applies field changes before saving and delegating completion notification preparation. */
    private Uni<JobTaskResponse> saveTaskStatus(
            NotificationTransaction context, JobTask task, UpdateStatusRequest request) {
        applyStatusFieldChanges(task, request);
        return taskRepo.update(context.transaction(), task)
                .flatMap(saved -> enrichAndNotifyOnCompletion(context, saved, request.getJobStatus()));
    }

    /** Mutates started and completed dates plus the status and audit fields for the transition. */
    private void applyStatusFieldChanges(JobTask task, UpdateStatusRequest req) {
        switch (req.getJobStatus()) {
            case "In Progress" -> markStartedIfNotAlready(task, req.getStartedDate());
            case "Completed" -> markCompleted(task, req.getStartedDate(), req.getCompletedDate());
            case "Pending", "On Hold" -> task.setCompletedDate(null);
            case "Closed" -> {
                /* closing an already-completed task — keep both dates as-is */
            }
        }
        task.setJobStatus(req.getJobStatus());
        task.setLastEditStaff(req.getLastEditStaff());
        task.setLastEdtiDate(DateUtil.nowSGT());
    }

    /** Sets the started date from the request when provided, otherwise now, only if unset. */
    private void markStartedIfNotAlready(JobTask task, LocalDate requestedStartedDate) {
        if (requestedStartedDate != null && task.getStartedDate() == null) {
            task.setStartedDate(requestedStartedDate.atStartOfDay());
        } else if (task.getStartedDate() == null) {
            task.setStartedDate(DateUtil.nowSGT());
        }
    }

    /** Sets the started date (if missing) and the completed date for a transition to "Completed". */
    private void markCompleted(JobTask task, LocalDate requestedStartedDate, LocalDate requestedCompletedDate) {
        markStartedIfNotAlready(task, requestedStartedDate);
        task.setCompletedDate(
                requestedCompletedDate != null ? requestedCompletedDate.atStartOfDay() : DateUtil.nowSGT());
    }

    /** Resolves recipients and delegates the transactional completion enqueue before mapping. */
    private Uni<JobTaskResponse> enrichAndNotifyOnCompletion(
            NotificationTransaction context, JobTask task, String newStatus) {
        return staffRepo
                .findByStaffId(context.transaction(), task.getAssignorStaffId())
                .flatMap(assignor -> staffRepo
                        .findByStaffId(context.transaction(), task.getAssigneeStaffId())
                        .call(assignee -> queueCompletionIfRequested(
                                context, new TaskNotification(task, assignee, assignor), newStatus))
                        .map(assignee -> viewAssembler.toResponse(task, assignor, assignee)));
    }

    /** Queues completion within the transaction when the requested status is Completed. */
    private Uni<Void> queueCompletionIfRequested(
            NotificationTransaction context, TaskNotification notification, String newStatus) {
        return "Completed".equals(newStatus)
                ? notificationService.notifyTaskCompleted(context, notification)
                : Uni.createFrom().voidItem();
    }
}
