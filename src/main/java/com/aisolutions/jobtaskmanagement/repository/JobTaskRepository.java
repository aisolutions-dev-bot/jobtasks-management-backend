package com.aisolutions.jobtaskmanagement.repository;

import com.aisolutions.jobtaskmanagement.entity.JobTask;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Reactive Panache repository for m24JobTasks.
 * Soft delete = JobStatus='Void' (no IsActive column in this table).
 * Note: 'Cancelled' is a user-settable status meaning the task was cancelled
 * by the user; 'Void' means the record was deleted by the assignor.
 */
@ApplicationScoped
public class JobTaskRepository implements PanacheRepositoryBase<JobTask, Long> {

    /** All non-voided tasks — for users with full access (a2401.02=1) */
    public Uni<List<JobTask>> findAllActive() {
        return list("jobStatus != 'Void' ORDER BY dueDate ASC");
    }

    /**
     * Tasks linked to staff in a given department.
     * Used when a2401.02=0 AND a2401.01=1.
     */
    public Uni<List<JobTask>> findByDepartment(String department) {
        return list(
            "jobStatus != 'Void' AND " +
            "(EXISTS (SELECT 1 FROM Staff s WHERE s.staffId = assignorStaffId AND s.department = ?1) OR " +
            " EXISTS (SELECT 1 FROM Staff s WHERE s.staffId = assigneeStaffId AND s.department = ?1)) " +
            "ORDER BY dueDate ASC",
            department);
    }

    /**
     * Tasks where the user is assignor OR assignee.
     * Used when both a2401.02=0 AND a2401.01=0.
     * staffId = m03Staff.StaffId (varchar, e.g. SUPERDREW).
     */
    public Uni<List<JobTask>> findByStaffId(String staffId) {
        return list(
            "jobStatus != 'Void' AND (assignorStaffId = ?1 OR assigneeStaffId = ?1) " +
            "ORDER BY dueDate ASC",
            staffId);
    }

    public Uni<JobTask> findActiveById(Long id) {
        return find("uniqId = ?1 AND jobStatus != 'Void'", id).firstResult();
    }

    /**
     * Job tasks eligible for a new release: matching one of the given statuses,
     * not already linked to a release, and (if search provided) matching
     * JobTaskId, TaskDescription, or assignee name.
     */
    public Uni<List<JobTask>> findReleasable(List<String> statuses, String search) {
        String like = "%" + (search == null ? "" : search.trim()) + "%";
        return list(
            "jobStatus IN ?1 AND releaseId IS NULL AND (" +
            "jobTaskId LIKE ?2 OR taskDescription LIKE ?2 OR " +
            "EXISTS (SELECT 1 FROM Staff s WHERE s.staffId = assigneeStaffId AND s.name LIKE ?2)" +
            ") ORDER BY dueDate ASC",
            statuses, like);
    }

    /** Count of active job tasks already linked to a given release. */
    public Uni<Long> countByReleaseId(String releaseId) {
        return count("releaseId = ?1", releaseId);
    }

    /** All job tasks linked to a given release, for the release detail view. */
    public Uni<List<JobTask>> findByReleaseId(String releaseId) {
        return list("releaseId = ?1 ORDER BY dueDate ASC", releaseId);
    }

    /** Bulk-unlinks every job task from a release (used when a release is deleted). */
    public Uni<Integer> clearReleaseId(String releaseId) {
        return update("releaseId = null WHERE releaseId = ?1", releaseId);
    }
}
