package com.aisolutions.jobtaskmanagement.service;

import com.aisolutions.jobtaskmanagement.client.GroupAuthorityAccessClient;
import com.aisolutions.jobtaskmanagement.dto.GroupAuthorityAccessDTO;
import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.*;
import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.entity.Staff;
import com.aisolutions.jobtaskmanagement.repository.JobTaskRepository;
import com.aisolutions.jobtaskmanagement.repository.StaffRepository;
import com.aisolutions.jobtaskmanagement.repository.UserActionLogRepository;
import com.aisolutions.jobtaskmanagement.service.jobtask.JobTaskNotificationService;
import com.aisolutions.jobtaskmanagement.util.DeviceInfo;
import com.aisolutions.shared.util.DateUtil;

import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Business logic for Job Task management.
 *
 * RBAC visibility (GET /api/v1/job-tasks):
 *   a2401.02 = true  → ALL records
 *   a2401.02 = false AND a2401.01 = true  → same department as current user
 *   a2401.02 = false AND a2401.01 = false → only tasks where I am assignor OR assignee
 *
 * Query params: groupAuthority (user's group), staffCode (user's StaffId varchar)
 */
@ApplicationScoped
public class JobTaskService {

    private static final Logger LOG = Logger.getLogger(JobTaskService.class);

    private static final String MODULE_ID       = "mod24";
    private static final String ACCESS_VIEW_ALL  = "a2401.02";
    private static final String ACCESS_VIEW_DEPT = "a2401.01";

    @Inject
    JobTaskRepository taskRepo;

    @Inject
    StaffRepository staffRepo;

    @Inject
    UserActionLogRepository logRepo;

    @Inject
    JobTaskNotificationService notificationService;

    @Inject
    @RestClient
    GroupAuthorityAccessClient accessClient;

    // ─── Staff dropdown ───────────────────────────────────────────────────────

    @WithSession
    public Uni<List<StaffSummary>> listStaff() {
        return getCachedStaffDropdown()
                .map(list -> list.stream().map(this::toStaffSummary).collect(Collectors.toList()));
    }

    /** Staff directory for assignor/assignee enrichment — rarely changes. */
    @CacheResult(cacheName = "jobtasks-staff-list")
    public Uni<List<Staff>> getCachedStaffList() {
        return staffRepo.findAllOrdered();
    }

    /** Assignor/assignee dropdown — short TTL so new staff are assignable quickly. */
    @CacheResult(cacheName = "jobtasks-staff-dropdown")
    public Uni<List<Staff>> getCachedStaffDropdown() {
        return staffRepo.findAllOrdered();
    }

    /** RBAC access codes per groupAuthority — rarely change. */
    @CacheResult(cacheName = "jobtasks-rbac-access")
    public Uni<List<GroupAuthorityAccessDTO>> getCachedAccess(@CacheKey String groupAuthority) {
        return accessClient.getAccessByModule(groupAuthority, MODULE_ID)
                .onFailure().recoverWithItem(e -> {
                    LOG.warnf("RBAC fetch failed: %s", e.getMessage());
                    return List.of();
                });
    }

    // ─── List with RBAC ───────────────────────────────────────────────────────

