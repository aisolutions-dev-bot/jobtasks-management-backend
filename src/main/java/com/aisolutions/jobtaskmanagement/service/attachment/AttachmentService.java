package com.aisolutions.jobtaskmanagement.service.attachment;

import com.aisolutions.jobtaskmanagement.dto.AttachmentDTO;
import com.aisolutions.jobtaskmanagement.entity.Attachment;
import com.aisolutions.jobtaskmanagement.repository.AttachmentRepository;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Attachment service for the JobTasks module.
 *
 * FTP path is built entirely from application.properties (no org-api call):
 *   ftp.base-path       → e.g. /test.borneochemicalintl.com/pms-attachments
 *   ftp.jobtasks-folder → e.g. JOBTASKS
 *
 * Final path per task:
 *   {ftp.base-path}/{ftp.jobtasks-folder}/{jobTaskId}/{uuid-file.ext}
 */
@ApplicationScoped
public class AttachmentService {

    private static final List<String> ALLOWED_EXTENSIONS = List.of(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx",
        ".jpg", ".jpeg", ".png", ".gif", ".txt", ".md"
    );
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024; // 20 MB

    @Inject
    AttachmentRepository attachmentRepository;

    @Inject
    FTPStorageService ftpStorageService;

    // ── GET ───────────────────────────────────────────────────────────────────

    public Uni<List<AttachmentDTO>> getAttachments(String jobTaskId) {
        return attachmentRepository.findByModuleAndReference("JOBTASKS", jobTaskId);
    }

    public Uni<Attachment> getAttachmentMeta(Long uniqId) {
        return attachmentRepository.findByIdMeta(uniqId);
    }

    // ── DOWNLOAD ──────────────────────────────────────────────────────────────

    public Uni<byte[]> downloadFile(Long uniqId) {
        return attachmentRepository.downloadFileContent(uniqId);
    }

    // ── UPLOAD ────────────────────────────────────────────────────────────────

    public Uni<AttachmentDTO> uploadFile(
            String jobTaskId,
            String originalName,
            String contentType,
            byte[] fileData,
            String entryStaff) {

        // Validate
        String err = validate(originalName, fileData);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }

        // Build directory from config (no org-api call)
        String directoryPath = ftpStorageService.buildDirectory(jobTaskId);

        System.out.println("[AttachmentService] Uploading to: " + directoryPath);

        // Step 1: Upload to FTP (runs on worker thread via vertx.executeBlocking)
        // Step 2: Persist metadata in a DB transaction (only after FTP succeeds)
        // Keeping FTP outside the DB transaction avoids holding a connection open
        // while waiting for network I/O.
        return ftpStorageService.uploadFile(fileData, directoryPath, originalName)
            .flatMap(remotePath -> Panache.withTransaction(() ->
                attachmentRepository.persistAttachmentMeta(
                    remotePath,
                    "JOBTASKS",
                    jobTaskId,
                    originalName,
                    contentType,
                    (long) fileData.length,
                    entryStaff
                )
            ))
            .map(this::toDTO);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    public Uni<Boolean> deleteAttachment(Long uniqId) {
        return Panache.withTransaction(() -> attachmentRepository.deleteAttachment(uniqId));
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private String validate(String originalName, byte[] fileData) {
        if (originalName == null || originalName.isBlank()) return "File name is required";
        if (fileData == null || fileData.length == 0)       return "File data is empty";
        if (fileData.length > MAX_FILE_SIZE)                return "File exceeds 20 MB limit";
        String ext = getExt(originalName).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext))
            return "File type not allowed: " + ext + ". Allowed: " + String.join(", ", ALLOWED_EXTENSIONS);
        return null;
    }

    private String getExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot != -1 ? filename.substring(dot) : "";
    }

    private AttachmentDTO toDTO(Attachment a) {
        return new AttachmentDTO(
            a.getUniqId(), a.getModuleType(), a.getReferenceCode(),
            a.getFileName(), a.getOriginalName(), a.getFileSize(),
            a.getStorageType(), a.getContentType(), a.getFileExtension(),
            a.getFilePath(), a.getDescription(), a.getUploadSource(),
            a.getEntryStaff(), a.getEntryDate()
        );
    }
}
