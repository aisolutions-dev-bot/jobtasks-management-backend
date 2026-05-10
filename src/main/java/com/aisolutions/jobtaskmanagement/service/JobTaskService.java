package com.aisolutions.jobtaskmanagement.service;

import com.aisolutions.jobtaskmanagement.client.GroupAuthorityAccessClient;
import com.aisolutions.jobtaskmanagement.dto.GroupAuthorityAccessDTO;
import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.*;
import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.entity.Staff;
import com.aisolutions.jobtaskmanagement.repository.JobTaskRepository;
import com.aisolutions.jobtaskmanagement.repository.StaffRepository;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
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
    @RestClient
    GroupAuthorityAccessClient accessClient;

    // ─── Staff dropdown ───────────────────────────────────────────────────────

    @WithSession
    public Uni<List<StaffSummary>> listStaff() {
        return staffRepo.findAllOrdered()
                .map(list -> list.stream().map(this::toStaffSummary).collect(Collectors.toList()));
    }

    // ─── List with RBAC ───────────────────────────────────────────────────────

    @WithSession
    public Uni<List<JobTaskResponse>> listWithRbac(String groupAuthority, String staffCode) {

        Uni<List<GroupAuthorityAccessDTO>> accessUni =
                (groupAuthority != null && !groupAuthority.isBlank())
                        ? accessClient.getAccessByModule(groupAuthority, MODULE_ID)
                                      .onFailure().recoverWithItem(e -> {
                                          LOG.warnf("RBAC fetch failed: %s", e.getMessage());
                                          return List.of();
                                      })
                        : Uni.createFrom().item(List.of());

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
                        // staffCode not provided — return all active tasks as fallback
                        tasksUni = taskRepo.findAllActive();
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

    @WithTransaction
    public Uni<JobTaskResponse> create(CreateJobTaskRequest req) {
        // Build the task entity first
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
        task.setEntryDate(LocalDateTime.now());
        // Temp code — will be replaced with sequential code after ID is generated
        task.setJobTaskId("JT-TEMP-" + (System.currentTimeMillis() % 99999));

        // Sequential reactive chain — Vert.x MySQL client cannot handle parallel queries
        // on the same connection. flatMap chains them strictly one-after-another.
        return staffRepo.findByStaffId(req.getAssignorStaffId())
                .flatMap(assignor ->
                    staffRepo.findByStaffId(req.getAssigneeStaffId())
                        .flatMap(assignee ->
                            taskRepo.persist(task)
                                .flatMap(saved -> {
                                    // Update JobTaskId now that we have the auto-generated UniqID.
                                    // Use flatMap + persist to ensure the update is flushed to DB.
                                    saved.setJobTaskId(String.format("JT-%d-%04d",
                                        Year.now().getValue(), saved.getUniqId()));
                                    return taskRepo.persist(saved)
                                        .map(updated -> toResponse(updated, assignor, assignee));
                                })
                        )
                );
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
                    task.setLastEdtiDate(LocalDateTime.now());
                    return enrichSingle(task);
                });
    }

    // ─── Status update ────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<JobTaskResponse> updateStatus(Long id, UpdateStatusRequest req) {
        return taskRepo.findActiveById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Task " + id + " not found"))
                .flatMap(task -> {
                    String newStatus = req.getJobStatus();
                    if ("In Progress".equals(newStatus) && task.getStartedDate() == null) {
                        task.setStartedDate(LocalDateTime.now());
                    }
                    if ("Completed".equals(newStatus)) {
                        task.setCompletedDate(LocalDateTime.now());
                    }
                    if ("Pending".equals(newStatus) || "On Hold".equals(newStatus)) {
                        task.setCompletedDate(null);
                    }
                    task.setJobStatus(newStatus);
                    task.setLastEditStaff(req.getLastEditStaff());
                    task.setLastEdtiDate(LocalDateTime.now());
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
                    task.setLastEdtiDate(LocalDateTime.now());
                    return Uni.createFrom().voidItem();
                });
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private boolean hasAccess(List<GroupAuthorityAccessDTO> accesses, String code) {
        return accesses.stream()
                .anyMatch(a -> code.equals(a.getAccessCode()) && Boolean.TRUE.equals(a.getAccessValue()));
    }

    private Uni<List<JobTaskResponse>> enrichWithStaff(List<JobTask> tasks) {
        if (tasks.isEmpty()) return Uni.createFrom().item(List.of());

        return staffRepo.findAllOrdered().map(staffList -> {
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
