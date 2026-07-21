package com.aisolutions.jobtaskmanagement.service;

import com.aisolutions.jobtaskmanagement.client.GroupAuthorityAccessClient;
import com.aisolutions.jobtaskmanagement.dto.GroupAuthorityAccessDTO;
import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.JobTaskResponse;
import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.StaffSummary;
import com.aisolutions.jobtaskmanagement.dto.TaskReleaseDTO.*;
import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.entity.Staff;
import com.aisolutions.jobtaskmanagement.entity.TaskRelease;
import com.aisolutions.jobtaskmanagement.repository.JobTaskRepository;
import com.aisolutions.jobtaskmanagement.repository.StaffRepository;
import com.aisolutions.jobtaskmanagement.repository.TaskReleaseRepository;
import com.aisolutions.shared.util.DateUtil;

import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Business logic for Task Release management (Job Tasks module, mod24 / a2402).
 *
 * RBAC (a2402):
 *   a2402    → required to view the Task Release submenu (list + releasable job tasks)
 *   a2402.01 → required to add a new release
 */
@ApplicationScoped
public class TaskReleaseService {

    private static final Logger LOG = Logger.getLogger(TaskReleaseService.class);

    private static final String MODULE_ID  = "mod24";
    private static final String ACCESS_VIEW = "a2402";
    private static final String ACCESS_ADD  = "a2402.01";

    @Inject
    TaskReleaseRepository releaseRepo;

    @Inject
    JobTaskRepository taskRepo;

    @Inject
    StaffRepository staffRepo;

    @Inject
    @RestClient
    GroupAuthorityAccessClient accessClient;

    /** RBAC access codes per groupAuthority — rarely change. */
    @CacheResult(cacheName = "jobtasks-rbac-access")
    public Uni<List<GroupAuthorityAccessDTO>> getCachedAccess(@CacheKey String groupAuthority) {
        return accessClient.getAccessByModule(groupAuthority, MODULE_ID)
                .onFailure().recoverWithItem(e -> {
                    LOG.warnf("RBAC fetch failed: %s", e.getMessage());
                    return List.of();
                });
    }

    private Uni<List<GroupAuthorityAccessDTO>> resolveAccess(String groupAuthority) {
        return (groupAuthority != null && !groupAuthority.isBlank())
                ? getCachedAccess(groupAuthority)
                : Uni.createFrom().item(List.of());
    }

    // ─── List ─────────────────────────────────────────────────────────────────

    @WithSession
    public Uni<List<TaskReleaseResponse>> listReleases(String groupAuthority) {
        return resolveAccess(groupAuthority).flatMap(accesses -> {
            if (!hasAccess(accesses, ACCESS_VIEW)) {
                return Uni.createFrom().failure(new ForbiddenException("Not authorized to view Task Releases"));
            }
            return releaseRepo.findAllOrdered()
                .flatMap(releases ->
                    Multi.createFrom().iterable(releases)
                        .onItem().transformToUniAndConcatenate(r ->
                            taskRepo.countByReleaseId(r.getReleaseId()).map(count -> toResponse(r, count)))
                        .collect().asList());
        });
    }

    // ─── Releasable job tasks ────────────────────────────────────────────────

    @WithSession
    public Uni<List<JobTaskResponse>> getReleasableJobTasks(String groupAuthority, List<String> statuses, String search) {
        return resolveAccess(groupAuthority).flatMap(accesses -> {
            if (!hasAccess(accesses, ACCESS_VIEW)) {
                return Uni.createFrom().failure(new ForbiddenException("Not authorized to view Task Releases"));
            }
            return taskRepo.findReleasable(statuses, search).flatMap(this::enrichWithStaff);
        });
    }

