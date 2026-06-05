package com.aisolutions.jobtaskmanagement.service.attachment;

import com.aisolutions.jobtaskmanagement.dto.AttachmentDTO;
import com.aisolutions.jobtaskmanagement.entity.Attachment;
import com.aisolutions.jobtaskmanagement.repository.AttachmentRepository;
import com.aisolutions.jobtaskmanagement.service.SystemParameterService;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Attachment service for the JobTasks module.
 *
 * FTP credentials and paths are loaded from m07SystemParameter at runtime via
 * {@link SystemParameterService}. The required parameters are:
 *   ATTACHMENT-MODE          → must be "FTP"
 *   ATTACHMENT-MAIN-URL      → e.g. /test.borneochemicalintl.com
 *   ATTACHMENT-PATH-JOBTASKS → e.g. JOBTASKS
 *   FTP-HOST, FTP-USERNAME, FTP-PASSWORD
 *
 * Final remote path per task:
 *   {ATTACHMENT-MAIN-URL}/{ATTACHMENT-PATH-JOBTASKS}/{jobTaskId}/{uuid-file.ext}
 */
@ApplicationScoped
public class AttachmentService {

    private static final Logger LOG           = Logger.getLogger(AttachmentService.class);
    /** Fixed module-level folder on the FTP server — never changes for this module. */
    private static final String MODULE_FOLDER = "jobtasks-attachments";

    private static final List<String> ALLOWED_EXTENSIONS = List.of(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx",
        ".jpg", ".jpeg", ".png", ".gif", ".txt", ".md"
    );
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024; // 20 MB

    @Inject
    AttachmentRepository attachmentRepository;

    @Inject
    FTPStorageService ftpStorageService;

    @Inject
    SystemParameterService systemParameterService;

    // ── GET ───────────────────────────────────────────────────────────────────

    public Uni<List<AttachmentDTO>> getAttachments(String jobTaskId) {
        return attachmentRepository.findByModuleAndReference("JOBTASKS", jobTaskId);
    }

    // ── DOWNLOAD ──────────────────────────────────────────────────────────────

    /** Carries file bytes together with the metadata needed for response headers. */
    public record DownloadResult(byte[] bytes, String originalName, String contentType) {}

    /**
     * Fetch attachment metadata and file bytes in a single flow.
     * Uses cached FTP config — no extra DB query for credentials.
     */
    @SuppressWarnings("null")
    public Uni<DownloadResult> downloadAttachment(Long uniqId) {
        return systemParameterService.loadFtpConfig()
            .flatMap(config ->
                attachmentRepository.findByIdMeta(uniqId).flatMap(a -> {
                    if (a == null) {
                        return Uni.createFrom().failure(
                            new RuntimeException("Attachment not found: " + uniqId));
                    }
                    if ("FTP".equalsIgnoreCase(a.getStorageType())) {
                        if (a.getFilePath() == null || a.getFilePath().isBlank()) {
                            return Uni.createFrom().failure(
                                new RuntimeException("FilePath missing for attachment: " + uniqId));
                        }
                        return ftpStorageService.downloadFile(a.getFilePath(), config)
                            .map(bytes -> new DownloadResult(bytes, a.getOriginalName(), a.getContentType()));
                    }
                    // LOCAL fallback
                    byte[] data = a.getFileData();
                    if (data == null) {
                        return Uni.createFrom().failure(
                            new RuntimeException("No file data in DB for attachment: " + uniqId));
                    }
                    return Uni.createFrom().item(
                        new DownloadResult(data, a.getOriginalName(), a.getContentType()));
                })
            );
    }

    // ── UPLOAD ────────────────────────────────────────────────────────────────

    /**
     * Upload a file for a given jobTaskId.
     *
     * Flow:
     *   1. Validate file (extension, size)
     *   2. Load FtpConfig from m07SystemParameter (checks ATTACHMENT-MODE = FTP)
     *   3. Upload bytes to FTP
     *   4. Persist metadata to m10Attachments in a DB transaction
     */
    public Uni<AttachmentDTO> uploadFile(
            String jobTaskId,
            String originalName,
            String contentType,
            byte[] fileData,
            String entryStaff) {

        String err = validate(originalName, fileData);
        if (err != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(err));
        }

        return systemParameterService.loadFtpConfig()
            .flatMap(config -> {
                String directoryPath = config.buildDirectory(MODULE_FOLDER, jobTaskId);
                LOG.infof("[Attachment] Uploading file for task: %s", jobTaskId);

                // Step 1: upload to FTP (blocking I/O on worker thread)
                // Step 2: persist metadata (DB transaction), only after FTP succeeds
                return ftpStorageService.uploadFile(fileData, directoryPath, originalName, config)
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
                    ));
            })
            .map(this::toDTO);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    /**
     * Delete an attachment.
     *
     * Flow:
     *   1. Load FtpConfig from m07SystemParameter
     *   2. Retrieve attachment metadata
     *   3. Delete from FTP (if FTP storage type)
     *   4. Delete metadata from DB in a transaction
     */
    public Uni<Boolean> deleteAttachment(Long uniqId) {
        return systemParameterService.loadFtpConfig()
            .flatMap(config ->
                attachmentRepository.findByIdMeta(uniqId).flatMap(a -> {
                    if (a == null) return Uni.createFrom().item(false);

                    Uni<Boolean> ftpDelete =
                        ("FTP".equalsIgnoreCase(a.getStorageType()) && a.getFilePath() != null)
                            ? ftpStorageService.deleteFile(a.getFilePath(), config)
                            : Uni.createFrom().item(true);

                    return ftpDelete.flatMap(ignored ->
                        Panache.withTransaction(() ->
                            attachmentRepository.deleteFromDb(uniqId)
                        )
                    );
                })
            );
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
