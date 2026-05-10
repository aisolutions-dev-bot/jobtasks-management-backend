package com.aisolutions.jobtaskmanagement.repository;

import com.aisolutions.jobtaskmanagement.dto.AttachmentDTO;
import com.aisolutions.jobtaskmanagement.entity.Attachment;
import com.aisolutions.jobtaskmanagement.service.attachment.FTPStorageService;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reactive Panache repository for m10Attachments (JobTasks module).
 *
 * Storage strategy: FTP only — FileData column is always null.
 * The full remote path is stored in FilePath.
 *
 * The directoryPath is built by AttachmentService from m07SystemParameters
 * so this repo never hardcodes any path.
 */
@ApplicationScoped
@WithSession
public class AttachmentRepository implements PanacheRepositoryBase<Attachment, Long> {

    @Inject
    FTPStorageService ftpStorageService;

    // #region QUERY

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
        ).onFailure().invoke(e -> System.err.println("[Attachment] findByModuleAndReference error: " + e.getMessage()));
    }

    public Uni<Attachment> findByIdMeta(Long uniqId) {
        return getSession().flatMap(session -> session.find(Attachment.class, uniqId));
    }

    // #endregion

    // #region CREATE

    /**
     * Upload file to FTP and persist metadata to m10Attachments.
     *
     * @param directoryPath  full FTP directory, e.g. /pms-attachments/JOBTASKS/JT-2026-0001
     * @param moduleType     "JOBTASKS"
     * @param referenceCode  jobTaskId, e.g. "JT-2026-0001"
     */
    public Uni<Attachment> createAttachment(
            String directoryPath,
            String moduleType,
            String referenceCode,
            String originalName,
            String contentType,
            Long   fileSize,
            byte[] fileData,
            String currentUser) {

        return ftpStorageService.uploadFile(fileData, directoryPath, originalName)
            .flatMap(remotePath ->
                getSession().flatMap(session -> {
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
                    a.setEntryDate(LocalDateTime.now());
                    return session.persist(a).replaceWith(a);
                })
            )
            .onFailure().invoke(e -> System.err.println("[Attachment] createAttachment error: " + e.getMessage()));
    }

    // #endregion

    // #region DOWNLOAD

    public Uni<byte[]> downloadFileContent(Long uniqId) {
        return findByIdMeta(uniqId).flatMap(a -> {
            if (a == null) return Uni.createFrom().failure(new RuntimeException("Attachment not found: " + uniqId));
            if ("FTP".equalsIgnoreCase(a.getStorageType())) {
                if (a.getFilePath() == null || a.getFilePath().isBlank())
                    return Uni.createFrom().failure(new RuntimeException("FilePath missing for attachment: " + uniqId));
                return ftpStorageService.downloadFile(a.getFilePath());
            } else if ("LOCAL".equalsIgnoreCase(a.getStorageType())) {
                byte[] data = a.getFileData();
                if (data == null) return Uni.createFrom().failure(new RuntimeException("No file data in DB: " + uniqId));
                return Uni.createFrom().item(data);
            }
            return Uni.createFrom().failure(new RuntimeException("Unknown StorageType: " + a.getStorageType()));
        });
    }

    // #endregion

    // #region DELETE

    public Uni<Boolean> deleteAttachment(Long uniqId) {
        return findByIdMeta(uniqId).flatMap(a -> {
            if (a == null) return Uni.createFrom().item(false);

            Uni<Boolean> deleteFromFtp =
                ("FTP".equalsIgnoreCase(a.getStorageType()) && a.getFilePath() != null)
                    ? ftpStorageService.deleteFile(a.getFilePath())
                    : Uni.createFrom().item(true);

            return deleteFromFtp.flatMap(ignored ->
                getSession().flatMap(session ->
                    session.find(Attachment.class, uniqId)
                        .onItem().ifNotNull().transformToUni(entity -> session.remove(entity).replaceWith(true))
                        .onItem().ifNull().continueWith(false)
                )
            );
        }).onFailure().invoke(e -> System.err.println("[Attachment] deleteAttachment error: " + e.getMessage()));
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
