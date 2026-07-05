package com.aisolutions.jobtaskmanagement.repository;

import com.aisolutions.jobtaskmanagement.dto.AttachmentDTO;
import com.aisolutions.jobtaskmanagement.entity.Attachment;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import com.aisolutions.shared.util.DateUtil;

import java.util.List;
import java.util.UUID;

/**
 * Reactive Panache repository for m10Attachments (JobTasks module).
 *
 * This repository handles DB operations only. FTP upload/download/delete
 * is orchestrated by {@link com.aisolutions.jobtaskmanagement.service.attachment.AttachmentService}
 * using credentials loaded from m07SystemParameter at runtime.
 */
@ApplicationScoped
@WithSession
public class AttachmentRepository implements PanacheRepositoryBase<Attachment, Long> {

    // #region QUERY

    @SuppressWarnings("null")
    public Uni<List<AttachmentDTO>> findByModuleAndReference(String moduleType, String referenceCode) {
        return getSession().flatMap(session ->
            session.createQuery(
                "SELECT new com.aisolutions.jobtaskmanagement.dto.AttachmentDTO(" +
                    "a.uniqId, a.moduleType, a.referenceCode, a.fileName, a.originalName, " +
                    "a.fileSize, a.storageType, a.contentType, a.fileExtension, a.filePath, " +
                    "a.description, a.uploadSource, a.entryStaff, a.entryDate) " +
                    "FROM Attachment a " +
                    "WHERE a.moduleType = :moduleType AND a.referenceCode = :referenceCode " +
                    "ORDER BY a.entryDate DESC",
                AttachmentDTO.class)
                .setParameter("moduleType", moduleType)
                .setParameter("referenceCode", referenceCode)
                .getResultList()
        ).onFailure().invoke(e ->
            System.err.println("[Attachment] findByModuleAndReference error: " + e.getMessage())
        );
    }

    /** Retrieve attachment metadata (no file blob). */
    @SuppressWarnings("null")
    public Uni<Attachment> findByIdMeta(Long uniqId) {
        return getSession().flatMap(session -> session.find(Attachment.class, uniqId));
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
            String remotePath,
            String moduleType,
            String referenceCode,
            String originalName,
            String contentType,
            Long   fileSize,
            String currentUser) {

        return getSession().flatMap(session -> {
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
            a.setFileData(null);
            a.setUploadSource("WEB");
            a.setEntryStaff(currentUser);
            a.setEntryDate(DateUtil.nowSGT());
            return session.persist(a).replaceWith(a);
        }).onFailure().invoke(e ->
            System.err.println("[Attachment] persistAttachmentMeta error: " + e.getMessage())
        );
    }

    // #endregion

    // #region DELETE

    /** Delete attachment record from DB. FTP deletion must be done before calling this. */
    public Uni<Boolean> deleteFromDb(Long uniqId) {
        return getSession().flatMap(session ->
            session.find(Attachment.class, uniqId)
                .onItem().ifNotNull().transformToUni(entity ->
                    session.remove(entity).replaceWith(true))
                .onItem().ifNull().continueWith(false)
        ).onFailure().invoke(e ->
            System.err.println("[Attachment] deleteFromDb error: " + e.getMessage())
        );
    }

    // #endregion

    // #region HELPERS

    private String getExt(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot != -1 ? filename.substring(dot) : "";
    }

    // #endregion
}
