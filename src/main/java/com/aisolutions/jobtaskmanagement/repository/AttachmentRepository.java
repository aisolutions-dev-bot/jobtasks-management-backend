package com.aisolutions.jobtaskmanagement.repository;

import com.aisolutions.jobtaskmanagement.dto.AttachmentDTO;
import com.aisolutions.jobtaskmanagement.entity.Attachment;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.mysqlclient.MySQLClient;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import com.aisolutions.shared.util.DateUtil;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Raw SqlClient repository for m10Attachments (JobTasks module).
 *
 * Every method is parameterized on a {@link SqlClient}: the caller resolves
 * the company-routed pool once and passes it in.
 *
 * This repository handles DB operations only. FTP upload/download/delete
 * is orchestrated by {@link com.aisolutions.jobtaskmanagement.service.attachment.AttachmentService}
 * using credentials loaded from m07SystemParameters at runtime.
 */
@ApplicationScoped
public class AttachmentRepository {

    private static final Logger LOG = Logger.getLogger(AttachmentRepository.class);

    // #region QUERY

    public Uni<List<AttachmentDTO>> findByModuleAndReference(SqlClient client, String moduleType, String referenceCode) {
        return client.preparedQuery(
                "SELECT UniqId, ModuleType, ReferenceCode, FileName, OriginalName, FileSize, StorageType, " +
                "ContentType, FileExtension, FilePath, Description, UploadSource, EntryStaff, EntryDate " +
                "FROM m10Attachments WHERE ModuleType = ? AND ReferenceCode = ? ORDER BY EntryDate DESC")
            .execute(Tuple.of(moduleType, referenceCode))
            .map(rows -> {
                List<AttachmentDTO> list = new ArrayList<>();
                rows.forEach(row -> list.add(new AttachmentDTO(
                    row.getLong("UniqId"),
                    row.getString("ModuleType"),
                    row.getString("ReferenceCode"),
                    row.getString("FileName"),
                    row.getString("OriginalName"),
                    row.getLong("FileSize"),
                    row.getString("StorageType"),
                    row.getString("ContentType"),
                    row.getString("FileExtension"),
                    row.getString("FilePath"),
                    row.getString("Description"),
                    row.getString("UploadSource"),
                    row.getString("EntryStaff"),
                    row.getLocalDateTime("EntryDate"))));
                return list;
            })
            .onFailure().invoke(e -> LOG.errorf(e, "[Attachment] findByModuleAndReference error: %s", e.getMessage()));
    }

    /** Retrieve attachment metadata (no file blob). */
    public Uni<Attachment> findByIdMeta(SqlClient client, Long uniqId) {
        return client.preparedQuery(
                "SELECT UniqId, ModuleType, ReferenceCode, FileName, OriginalName, FileSize, StorageType, " +
                "ContentType, FileExtension, FilePath, Description, UploadSource, EntryStaff, EntryDate, " +
                "LastEditStaff, LastEditDate FROM m10Attachments WHERE UniqId = ?")
            .execute(Tuple.of(uniqId))
            .map(rows -> rows.iterator().hasNext() ? mapMetaRow(rows.iterator().next()) : null)
            .onFailure().invoke(e -> LOG.errorf(e, "[Attachment] findByIdMeta error: %s", e.getMessage()));
    }

    // #endregion

    // #region CREATE

    /**
     * Persist attachment metadata after a successful FTP upload.
     *
     * @param remotePath    full FTP path returned by FTPStorageService
     * @param moduleType    "JOBTASKS"
     * @param referenceCode jobTaskId, e.g. "JT-2026-0001"
     */
    public Uni<Attachment> persistAttachmentMeta(
            SqlClient client,
            String remotePath,
            String moduleType,
            String referenceCode,
            String originalName,
            String contentType,
            Long   fileSize,
            String currentUser) {

        Attachment a = new Attachment();
        String ext = getExt(originalName);
        a.setModuleType(moduleType.toUpperCase());
        a.setReferenceCode(referenceCode);
        a.setFileName(UUID.randomUUID().toString() + ext);
        a.setOriginalName(originalName);
        a.setFileSize(fileSize);
        a.setContentType(contentType);
        a.setFileExtension(ext);
        a.setStorageType("FTP");
        a.setFilePath(remotePath);
        a.setUploadSource("WEB");
        a.setEntryStaff(currentUser);
        a.setEntryDate(DateUtil.nowSGT());

        return client.preparedQuery(
                "INSERT INTO m10Attachments (ModuleType, ReferenceCode, FileName, OriginalName, FileSize, " +
                "StorageType, ContentType, FileExtension, FilePath, UploadSource, EntryStaff, EntryDate) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")
            .execute(Tuple.tuple()
                .addValue(a.getModuleType())
                .addValue(a.getReferenceCode())
                .addValue(a.getFileName())
                .addValue(a.getOriginalName())
                .addValue(a.getFileSize())
                .addValue(a.getStorageType())
                .addValue(a.getContentType())
                .addValue(a.getFileExtension())
                .addValue(a.getFilePath())
                .addValue(a.getUploadSource())
                .addValue(a.getEntryStaff())
                .addValue(a.getEntryDate()))
            .map(result -> {
                a.setUniqId((Long) result.property(MySQLClient.LAST_INSERTED_ID));
                return a;
            })
            .onFailure().invoke(e -> LOG.errorf(e, "[Attachment] persistAttachmentMeta error: %s", e.getMessage()));
    }

    // #endregion

    // #region DELETE

    /** Delete attachment record from DB. FTP deletion must be done before calling this. */
    public Uni<Boolean> deleteFromDb(SqlClient client, Long uniqId) {
        return client.preparedQuery("DELETE FROM m10Attachments WHERE UniqId = ?")
            .execute(Tuple.of(uniqId))
            .map(result -> result.rowCount() > 0)
            .onFailure().invoke(e -> LOG.errorf(e, "[Attachment] deleteFromDb error: %s", e.getMessage()));
    }

    // #endregion

    // #region HELPERS

    private Attachment mapMetaRow(Row row) {
        Attachment a = new Attachment();
        a.setUniqId(row.getLong("UniqId"));
        a.setModuleType(row.getString("ModuleType"));
        a.setReferenceCode(row.getString("ReferenceCode"));
        a.setFileName(row.getString("FileName"));
        a.setOriginalName(row.getString("OriginalName"));
        a.setFileSize(row.getLong("FileSize"));
        a.setStorageType(row.getString("StorageType"));
        a.setContentType(row.getString("ContentType"));
        a.setFileExtension(row.getString("FileExtension"));
        a.setFilePath(row.getString("FilePath"));
        a.setDescription(row.getString("Description"));
        a.setUploadSource(row.getString("UploadSource"));
        a.setEntryStaff(row.getString("EntryStaff"));
        a.setEntryDate(row.getLocalDateTime("EntryDate"));
        a.setLastEditStaff(row.getString("LastEditStaff"));
        a.setLastEditDate(row.getLocalDateTime("LastEditDate"));
        return a;
    }

    private String getExt(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot != -1 ? filename.substring(dot) : "";
    }

    // #endregion
}
