package com.aisolutions.jobtaskmanagement.repository;

import com.aisolutions.jobtaskmanagement.entity.JobTask;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.mysqlclient.MySQLClient;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Raw SqlClient repository for m24JobTasks.
 *
 * Every method is parameterized on a {@link SqlClient}: the caller resolves
 * the company-routed pool once and passes it in (see {@code CompanyPoolManager}).
 *
 * Soft delete = JobStatus='Void' (no IsActive column in this table).
 * Note: 'Cancelled' is a user-settable status meaning the task was cancelled
 * by the user; 'Void' means the record was deleted by the assignor.
 */
@ApplicationScoped
public class JobTaskRepository {

    private static final String ALL_COLUMNS =
        "UniqID, JobTaskId, TaskTitle, TaskType, TaskDescription, AssignorStaffID, AssigneeStaffID, " +
        "Priority, JobStatus, DueDate, StartedDate, CompletedDate, EstimatedHours, ActualHours, " +
        "Remarks, ProgressRemarks, AttachmentPath, ReleaseId, EntryStaff, EntryDate, LastEditStaff, LastEdtiDate";

    /** All non-voided tasks — for users with full access (a2401.02=1) */
    public Uni<List<JobTask>> findAllActive(SqlClient client) {
        return client.preparedQuery(
                "SELECT " + ALL_COLUMNS + " FROM m24JobTasks WHERE JobStatus != 'Void' ORDER BY DueDate ASC")
            .execute()
            .map(this::mapList);
    }

    /**
     * Tasks linked to staff in a given department.
     * Used when a2401.02=0 AND a2401.01=1.
     */
    public Uni<List<JobTask>> findByDepartment(SqlClient client, String department) {
        return client.preparedQuery(
                "SELECT " + ALL_COLUMNS + " FROM m24JobTasks " +
                "WHERE JobStatus != 'Void' AND (" +
                "EXISTS (SELECT 1 FROM m03Staff s WHERE s.StaffId = AssignorStaffID AND s.Department = ?) OR " +
                "EXISTS (SELECT 1 FROM m03Staff s WHERE s.StaffId = AssigneeStaffID AND s.Department = ?)) " +
                "ORDER BY DueDate ASC")
            .execute(Tuple.of(department, department))
            .map(this::mapList);
    }

    /**
     * Tasks where the user is assignor OR assignee.
     * Used when both a2401.02=0 AND a2401.01=0.
     * staffId = m03Staff.StaffId (varchar, e.g. SUPERDREW).
     */
    public Uni<List<JobTask>> findByStaffId(SqlClient client, String staffId) {
        return client.preparedQuery(
                "SELECT " + ALL_COLUMNS + " FROM m24JobTasks " +
                "WHERE JobStatus != 'Void' AND (AssignorStaffID = ? OR AssigneeStaffID = ?) " +
                "ORDER BY DueDate ASC")
            .execute(Tuple.of(staffId, staffId))
            .map(this::mapList);
    }

    public Uni<JobTask> findActiveById(SqlClient client, Long id) {
        return client.preparedQuery(
                "SELECT " + ALL_COLUMNS + " FROM m24JobTasks WHERE UniqID = ? AND JobStatus != 'Void'")
            .execute(Tuple.of(id))
            .map(this::mapFirstOrNull);
    }

    /** Lookup by UniqId regardless of status — mirrors Panache's default findById. */
    public Uni<JobTask> findById(SqlClient client, Long id) {
        return client.preparedQuery("SELECT " + ALL_COLUMNS + " FROM m24JobTasks WHERE UniqID = ?")
            .execute(Tuple.of(id))
            .map(this::mapFirstOrNull);
    }

    /**
     * Job tasks eligible for a new release: matching one of the given statuses,
     * not already linked to a release, and (if search provided) matching
     * JobTaskId, TaskDescription, or assignee name.
     */
    public Uni<List<JobTask>> findReleasable(SqlClient client, List<String> statuses, String search) {
        String like = "%" + (search == null ? "" : search.trim()) + "%";
        String placeholders = statuses.stream().map(s -> "?").collect(Collectors.joining(","));
        Tuple params = Tuple.tuple();
        statuses.forEach(params::addValue);
        params.addValue(like).addValue(like).addValue(like);
        return client.preparedQuery(
                "SELECT " + ALL_COLUMNS + " FROM m24JobTasks " +
                "WHERE JobStatus IN (" + placeholders + ") AND ReleaseId IS NULL AND (" +
                "JobTaskId LIKE ? OR TaskDescription LIKE ? OR " +
                "EXISTS (SELECT 1 FROM m03Staff s WHERE s.StaffId = AssigneeStaffID AND s.Name LIKE ?)" +
                ") ORDER BY DueDate ASC")
            .execute(params)
            .map(this::mapList);
    }

    /** Count of active job tasks already linked to a given release. */
    public Uni<Long> countByReleaseId(SqlClient client, String releaseId) {
        return client.preparedQuery("SELECT COUNT(*) AS cnt FROM m24JobTasks WHERE ReleaseId = ?")
            .execute(Tuple.of(releaseId))
            .map(rows -> rows.iterator().next().getLong("cnt"));
    }

