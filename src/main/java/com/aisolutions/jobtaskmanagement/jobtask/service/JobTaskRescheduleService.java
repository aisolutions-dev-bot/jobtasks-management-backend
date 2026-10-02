package com.aisolutions.jobtaskmanagement.jobtask.service;

import java.time.LocalDate;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.*;
import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.jobtask.mapper.JobTaskResponseMapper;
import com.aisolutions.jobtaskmanagement.jobtask.service.JobTaskAuditService.JobTaskAuditChange;
import com.aisolutions.jobtaskmanagement.jobtask.service.JobTaskAuditService.JobTaskAuditContext;
import com.aisolutions.jobtaskmanagement.repository.JobTaskRepository;
import com.aisolutions.jobtaskmanagement.service.auth.AccessControlService;
import com.aisolutions.jobtaskmanagement.util.DeviceInfo;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import com.aisolutions.shared.util.DateUtil;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.SqlClient;

/**
 * Changes a Job Task due date and records the change in the audit log.
 *
 * Delegates task loading to {@link #loadActiveTask(SqlClient, Long)}, audit persistence to
 * {@link JobTaskAuditService}, and response assembly to {@link JobTaskResponseMapper}.
 */
@ApplicationScoped
public class JobTaskRescheduleService {

    /** Groups the audit identity and values for a single reschedule entry. */
    private record RescheduleAudit(JobTaskAuditContext context, JobTaskAuditChange change) {}

    @Inject
    JobTaskRepository taskRepo;

    @Inject
    JobTaskAuditService auditService;

    @Inject
    JobTaskResponseMapper viewMapper;

    @Inject
    CompanyPoolManager companyPoolManager;

    @Inject
    AccessControlService accessControlService;

    /** Resolves the company-routed pool for the current request. */
    private Uni<Pool> currentPool() {
        return companyPoolManager.poolFor(accessControlService.getCurrentCompanyId());
    }

    /** Reschedules a task and writes the audit entry in the same transaction. */
    public Uni<JobTaskResponse> reschedule(Long id, RescheduleRequest request, DeviceInfo deviceInfo) {
        return currentPool()
                .flatMap(pool -> pool.withTransaction(client -> loadActiveTask(client, id)
                        .flatMap(task -> applyReschedule(client, task, request, deviceInfo))));
    }

    /** Fails the chain when the referenced task is missing or already void. */
    private Uni<JobTask> loadActiveTask(SqlClient client, Long id) {
        return taskRepo.findActiveById(client, id)
                .onItem()
                .ifNull()
                .failWith(() -> new NotFoundException("Task " + id + " not found"));
    }

    /** Applies the new due date, saves, then writes the audit entry and maps the response. */
    private Uni<JobTaskResponse> applyReschedule(
            SqlClient client, JobTask task, RescheduleRequest request, DeviceInfo deviceInfo) {
        String previousDueDate = describeDueDate(
                task.getDueDate() == null ? null : task.getDueDate().toLocalDate());
        String updatedDueDate = describeDueDate(request.getNewDueDate());
        task.setDueDate(
                request.getNewDueDate() != null ? request.getNewDueDate().atStartOfDay() : null);
        task.setLastEditStaff(request.getLastEditStaff());
        task.setLastEdtiDate(DateUtil.nowSGT());
        RescheduleAudit audit = new RescheduleAudit(
                new JobTaskAuditContext(deviceInfo, request.getLastEditStaff()),
                new JobTaskAuditChange(task.getJobTaskId(), previousDueDate, updatedDueDate));
        return taskRepo.update(client, task).flatMap(saved -> recordRescheduleAndMap(client, saved, audit));
    }

    /** Writes the RESCHEDULE audit entry and maps the saved task to the API response. */
    private Uni<JobTaskResponse> recordRescheduleAndMap(SqlClient client, JobTask saved, RescheduleAudit audit) {
        return auditService
                .logReschedule(client, audit.context(), audit.change())
                .flatMap(ignored -> viewMapper.enrichOne(client, saved));
    }

    /** Renders a due date for audit remarks, using "none" when the value is absent. */
    private String describeDueDate(LocalDate dueDate) {
        return dueDate == null ? "none" : dueDate.toString();
    }
}