    @WithSession
    public Uni<List<JobTaskResponse>> listWithRbac(String groupAuthority, String staffCode) {

        Uni<List<GroupAuthorityAccessDTO>> accessUni =
                (groupAuthority != null && !groupAuthority.isBlank())
                        ? getCachedAccess(groupAuthority)
                        : Uni.createFrom().item(List.of());

        // Current user's own record is looked up directly (not cached) so RBAC
        // department resolution reflects changes immediately.
        Uni<Staff> staffUni =
                (staffCode != null && !staffCode.isBlank())
                        ? staffRepo.findByStaffId(staffCode)
                                   .onFailure().recoverWithNull()
                        : Uni.createFrom().nullItem();

        // Sequential chain — avoid Uni.combine parallel query conflict on reactive MySQL
        return accessUni.flatMap(accesses ->
                staffUni.flatMap(staff -> {
                    boolean viewAll  = hasAccess(accesses, ACCESS_VIEW_ALL);
                    boolean viewDept = hasAccess(accesses, ACCESS_VIEW_DEPT);

                    Uni<List<JobTask>> tasksUni;
                    if (viewAll) {
                        tasksUni = taskRepo.findAllActive();
                    } else if (viewDept && staff != null && staff.getDepartment() != null) {
                        tasksUni = taskRepo.findByDepartment(staff.getDepartment());
                    } else if (staff != null) {
                        tasksUni = taskRepo.findByStaffId(staff.getStaffId());
                    } else {
                        // Fail closed: unresolved staff identity must not see all tasks.
                        LOG.warnf("listWithRbac: could not resolve staff for staffCode='%s' — returning empty list", staffCode);
                        tasksUni = Uni.createFrom().item(List.of());
                    }

                    return tasksUni.flatMap(tasks -> enrichWithStaff(tasks));
                }));
    }

    // ─── Single task ──────────────────────────────────────────────────────────

    @WithSession
    public Uni<JobTaskResponse> findById(Long id) {
        return taskRepo.findActiveById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Task " + id + " not found"))
                .flatMap(task -> enrichSingle(task));
    }

    // ─── Create ───────────────────────────────────────────────────────────────

    /**
     * Creates a new job task and notifies the assignee once it's persisted.
     *
     * Delegates to {@link #buildNewJobTaskEntity} for entity construction and
     * {@link #persistNewTaskAndNotifyAssignee} for the persist + notify chain.
     */
    @WithTransaction
    public Uni<JobTaskResponse> create(CreateJobTaskRequest req) {
        JobTask task = buildNewJobTaskEntity(req);

        // Sequential reactive chain — Vert.x MySQL client cannot handle parallel queries
        // on the same connection. flatMap chains them strictly one-after-another.
        return staffRepo.findByStaffId(req.getAssignorStaffId())
                .flatMap(assignor ->
                    staffRepo.findByStaffId(req.getAssigneeStaffId())
                        .flatMap(assignee -> persistNewTaskAndNotifyAssignee(task, assignor, assignee)));
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
        // Temp code — will be replaced with sequential code after ID is generated
        task.setJobTaskId("JT-TEMP-" + (System.currentTimeMillis() % 99999));
        return task;
    }

    /**
     * Persists the task, assigns its final sequential code, fires the "task assigned"
     * notification to the assignee, and maps the result to a response DTO.
     */
    private Uni<JobTaskResponse> persistNewTaskAndNotifyAssignee(JobTask task, Staff assignor, Staff assignee) {
        return taskRepo.persist(task)
                .flatMap(this::assignGeneratedJobTaskCode)
                .invoke(updated -> notifyAssigneeOfNewTaskAssignment(updated, assignee, assignor))
                .map(updated -> toResponse(updated, assignor, assignee));
    }

    /** Replaces the temporary code with the final sequential {@code JT-<year>-<uniqId>} code and flushes it. */
    private Uni<JobTask> assignGeneratedJobTaskCode(JobTask saved) {
        saved.setJobTaskId(String.format("JT-%d-%04d", Year.now().getValue(), saved.getUniqId()));
        return taskRepo.persist(saved);
    }

    /** Fires the assignment notification to the assignee; never affects the caller's transaction. */
    private void notifyAssigneeOfNewTaskAssignment(JobTask task, Staff assignee, Staff assignor) {
        fireAndForgetNotification(notificationService.notifyTaskAssigned(task, assignee, assignor));
    }