    /** All job tasks linked to a given release, for the release detail view. */
    public Uni<List<JobTask>> findByReleaseId(SqlClient client, String releaseId) {
        return client.preparedQuery(
                "SELECT " + ALL_COLUMNS + " FROM m24JobTasks WHERE ReleaseId = ? ORDER BY DueDate ASC")
            .execute(Tuple.of(releaseId))
            .map(this::mapList);
    }

    /** Bulk-unlinks every job task from a release (used when a release is deleted). */
    public Uni<Integer> clearReleaseId(SqlClient client, String releaseId) {
        return client.preparedQuery("UPDATE m24JobTasks SET ReleaseId = NULL WHERE ReleaseId = ?")
            .execute(Tuple.of(releaseId))
            .map(RowSet::rowCount);
    }

    /** Inserts a new job task and returns it with the generated UniqID set. */
    public Uni<JobTask> insert(SqlClient client, JobTask task) {
        return client.preparedQuery(
                "INSERT INTO m24JobTasks (JobTaskId, TaskTitle, TaskType, TaskDescription, AssignorStaffID, " +
                "AssigneeStaffID, Priority, JobStatus, DueDate, StartedDate, CompletedDate, EstimatedHours, " +
                "ActualHours, Remarks, ProgressRemarks, AttachmentPath, ReleaseId, EntryStaff, EntryDate, " +
                "LastEditStaff, LastEdtiDate) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
            .execute(insertTuple(task))
            .map(result -> {
                task.setUniqId((Long) result.property(MySQLClient.LAST_INSERTED_ID));
                return task;
            });
    }

    /** Persists every mutable field of an already-loaded task back to the row. */
    public Uni<JobTask> update(SqlClient client, JobTask task) {
        return client.preparedQuery(
                "UPDATE m24JobTasks SET JobTaskId=?, TaskTitle=?, TaskType=?, TaskDescription=?, " +
                "AssignorStaffID=?, AssigneeStaffID=?, Priority=?, JobStatus=?, DueDate=?, StartedDate=?, " +
                "CompletedDate=?, EstimatedHours=?, ActualHours=?, Remarks=?, ProgressRemarks=?, " +
                "AttachmentPath=?, ReleaseId=?, EntryStaff=?, EntryDate=?, LastEditStaff=?, LastEdtiDate=? " +
                "WHERE UniqID=?")
            .execute(insertTuple(task).addValue(task.getUniqId()))
            .map(ignored -> task);
    }

    private Tuple insertTuple(JobTask t) {
        return Tuple.tuple()
            .addValue(t.getJobTaskId())
            .addValue(t.getTaskTitle())
            .addValue(t.getTaskType())
            .addValue(t.getTaskDescription())
            .addValue(t.getAssignorStaffId())
            .addValue(t.getAssigneeStaffId())
            .addValue(t.getPriority())
            .addValue(t.getJobStatus())
            .addValue(t.getDueDate())
            .addValue(t.getStartedDate())
            .addValue(t.getCompletedDate())
            .addValue(t.getEstimatedHours())
            .addValue(t.getActualHours())
            .addValue(t.getRemarks())
            .addValue(t.getProgressRemarks())
            .addValue(t.getAttachmentPath())
            .addValue(t.getReleaseId())
            .addValue(t.getEntryStaff())
            .addValue(t.getEntryDate())
            .addValue(t.getLastEditStaff())
            .addValue(t.getLastEdtiDate());
    }

    private List<JobTask> mapList(RowSet<Row> rows) {
        List<JobTask> list = new ArrayList<>();
        rows.forEach(row -> list.add(mapRow(row)));
        return list;
    }

    private JobTask mapFirstOrNull(RowSet<Row> rows) {
        return rows.iterator().hasNext() ? mapRow(rows.iterator().next()) : null;
    }

    private JobTask mapRow(Row row) {
        JobTask t = new JobTask();
        t.setUniqId(row.getLong("UniqID"));
        t.setJobTaskId(row.getString("JobTaskId"));
        t.setTaskTitle(row.getString("TaskTitle"));
        t.setTaskType(row.getString("TaskType"));
        t.setTaskDescription(row.getString("TaskDescription"));
        t.setAssignorStaffId(row.getString("AssignorStaffID"));
        t.setAssigneeStaffId(row.getString("AssigneeStaffID"));
        t.setPriority(row.getString("Priority"));
        t.setJobStatus(row.getString("JobStatus"));
        t.setDueDate(row.getLocalDateTime("DueDate"));
        t.setStartedDate(row.getLocalDateTime("StartedDate"));
        t.setCompletedDate(row.getLocalDateTime("CompletedDate"));
        t.setEstimatedHours(row.getBigDecimal("EstimatedHours"));
        t.setActualHours(row.getBigDecimal("ActualHours"));
        t.setRemarks(row.getString("Remarks"));
        t.setProgressRemarks(row.getString("ProgressRemarks"));
        t.setAttachmentPath(row.getString("AttachmentPath"));
        t.setReleaseId(row.getString("ReleaseId"));
        t.setEntryStaff(row.getString("EntryStaff"));
        t.setEntryDate(row.getLocalDateTime("EntryDate"));
        t.setLastEditStaff(row.getString("LastEditStaff"));
        t.setLastEdtiDate(row.getLocalDateTime("LastEdtiDate"));
        return t;
    }
}