    // ─── Create ───────────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<TaskReleaseResponse> create(String groupAuthority, CreateTaskReleaseRequest req) {
        return resolveAccess(groupAuthority).flatMap(accesses -> {
            if (!hasAccess(accesses, ACCESS_ADD)) {
                return Uni.createFrom().failure(new ForbiddenException("Not authorized to add a Task Release"));
            }
            return doCreate(req);
        });
    }

    // ─── Detail ───────────────────────────────────────────────────────────────

    @WithSession
    public Uni<TaskReleaseDetailResponse> getDetail(String groupAuthority, Long id) {
        return resolveAccess(groupAuthority).flatMap(accesses -> {
            if (!hasAccess(accesses, ACCESS_VIEW)) {
                return Uni.createFrom().failure(new ForbiddenException("Not authorized to view Task Releases"));
            }
            return releaseRepo.findById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Task Release " + id + " not found"))
                .flatMap(this::buildDetail);
        });
    }

    // ─── Update ───────────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<TaskReleaseResponse> update(String groupAuthority, Long id, UpdateTaskReleaseRequest req) {
        return resolveAccess(groupAuthority).flatMap(accesses -> {
            if (!hasAccess(accesses, ACCESS_ADD)) {
                return Uni.createFrom().failure(new ForbiddenException("Not authorized to edit a Task Release"));
            }
            return releaseRepo.findById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Task Release " + id + " not found"))
                .flatMap(release -> doUpdate(release, req));
        });
    }

    private Uni<TaskReleaseResponse> doUpdate(TaskRelease release, UpdateTaskReleaseRequest req) {
        String oldReleaseId = release.getReleaseId();
        String newReleaseId = req.getReleaseId();
        boolean releaseIdChanged = newReleaseId != null && !newReleaseId.equals(oldReleaseId);

        Uni<Void> uniquenessCheck = releaseIdChanged
            ? releaseRepo.existsByReleaseIdExcluding(newReleaseId, release.getUniqId())
                .flatMap(exists -> exists
                    ? Uni.createFrom().<Void>failure(new BadRequestException("Release ID already exists"))
                    : Uni.createFrom().voidItem())
            : Uni.createFrom().voidItem();

        return uniquenessCheck.flatMap(ignored -> {
            release.setReleaseId(newReleaseId);
            release.setReleaseDate(req.getReleaseDate() != null ? req.getReleaseDate().atStartOfDay() : release.getReleaseDate());
            release.setReleaseVersion(req.getReleaseVersion());
            release.setReleaseRemarks(req.getReleaseRemarks());
            release.setLastEditStaff(req.getLastEditStaff());
            release.setLastEditDate(DateUtil.nowSGT());

            // ReleaseId is duplicated onto every linked JobTask row (JobTask.releaseId
            // stores the release's *code*, not its uniqId) — keep them in sync.
            Uni<Void> syncTasks = releaseIdChanged
                ? taskRepo.findByReleaseId(oldReleaseId).map(tasks -> {
                        tasks.forEach(t -> t.setReleaseId(newReleaseId));
                        return null;
                    })
                : Uni.createFrom().voidItem();

            return syncTasks
                .flatMap(ignored2 -> taskRepo.countByReleaseId(release.getReleaseId()))
                .map(count -> toResponse(release, count));
        });
    }

    // ─── Task management ─────────────────────────────────────────────────────

    @WithTransaction
    public Uni<TaskReleaseDetailResponse> addJobTasks(String groupAuthority, Long id, AddJobTasksRequest req) {
        return resolveAccess(groupAuthority).flatMap(accesses -> {
            if (!hasAccess(accesses, ACCESS_ADD)) {
                return Uni.createFrom().failure(new ForbiddenException("Not authorized to edit a Task Release"));
            }
            return releaseRepo.findById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Task Release " + id + " not found"))
                .flatMap(release -> {
                    List<Long> jobTaskIds = req.getJobTaskIds() != null ? req.getJobTaskIds() : List.of();
                    return Multi.createFrom().iterable(jobTaskIds)
                        .onItem().transformToUniAndConcatenate(taskId ->
                            taskRepo.findById(taskId).flatMap(task -> {
                                if (task == null || task.getReleaseId() != null) {
                                    return Uni.createFrom().voidItem();
                                }
                                task.setReleaseId(release.getReleaseId());
                                task.setLastEdtiDate(DateUtil.nowSGT());
                                return Uni.createFrom().voidItem();
                            }))
                        .collect().asList()
                        .flatMap(ignored -> buildDetail(release));
                });
        });
    }

    @WithTransaction
    public Uni<Void> removeJobTask(String groupAuthority, Long id, Long jobTaskUniqId) {
        return resolveAccess(groupAuthority).flatMap(accesses -> {
            if (!hasAccess(accesses, ACCESS_ADD)) {
                return Uni.createFrom().failure(new ForbiddenException("Not authorized to edit a Task Release"));
            }
            return releaseRepo.findById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Task Release " + id + " not found"))
                .flatMap(release ->
                    taskRepo.findById(jobTaskUniqId)
                        .onItem().ifNull().failWith(() -> new NotFoundException("Job Task " + jobTaskUniqId + " not found"))
                        .flatMap(task -> {
                            if (!Objects.equals(task.getReleaseId(), release.getReleaseId())) {
                                return Uni.createFrom().failure(new BadRequestException("Task is not part of this release"));
                            }
                            task.setReleaseId(null);
                            task.setLastEdtiDate(DateUtil.nowSGT());
                            return Uni.createFrom().voidItem();
                        }));
        });
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    @WithTransaction
    public Uni<Void> delete(String groupAuthority, Long id) {
        return resolveAccess(groupAuthority).flatMap(accesses -> {
            if (!hasAccess(accesses, ACCESS_ADD)) {
                return Uni.createFrom().failure(new ForbiddenException("Not authorized to delete a Task Release"));
            }
            return releaseRepo.findById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Task Release " + id + " not found"))
                .flatMap(release ->
                    taskRepo.clearReleaseId(release.getReleaseId())
                        .flatMap(ignored -> releaseRepo.delete(release)));
        });
    }

    private boolean hasAccess(List<GroupAuthorityAccessDTO> accesses, String code) {
        return accesses.stream()
                .anyMatch(a -> code.equals(a.getAccessCode()) && Boolean.TRUE.equals(a.getAccessValue()));
    }

    private Uni<TaskReleaseResponse> doCreate(CreateTaskReleaseRequest req) {
        TaskRelease release = new TaskRelease();
        release.setReleaseId(req.getReleaseId());
        release.setReleaseDate(req.getReleaseDate() != null ? req.getReleaseDate().atStartOfDay() : DateUtil.nowSGT());
        release.setReleaseVersion(req.getReleaseVersion());
        release.setReleaseRemarks(req.getReleaseRemarks());
        release.setEntryStaff(req.getEntryStaff() != null ? req.getEntryStaff() : "SYSTEM");
        release.setEntryDate(DateUtil.nowSGT());

        List<Long> jobTaskIds = req.getJobTaskIds() != null ? req.getJobTaskIds() : List.of();

        return releaseRepo.persist(release)
            .flatMap(savedRelease ->
                Multi.createFrom().iterable(jobTaskIds)
                    .onItem().transformToUniAndConcatenate(id ->
                        taskRepo.findById(id).flatMap(task -> {
                            if (task == null) return Uni.createFrom().voidItem();
                            task.setReleaseId(savedRelease.getReleaseId());
                            task.setLastEdtiDate(DateUtil.nowSGT());
                            return Uni.createFrom().voidItem();
                        }))
                    .collect().asList()
                    .flatMap(ignored -> taskRepo.countByReleaseId(savedRelease.getReleaseId()))
                    .map(count -> toResponse(savedRelease, count)));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private TaskReleaseResponse toResponse(TaskRelease r, Long taskCount) {
        TaskReleaseResponse resp = new TaskReleaseResponse();
        resp.setUniqId(r.getUniqId());
        resp.setReleaseId(r.getReleaseId());
        resp.setReleaseDate(r.getReleaseDate());
        resp.setReleaseVersion(r.getReleaseVersion());
        resp.setReleaseRemarks(r.getReleaseRemarks());
        resp.setEntryStaff(r.getEntryStaff());
        resp.setEntryDate(r.getEntryDate());
        resp.setLastEditStaff(r.getLastEditStaff());
        resp.setLastEditDate(r.getLastEditDate());
        resp.setTaskCount(taskCount);
        return resp;
    }

    private Uni<TaskReleaseDetailResponse> buildDetail(TaskRelease release) {
        return taskRepo.findByReleaseId(release.getReleaseId())
            .flatMap(this::enrichWithStaff)
            .map(jobTasks -> toDetailResponse(release, (long) jobTasks.size(), jobTasks));
    }

    private TaskReleaseDetailResponse toDetailResponse(TaskRelease r, Long taskCount, List<JobTaskResponse> jobTasks) {
        TaskReleaseDetailResponse resp = new TaskReleaseDetailResponse();
        resp.setUniqId(r.getUniqId());
        resp.setReleaseId(r.getReleaseId());
        resp.setReleaseDate(r.getReleaseDate());
        resp.setReleaseVersion(r.getReleaseVersion());
        resp.setReleaseRemarks(r.getReleaseRemarks());
        resp.setEntryStaff(r.getEntryStaff());
        resp.setEntryDate(r.getEntryDate());
        resp.setLastEditStaff(r.getLastEditStaff());
        resp.setLastEditDate(r.getLastEditDate());
        resp.setTaskCount(taskCount);
        resp.setJobTasks(jobTasks);
        return resp;
    }

    private Uni<List<JobTaskResponse>> enrichWithStaff(List<JobTask> tasks) {
        if (tasks.isEmpty()) return Uni.createFrom().item(List.of());

        return staffRepo.findAllOrdered().map(staffList -> {
            Map<String, Staff> staffMap = staffList.stream()
                    .collect(Collectors.toMap(Staff::getStaffId, Function.identity()));
            return tasks.stream()
                    .map(t -> toJobTaskResponse(t,
                            staffMap.get(t.getAssignorStaffId()),
                            staffMap.get(t.getAssigneeStaffId())))
                    .collect(Collectors.toList());
        });
    }

    private JobTaskResponse toJobTaskResponse(JobTask t, Staff assignor, Staff assignee) {
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
