package com.aisolutions.jobtaskmanagement.repository;

import com.aisolutions.jobtaskmanagement.entity.TaskRelease;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.mysqlclient.MySQLClient;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * Raw SqlClient repository for m24TaskRelease.
 *
 * Every method is parameterized on a {@link SqlClient}: the caller resolves
 * the company-routed pool once and passes it in.
 */
@ApplicationScoped
public class TaskReleaseRepository {

    private static final String ALL_COLUMNS =
        "UniqId, ReleaseId, ReleaseDate, ReleaseVersion, ReleaseType, ReleaseRemarks, " +
        "EntryStaff, EntryDate, LastEditStaff, LastEditDate";

    public Uni<List<TaskRelease>> findAllOrdered(SqlClient client) {
        return client.preparedQuery(
                "SELECT " + ALL_COLUMNS + " FROM m24TaskRelease ORDER BY ReleaseDate DESC")
            .execute()
            .map(this::mapList);
    }

    public Uni<TaskRelease> findById(SqlClient client, Long uniqId) {
        return client.preparedQuery("SELECT " + ALL_COLUMNS + " FROM m24TaskRelease WHERE UniqId = ?")
            .execute(Tuple.of(uniqId))
            .map(this::mapFirstOrNull);
    }

    public Uni<Long> countByReleaseId(SqlClient client, String releaseId) {
        return client.preparedQuery("SELECT COUNT(*) AS cnt FROM m24TaskRelease WHERE ReleaseId = ?")
            .execute(Tuple.of(releaseId))
            .map(rows -> rows.iterator().next().getLong("cnt"));
    }

    /** True if another release (different uniqId) already uses this releaseId. */
    public Uni<Boolean> existsByReleaseIdExcluding(SqlClient client, String releaseId, Long excludeUniqId) {
        return client.preparedQuery(
                "SELECT COUNT(*) AS cnt FROM m24TaskRelease WHERE ReleaseId = ? AND UniqId != ?")
            .execute(Tuple.of(releaseId, excludeUniqId))
            .map(rows -> rows.iterator().next().getLong("cnt") > 0);
    }

    /** All releases whose releaseId starts with the given year prefix, e.g. "REL-2026-". */
    public Uni<List<TaskRelease>> findByReleaseIdPrefix(SqlClient client, String prefix) {
        return client.preparedQuery("SELECT " + ALL_COLUMNS + " FROM m24TaskRelease WHERE ReleaseId LIKE ?")
            .execute(Tuple.of(prefix + "%"))
            .map(this::mapList);
    }

    /** Inserts a new release and returns it with the generated UniqId set. */
    public Uni<TaskRelease> insert(SqlClient client, TaskRelease release) {
        return client.preparedQuery(
                "INSERT INTO m24TaskRelease (ReleaseId, ReleaseDate, ReleaseVersion, ReleaseType, " +
                "ReleaseRemarks, EntryStaff, EntryDate, LastEditStaff, LastEditDate) VALUES (?,?,?,?,?,?,?,?,?)")
            .execute(insertTuple(release))
            .map(result -> {
                release.setUniqId((Long) result.property(MySQLClient.LAST_INSERTED_ID));
                return release;
            });
    }

    /** Persists every mutable field of an already-loaded release back to the row. */
    public Uni<TaskRelease> update(SqlClient client, TaskRelease release) {
        return client.preparedQuery(
                "UPDATE m24TaskRelease SET ReleaseId=?, ReleaseDate=?, ReleaseVersion=?, ReleaseType=?, " +
                "ReleaseRemarks=?, EntryStaff=?, EntryDate=?, LastEditStaff=?, LastEditDate=? WHERE UniqId=?")
            .execute(insertTuple(release).addValue(release.getUniqId()))
            .map(ignored -> release);
    }

    public Uni<Void> delete(SqlClient client, TaskRelease release) {
        return client.preparedQuery("DELETE FROM m24TaskRelease WHERE UniqId = ?")
            .execute(Tuple.of(release.getUniqId()))
            .replaceWithVoid();
    }

    private Tuple insertTuple(TaskRelease r) {
        return Tuple.tuple()
            .addValue(r.getReleaseId())
            .addValue(r.getReleaseDate())
            .addValue(r.getReleaseVersion())
            .addValue(r.getReleaseType())
            .addValue(r.getReleaseRemarks())
            .addValue(r.getEntryStaff())
            .addValue(r.getEntryDate())
            .addValue(r.getLastEditStaff())
            .addValue(r.getLastEditDate());
    }

    private List<TaskRelease> mapList(RowSet<Row> rows) {
        List<TaskRelease> list = new ArrayList<>();
        rows.forEach(row -> list.add(mapRow(row)));
        return list;
    }

    private TaskRelease mapFirstOrNull(RowSet<Row> rows) {
        return rows.iterator().hasNext() ? mapRow(rows.iterator().next()) : null;
    }

    private TaskRelease mapRow(Row row) {
        TaskRelease r = new TaskRelease();
        r.setUniqId(row.getLong("UniqId"));
        r.setReleaseId(row.getString("ReleaseId"));
        r.setReleaseDate(row.getLocalDateTime("ReleaseDate"));
        r.setReleaseVersion(row.getString("ReleaseVersion"));
        r.setReleaseType(row.getString("ReleaseType"));
        r.setReleaseRemarks(row.getString("ReleaseRemarks"));
        r.setEntryStaff(row.getString("EntryStaff"));
        r.setEntryDate(row.getLocalDateTime("EntryDate"));
        r.setLastEditStaff(row.getString("LastEditStaff"));
        r.setLastEditDate(row.getLocalDateTime("LastEditDate"));
        return r;
    }
}
