package com.aisolutions.jobtaskmanagement.jobtask.service;

import java.time.Year;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.*;
import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.entity.Staff;
import com.aisolutions.jobtaskmanagement.jobtask.mapper.JobTaskResponseMapper;
import com.aisolutions.jobtaskmanagement.jobtask.service.notification.JobTaskNotifier;
import com.aisolutions.jobtaskmanagement.jobtask.service.notification.JobTaskNotifier.TaskNotification;
import com.aisolutions.jobtaskmanagement.repository.JobTaskRepository;
import com.aisolutions.jobtaskmanagement.repository.StaffRepository;
import com.aisolutions.jobtaskmanagement.service.auth.AccessControlService;
import com.aisolutions.shared.notification.NotificationTransaction;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import com.aisolutions.shared.util.DateUtil;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Creates Job Tasks and queues the assignment notification in the same transaction.
 *
 * Delegates entity construction to {@link #buildNewJobTaskEntity(CreateJobTaskRequest)},
 * recipient lookup to {@link #createTaskWithResolvedStaff(NotificationTransaction, JobTask, CreateJobTaskRequest)},
 * and the atomic persist plus enqueue to
 * {@link #persistNewTaskAndNotifyAssignee(NotificationTransaction, JobTask, Staff, Staff)}.
 */
@ApplicationScoped
public class JobTaskCreationService {

    @Inject
    JobTaskRepository taskRepo;

    @Inject
    StaffRepository staffRepo;

    @Inject
    JobTaskNotifier jobTaskNotifier;

    @Inject
    CompanyPoolManager companyPoolManager;

    @Inject
    AccessControlService accessControlService;

    @Inject
    JobTaskResponseMapper viewAssembler;

    @ConfigProperty(name = "tenant.default-company-id", defaultValue = "db_test2")
    String defaultCompanyId;

    /** Creates a new job task and notifies the assignee once it is persisted. */
    public Uni<JobTaskResponse> create(CreateJobTaskRequest request) {
        JobTask task = buildNewJobTaskEntity(request);
        String companyId = accessControlService.getCurrentCompanyId();
        String notificationCompanyId = resolveNotificationCompanyId(companyId);
        return companyPoolManager
                .poolFor(companyId)
                .flatMap(pool -> pool.withTransaction(transaction -> createTaskWithResolvedStaff(
                        new NotificationTransaction(transaction, notificationCompanyId), task, request)));
    }

    /** Resolves both staff rows sequentially before persisting the task. */
    private Uni<JobTaskResponse> createTaskWithResolvedStaff(
            NotificationTransaction context, JobTask task, CreateJobTaskRequest request) {
        return staffRepo
                .findByStaffId(context.transaction(), request.getAssignorStaffId())
                .flatMap(assignor -> staffRepo
                        .findByStaffId(context.transaction(), request.getAssigneeStaffId())
                        .flatMap(assignee -> persistNewTaskAndNotifyAssignee(context, task, assignor, assignee)));
    }

    /** Maps a create request into a new, unsaved {@link JobTask} entity with a temporary code. */
    private JobTask buildNewJobTaskEntity(CreateJobTaskRequest req) {
        JobTask task = new JobTask();
        task.setTaskTitle(req.getTaskTitle() != null ? req.getTaskTitle().trim() : "");
        task.setTaskType(req.getTaskType());
        task.setTaskDescription(req.getTaskDescription());
        task.setAssignorStaffId(req.getAssignorStaffId());
        task.setAssigneeStaffId(req.getAssigneeStaffId());
        task.setPriority(req.getPriority() != null ? req.getPriority() : "Medium");
        task.setJobStatus("Pending");
        task.setDueDate(req.getDueDate() != null ? req.getDueDate().atStartOfDay() : null);
        task.setEstimatedHours(req.getEstimatedHours());
        task.setEntryStaff(req.getEntryStaff() != null ? req.getEntryStaff() : "SYSTEM");
        task.setEntryDate(DateUtil.nowSGT());
        task.setJobTaskId("JT-TEMP-" + (System.currentTimeMillis() % 99999));
        return task;
    }

    /** Delegates code assignment and the transactional enqueue before mapping the response. */
    private Uni<JobTaskResponse> persistNewTaskAndNotifyAssignee(
            NotificationTransaction context, JobTask task, Staff assignor, Staff assignee) {
        return taskRepo.insert(context.transaction(), task)
                .flatMap(saved -> assignGeneratedJobTaskCode(context.transaction(), saved))
                .call(updated ->
                        jobTaskNotifier.notifyTaskAssigned(context, new TaskNotification(updated, assignee, assignor)))
                .map(updated -> viewAssembler.toResponse(updated, assignor, assignee));
    }

    /** Replaces the temporary code with the sequential {@code JT-<year>-<uniqId>} code and flushes it. */
    private Uni<JobTask> assignGeneratedJobTaskCode(SqlClient client, JobTask saved) {
        saved.setJobTaskId(String.format("JT-%d-%04d", Year.now().getValue(), saved.getUniqId()));
        return taskRepo.update(client, saved);
    }

    /** Uses the request's company claim, defaulting to the main database when it is absent. */
    private String resolveNotificationCompanyId(String companyId) {
        return companyId == null || companyId.isBlank() ? defaultCompanyId : companyId;
    }
}
