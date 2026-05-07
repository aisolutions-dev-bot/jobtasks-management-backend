package com.aisolutions.jobtaskmanagement.repository;

import com.aisolutions.jobtaskmanagement.entity.JobTask;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Reactive Panache repository for m24JobTasks.
 * Soft delete = JobStatus='Cancelled' (no IsActive column in this table).
 */
@ApplicationScoped
public class JobTaskRepository implements PanacheRepositoryBase<JobTask, Long> {

    /** All non-cancelled tasks — for users with full access (a2401.02=1) */
    public Uni<List<JobTask>> findAllActive() {
        return list("jobStatus != 'Cancelled' ORDER BY dueDate ASC");
    }

    /**
     * Tasks linked to staff in a given department.
     * Used when a2401.02=0 AND a2401.01=1.
     */
    public Uni<List<JobTask>> findByDepartment(String department) {
        return list(
            "jobStatus != 'Cancelled' AND " +
            "(EXISTS (SELECT 1 FROM Staff s WHERE s.code = assignorStaffId AND s.department = ?1) OR " +
            " EXISTS (SELECT 1 FROM Staff s WHERE s.code = assigneeStaffId AND s.department = ?1)) " +
            "ORDER BY dueDate ASC",
            department);
    }

    /**
     * Tasks where the user is assignor OR assignee.
     * Used when both a2401.02=0 AND a2401.01=0.
     * staffCode = m03Staff.Code (the numeric PK).
     */
    public Uni<List<JobTask>> findByStaffCode(Integer staffCode) {
        return list(
            "jobStatus != 'Cancelled' AND (assignorStaffId = ?1 OR assigneeStaffId = ?1) " +
            "ORDER BY dueDate ASC",
            staffCode);
    }

    public Uni<JobTask> findActiveById(Long id) {
        return find("uniqId = ?1 AND jobStatus != 'Cancelled'", id).firstResult();
    }
}
