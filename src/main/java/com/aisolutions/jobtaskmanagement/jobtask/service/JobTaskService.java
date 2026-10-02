package com.aisolutions.jobtaskmanagement.jobtask.service;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.*;
import com.aisolutions.jobtaskmanagement.util.DeviceInfo;
import io.smallrye.mutiny.Uni;

/**
 * Entry point for Job Task use cases.
 *
 * Delegates reads to {@link JobTaskQueryService}, creation to {@link JobTaskCreationService},
 * field edits to {@link JobTaskCommandService}, status transitions to {@link JobTaskStatusService},
 * reassignment to {@link JobTaskReassignmentService}, and rescheduling to
 * {@link JobTaskRescheduleService}.
 */
@ApplicationScoped
public class JobTaskService {

    @Inject
    JobTaskQueryService queryService;

    @Inject
    JobTaskCreationService creationService;

    @Inject
    JobTaskCommandService commandService;

    @Inject
    JobTaskStatusService statusService;

    @Inject
    JobTaskReassignmentService reassignmentService;

    @Inject
    JobTaskRescheduleService rescheduleService;

    /** Returns the assignor/assignee staff dropdown. */
    public Uni<List<StaffSummary>> listStaff() {
        return queryService.listStaff();
    }

    /** Lists the tasks visible to the caller under the RBAC rules. */
    public Uni<List<JobTaskResponse>> listWithRbac(String groupAuthority, String staffCode) {
        return queryService.listWithRbac(groupAuthority, staffCode);
    }

    /** Returns one active task. */
    public Uni<JobTaskResponse> findById(Long id) {
        return queryService.findById(id);
    }

    /** Creates a task and queues the assignment notification. */
    public Uni<JobTaskResponse> create(CreateJobTaskRequest request) {
        return creationService.create(request);
    }

    /** Applies the editable fields of an existing task. */
    public Uni<JobTaskResponse> update(Long id, UpdateJobTaskRequest request) {
        return commandService.update(id, request);
    }

    /** Updates a task's status and queues any completion notification. */
    public Uni<JobTaskResponse> updateStatus(Long id, UpdateStatusRequest request) {
        return statusService.updateStatus(id, request);
    }

    /** Reassigns a task to a new assignee. */
    public Uni<JobTaskResponse> reassign(Long id, ReassignRequest request, DeviceInfo deviceInfo) {
        return reassignmentService.reassign(id, request, deviceInfo);
    }

    /** Changes a task's due date. */
    public Uni<JobTaskResponse> reschedule(Long id, RescheduleRequest request, DeviceInfo deviceInfo) {
        return rescheduleService.reschedule(id, request, deviceInfo);
    }

    /** Records assignee progress remarks. */
    public Uni<JobTaskResponse> updateProgressRemarks(Long id, UpdateProgressRemarksRequest request) {
        return commandService.updateProgressRemarks(id, request);
    }

    /** Soft-deletes a task. */
    public Uni<Void> delete(Long id) {
        return commandService.delete(id);
    }
}