    // ─── Update ───────────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<JobTaskResponse> update(Long id, UpdateJobTaskRequest req) {
        return taskRepo.findActiveById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Task " + id + " not found"))
                .flatMap(task -> {
                    task.setTaskTitle(req.getTaskTitle().trim());
                    task.setTaskType(req.getTaskType());
                    task.setTaskDescription(req.getTaskDescription());
                    task.setAssigneeStaffId(req.getAssigneeStaffId());
                    task.setPriority(req.getPriority());
                    task.setDueDate(req.getDueDate() != null ? req.getDueDate().atStartOfDay() : null);
                    task.setEstimatedHours(req.getEstimatedHours());
                    task.setActualHours(req.getActualHours());
                    task.setRemarks(req.getRemarks());
                    task.setLastEditStaff(req.getLastEditStaff());
                    task.setLastEdtiDate(DateUtil.nowSGT());
                    return enrichSingle(task);
                });
    }

    // ─── Status update ────────────────────────────────────────────────────────

    /**
     * Updates a task's status, adjusting started/completed dates for the new status.
     *
     * Delegates to {@link #applyStatusFieldChanges} for field mutation and
     * {@link #enrichAndNotifyOnCompletion} for staff enrichment + completion notification.
     */
    @WithTransaction
    public Uni<JobTaskResponse> updateStatus(Long id, UpdateStatusRequest req) {
        return taskRepo.findActiveById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Task " + id + " not found"))
                .flatMap(task -> {
                    applyStatusFieldChanges(task, req);
                    return enrichAndNotifyOnCompletion(task, req.getJobStatus());
                });
    }

    /** Mutates started/completed dates and the status/audit fields for the requested status transition. */
    private void applyStatusFieldChanges(JobTask task, UpdateStatusRequest req) {
        switch (req.getJobStatus()) {
            case "In Progress" -> markStartedIfNotAlready(task, req.getStartedDate());
            case "Completed" -> markCompleted(task, req.getStartedDate(), req.getCompletedDate());
            case "Pending", "On Hold" -> task.setCompletedDate(null); // reset completion; keep startedDate intact
            case "Closed" -> { /* closing an already-completed task — keep both dates as-is */ }
        }
        task.setJobStatus(req.getJobStatus());
        task.setLastEditStaff(req.getLastEditStaff());
        task.setLastEdtiDate(DateUtil.nowSGT());
    }

    /** Sets startedDate from the request if provided, otherwise now — only if not already set. */
    private void markStartedIfNotAlready(JobTask task, LocalDate requestedStartedDate) {
        if (requestedStartedDate != null) {
            task.setStartedDate(requestedStartedDate.atStartOfDay());
        } else if (task.getStartedDate() == null) {
            task.setStartedDate(DateUtil.nowSGT());
        }
    }

    /** Sets startedDate (if missing) and completedDate for a transition into "Completed". */
    private void markCompleted(JobTask task, LocalDate requestedStartedDate, LocalDate requestedCompletedDate) {
        if (requestedStartedDate != null && task.getStartedDate() == null) {
            task.setStartedDate(requestedStartedDate.atStartOfDay());
        } else if (task.getStartedDate() == null) {
            task.setStartedDate(DateUtil.nowSGT());
        }
        task.setCompletedDate(requestedCompletedDate != null ? requestedCompletedDate.atStartOfDay() : DateUtil.nowSGT());
    }

    /**
     * Resolves assignor + assignee, fires the "task completed" notification to the assignor
     * when the new status is "Completed", and maps the result to a response DTO.
     */
    private Uni<JobTaskResponse> enrichAndNotifyOnCompletion(JobTask task, String newStatus) {
        return staffRepo.findByStaffId(task.getAssignorStaffId())
                .flatMap(assignor ->
                    staffRepo.findByStaffId(task.getAssigneeStaffId())
                        .invoke(assignee -> notifyAssignorIfTaskJustCompleted(task, newStatus, assignor, assignee))
                        .map(assignee -> toResponse(task, assignor, assignee)));
    }

    /** Fires the completion notification to the assignor only when the transition landed on "Completed". */
    private void notifyAssignorIfTaskJustCompleted(JobTask task, String newStatus, Staff assignor, Staff assignee) {
        if ("Completed".equals(newStatus)) {
            fireAndForgetNotification(notificationService.notifyTaskCompleted(task, assignor, assignee));
        }
    }

    // ─── Reassign (assignor only) ─────────────────────────────────────────────

    /**
     * Reassigns a task to a new assignee, logs the change, and notifies the new assignee.
     *
     * Delegates to {@link #applyReassignmentFieldChanges} for field mutation and
     * {@link #logReassignmentAndNotifyNewAssignee} for the audit log + notification chain.
     */
    @WithTransaction
    public Uni<JobTaskResponse> reassign(Long id, ReassignRequest req, DeviceInfo deviceInfo) {
        return taskRepo.findActiveById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Task " + id + " not found"))
                .flatMap(task -> {
                    String previousAssigneeStaffId = task.getAssigneeStaffId();
                    applyReassignmentFieldChanges(task, req);
                    return logReassignmentAndNotifyNewAssignee(task, req, previousAssigneeStaffId, deviceInfo);
                });
    }

    /** Mutates the assignee and audit fields for a reassignment. */
    private void applyReassignmentFieldChanges(JobTask task, ReassignRequest req) {
        task.setAssigneeStaffId(req.getNewAssigneeStaffId());
        task.setLastEditStaff(req.getLastEditStaff());
        task.setLastEdtiDate(DateUtil.nowSGT());
    }

    /**
     * Writes the REASSIGN audit log entry, then resolves assignor + new assignee and fires
     * the "task assigned" notification to the new assignee.
     */
    private Uni<JobTaskResponse> logReassignmentAndNotifyNewAssignee(
            JobTask task, ReassignRequest req, String previousAssigneeStaffId, DeviceInfo deviceInfo) {
        String remarks = "Reassigned from " + previousAssigneeStaffId + " to " + req.getNewAssigneeStaffId();
        return logRepo.log(req.getLastEditStaff(), "JOBTASKS", task.getJobTaskId(), "REASSIGN", deviceInfo, remarks)
                .flatMap(ignored ->
                    staffRepo.findByStaffId(task.getAssignorStaffId())
                        .flatMap(assignor ->
                            staffRepo.findByStaffId(task.getAssigneeStaffId())
                                .invoke(newAssignee -> notifyAssigneeOfNewTaskAssignment(task, newAssignee, assignor))
                                .map(newAssignee -> toResponse(task, assignor, newAssignee))));
    }

    // ─── Reschedule (assignor only) ───────────────────────────────────────────

    @WithTransaction
    public Uni<JobTaskResponse> reschedule(Long id, RescheduleRequest req, DeviceInfo deviceInfo) {
        return taskRepo.findActiveById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Task " + id + " not found"))
                .flatMap(task -> {
                    String original = task.getDueDate() != null
                            ? task.getDueDate().toLocalDate().toString() : "none";
                    String updated  = req.getNewDueDate() != null ? req.getNewDueDate().toString() : "none";
                    task.setDueDate(req.getNewDueDate() != null ? req.getNewDueDate().atStartOfDay() : null);
                    task.setLastEditStaff(req.getLastEditStaff());
                    task.setLastEdtiDate(DateUtil.nowSGT());
                    String remarks = "Rescheduled from " + original + " to " + updated;
                    return logRepo.log(req.getLastEditStaff(), "JOBTASKS", task.getJobTaskId(), "RESCHEDULE", deviceInfo, remarks)
                            .flatMap(ignored -> enrichSingle(task));
                });
    }

    // ─── Progress remarks (assignee only) ────────────────────────────────────

    @WithTransaction
    public Uni<JobTaskResponse> updateProgressRemarks(Long id, UpdateProgressRemarksRequest req) {
        return taskRepo.findActiveById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Task " + id + " not found"))
                .flatMap(task -> {
                    task.setProgressRemarks(req.getProgressRemarks());
                    task.setLastEditStaff(req.getLastEditStaff());
                    task.setLastEdtiDate(DateUtil.nowSGT());
                    return enrichSingle(task);
                });
    }

    // ─── Soft delete ──────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<Void> delete(Long id) {
        return taskRepo.findActiveById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Task " + id + " not found"))
                .flatMap(task -> {
                    task.setJobStatus("Void");
                    task.setLastEdtiDate(DateUtil.nowSGT());
                    return Uni.createFrom().voidItem();
                });
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Subscribes to a notification {@link Uni} without blocking the caller or propagating
     * failures into the caller's reactive chain. Errors are logged only — a notification
     * outage must never fail or delay the task create/update transaction.
     */
    private void fireAndForgetNotification(Uni<Void> notification) {
        notification.subscribe().with(
                ignored -> { },
                err -> LOG.errorf(err, "[JobTaskService] Notification failed: %s", err.getMessage()));
    }

    private boolean hasAccess(List<GroupAuthorityAccessDTO> accesses, String code) {
        return accesses.stream()
                .anyMatch(a -> code.equals(a.getAccessCode()) && Boolean.TRUE.equals(a.getAccessValue()));
    }

    private Uni<List<JobTaskResponse>> enrichWithStaff(List<JobTask> tasks) {
        if (tasks.isEmpty()) return Uni.createFrom().item(List.of());

        return getCachedStaffList().map(staffList -> {
            Map<String, Staff> staffMap = staffList.stream()
                    .collect(Collectors.toMap(Staff::getStaffId, Function.identity()));
            return tasks.stream()
                    .map(t -> toResponse(t,
                            staffMap.get(t.getAssignorStaffId()),
                            staffMap.get(t.getAssigneeStaffId())))
                    .collect(Collectors.toList());
        });
    }

    private Uni<JobTaskResponse> enrichSingle(JobTask task) {
        return staffRepo.findByStaffId(task.getAssignorStaffId())
                .flatMap(assignor ->
                    staffRepo.findByStaffId(task.getAssigneeStaffId())
                        .map(assignee -> toResponse(task, assignor, assignee)));
    }

    private JobTaskResponse toResponse(JobTask t, Staff assignor, Staff assignee) {
        JobTaskResponse r = new JobTaskResponse();
        r.setUniqId(t.getUniqId());
        r.setJobTaskId(t.getJobTaskId());
        r.setTaskTitle(t.getTaskTitle());
        r.setTaskType(t.getTaskType());
        r.setTaskDescription(t.getTaskDescription());
        r.setPriority(t.getPriority());
        r.setJobStatus(t.getJobStatus());
        r.setDueDate(t.getDueDate());
        r.setStartedDate(t.getStartedDate());
        r.setCompletedDate(t.getCompletedDate());
        r.setEstimatedHours(t.getEstimatedHours());
        r.setActualHours(t.getActualHours());
        r.setRemarks(t.getRemarks());
        r.setProgressRemarks(t.getProgressRemarks());
        r.setAttachmentPath(t.getAttachmentPath());
        r.setEntryStaff(t.getEntryStaff());
        r.setEntryDate(t.getEntryDate());
        r.setLastEditStaff(t.getLastEditStaff());
        r.setLastEdtiDate(t.getLastEdtiDate());
        r.setAssignor(toStaffSummary(assignor));
        r.setAssignee(toStaffSummary(assignee));
        return r;
    }

    private StaffSummary toStaffSummary(Staff s) {
        if (s == null) return null;
        StaffSummary ss = new StaffSummary();
        ss.setStaffCode(s.getCode());
        ss.setStaffId(s.getStaffId());
        ss.setName(s.getName());
        ss.setDepartment(s.getDepartment());
        ss.setAppointment(s.getAppointment());
        ss.setAvatarColor(s.getAvatarColor());
        return ss;
    }
}
