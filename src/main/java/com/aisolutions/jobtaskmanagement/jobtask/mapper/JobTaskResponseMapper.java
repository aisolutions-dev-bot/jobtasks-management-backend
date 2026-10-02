package com.aisolutions.jobtaskmanagement.jobtask.mapper;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.*;
import com.aisolutions.jobtaskmanagement.entity.JobTask;
import com.aisolutions.jobtaskmanagement.entity.Staff;
import com.aisolutions.jobtaskmanagement.repository.StaffRepository;
import com.aisolutions.shared.tenancy.CompanyPoolManager;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.SqlClient;

/**
 * Maps task and staff rows into API response shapes.
 *
 * Delegates to {@link #cachedStaffList(String)} and {@link #cachedStaffDropdown(String)} for
 * the cached staff directories, and to {@link #enrichAll(String, List)} or
 * {@link #enrichOne(SqlClient, JobTask)} for assembling assignor and assignee summaries.
 */
@ApplicationScoped
public class JobTaskResponseMapper {

    @Inject
    StaffRepository staffRepo;

    @Inject
    CompanyPoolManager companyPoolManager;

    /** Assignor/assignee dropdown — short TTL so new staff are assignable quickly. */
    public Uni<List<StaffSummary>> listStaff(String companyId) {
        return cachedStaffDropdown(companyId)
                .map(staffList -> staffList.stream().map(this::toStaffSummary).collect(Collectors.toList()));
    }

    /** Cached staff directory used for list enrichment — rarely changes. */
    @CacheResult(cacheName = "jobtasks-staff-list")
    public Uni<List<Staff>> cachedStaffList(@CacheKey String companyId) {
        return companyPoolManager.poolFor(companyId).flatMap(staffRepo::findAllOrdered);
    }

    /** Cached staff directory used for the dropdown — short TTL. */
    @CacheResult(cacheName = "jobtasks-staff-dropdown")
    public Uni<List<Staff>> cachedStaffDropdown(@CacheKey String companyId) {
        return companyPoolManager.poolFor(companyId).flatMap(staffRepo::findAllOrdered);
    }

    /** Enriches every task with the cached staff directory in a single pass. */
    public Uni<List<JobTaskResponse>> enrichAll(String companyId, List<JobTask> tasks) {
        if (tasks.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return cachedStaffList(companyId).map(staffList -> mapTasks(tasks, staffList));
    }

    /** Enriches one task by looking up both staff rows on the caller's transaction client. */
    public Uni<JobTaskResponse> enrichOne(SqlClient client, JobTask task) {
        return staffRepo
                .findByStaffId(client, task.getAssignorStaffId())
                .flatMap(assignor -> staffRepo
                        .findByStaffId(client, task.getAssigneeStaffId())
                        .map(assignee -> toResponse(task, assignor, assignee)));
    }

    /** Maps each task against a staff-by-StaffId index built from the cached directory. */
    private List<JobTaskResponse> mapTasks(List<JobTask> tasks, List<Staff> staffList) {
        Map<String, Staff> staffById =
                staffList.stream().collect(Collectors.toMap(Staff::getStaffId, Function.identity()));
        return tasks.stream()
                .map(task -> toResponse(
                        task, staffById.get(task.getAssignorStaffId()), staffById.get(task.getAssigneeStaffId())))
                .collect(Collectors.toList());
    }

    /** Copies task fields and resolved staff summaries into the API response. */
    public JobTaskResponse toResponse(JobTask task, Staff assignor, Staff assignee) {
        JobTaskResponse response = new JobTaskResponse();
        response.setUniqId(task.getUniqId());
        response.setJobTaskId(task.getJobTaskId());
        response.setTaskTitle(task.getTaskTitle());
        response.setTaskType(task.getTaskType());
        response.setTaskDescription(task.getTaskDescription());
        response.setPriority(task.getPriority());
        response.setJobStatus(task.getJobStatus());
        response.setDueDate(task.getDueDate());
        response.setStartedDate(task.getStartedDate());
        response.setCompletedDate(task.getCompletedDate());
        response.setEstimatedHours(task.getEstimatedHours());
        response.setActualHours(task.getActualHours());
        response.setRemarks(task.getRemarks());
        response.setProgressRemarks(task.getProgressRemarks());
        response.setAttachmentPath(task.getAttachmentPath());
        response.setEntryStaff(task.getEntryStaff());
        response.setEntryDate(task.getEntryDate());
        response.setLastEditStaff(task.getLastEditStaff());
        response.setLastEdtiDate(task.getLastEdtiDate());
        response.setAssignor(toStaffSummary(assignor));
        response.setAssignee(toStaffSummary(assignee));
        return response;
    }

    /** Maps a staff row to the compact dropdown summary, preserving nulls for unknown staff. */
    private StaffSummary toStaffSummary(Staff staff) {
        if (staff == null) {
            return null;
        }
        StaffSummary summary = new StaffSummary();
        summary.setStaffCode(staff.getCode());
        summary.setStaffId(staff.getStaffId());
        summary.setName(staff.getName());
        summary.setDepartment(staff.getDepartment());
        summary.setAppointment(staff.getAppointment());
        summary.setAvatarColor(staff.getAvatarColor());
        return summary;
    }
}
