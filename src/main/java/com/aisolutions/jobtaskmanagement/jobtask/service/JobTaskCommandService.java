package com.aisolutions.jobtaskmanagement.jobtask.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.*;
import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.jobtask.mapper.JobTaskResponseMapper;
import com.aisolutions.jobtaskmanagement.repository.JobTaskRepository;
import com.aisolutions.jobtaskmanagement.service.auth.AccessControlService;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import com.aisolutions.shared.util.DateUtil;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.SqlClient;

/**
 * Field edits on an existing Job Task.
 *
 * Delegates the company-routed transaction to {@link #currentPool()}, task loading to
 * {@link #loadActiveTask(SqlClient, Long)}, and response assembly to {@link JobTaskResponseMapper}.
 */
@ApplicationScoped
public class JobTaskCommandService {

    @Inject
    JobTaskRepository taskRepo;

    @Inject
    CompanyPoolManager companyPoolManager;

    @Inject
    AccessControlService accessControlService;

    @Inject
    JobTaskResponseMapper viewAssembler;

    /** Resolves the company-routed pool for the current request. */
    private Uni<Pool> currentPool() {
        return companyPoolManager.poolFor(accessControlService.getCurrentCompanyId());
    }

    /** Fails the chain when the referenced task is missing or already void. */
    private Uni<JobTask> loadActiveTask(SqlClient client, Long id) {
        return taskRepo.findActiveById(client, id)
                .onItem()
                .ifNull()
                .failWith(() -> new NotFoundException("Task " + id + " not found"));
    }

    /** Applies the editable task fields and returns the enriched response. */
    public Uni<JobTaskResponse> update(Long id, UpdateJobTaskRequest request) {
        return currentPool()
                .flatMap(pool -> pool.withTransaction(
                        client -> loadActiveTask(client, id).flatMap(task -> applyUpdate(client, task, request))));
    }

    /** Mutates the editable fields, saves, and delegates response assembly. */
    private Uni<JobTaskResponse> applyUpdate(SqlClient client, JobTask task, UpdateJobTaskRequest request) {
        task.setTaskTitle(request.getTaskTitle().trim());
        task.setTaskType(request.getTaskType());
        task.setTaskDescription(request.getTaskDescription());
        task.setAssigneeStaffId(request.getAssigneeStaffId());
        task.setPriority(request.getPriority());
        task.setDueDate(request.getDueDate() != null ? request.getDueDate().atStartOfDay() : null);
        task.setEstimatedHours(request.getEstimatedHours());
        task.setActualHours(request.getActualHours());
        task.setRemarks(request.getRemarks());
        task.setLastEditStaff(request.getLastEditStaff());
        task.setLastEdtiDate(DateUtil.nowSGT());
        return taskRepo.update(client, task).flatMap(saved -> viewAssembler.enrichOne(client, saved));
    }

    /** Records assignee progress remarks and returns the enriched response. */
    public Uni<JobTaskResponse> updateProgressRemarks(Long id, UpdateProgressRemarksRequest request) {
        return currentPool()
                .flatMap(pool -> pool.withTransaction(client ->
                        loadActiveTask(client, id).flatMap(task -> applyProgressRemarks(client, task, request))));
    }

    /** Mutates progress remarks, saves, and delegates response assembly. */
    private Uni<JobTaskResponse> applyProgressRemarks(
            SqlClient client, JobTask task, UpdateProgressRemarksRequest request) {
        task.setProgressRemarks(request.getProgressRemarks());
        task.setLastEditStaff(request.getLastEditStaff());
        task.setLastEdtiDate(DateUtil.nowSGT());
        return taskRepo.update(client, task).flatMap(saved -> viewAssembler.enrichOne(client, saved));
    }

    /** Soft-deletes a task by moving it to the Void status. */
    public Uni<Void> delete(Long id) {
        return currentPool()
                .flatMap(pool -> pool.withTransaction(
                        client -> loadActiveTask(client, id).flatMap(task -> applySoftDelete(client, task))));
    }

    /** Sets the audit fields for a soft delete and persists the voided task. */
    private Uni<Void> applySoftDelete(SqlClient client, JobTask task) {
        task.setJobStatus("Void");
        task.setLastEdtiDate(DateUtil.nowSGT());
        return taskRepo.update(client, task).replaceWithVoid();
    }
}
