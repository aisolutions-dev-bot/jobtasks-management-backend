package com.aisolutions.jobtaskmanagement.service.attachment;

import com.aisolutions.jobtaskmanagement.client.SystemParameterClient;
import com.aisolutions.jobtaskmanagement.dto.AttachmentDTO;
import com.aisolutions.jobtaskmanagement.dto.SystemParameterDTO;
import com.aisolutions.jobtaskmanagement.entity.Attachment;
import com.aisolutions.jobtaskmanagement.repository.AttachmentRepository;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;

/**
 * Attachment service for the JobTasks module.
 *
 * FTP directory is resolved dynamically from m07SystemParameters:
 *   ATTACHMENT-MAIN-URL       → e.g. /test.borneochemicalintl.com/pms-attachments
 *   ATTACHMENT-PATH-JOBTASKS  → e.g. JOBTASKS
 *
 * Final path per task:
 *   {ATTACHMENT-MAIN-URL}/{ATTACHMENT-PATH-JOBTASKS}/{jobTaskId}
 *   e.g. /test.borneochemicalintl.com/pms-attachments/JOBTASKS/JT-2026-0001
 */
@ApplicationScoped
public class AttachmentService {

    private static final String PARAM_MAIN_URL  = "ATTACHMENT-MAIN-URL";
    private static final String PARAM_SUBFOLDER = "ATTACHMENT-PATH-JOBTASKS";

    private static final List<String> ALLOWED_EXTENSIONS = List.of(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx",
        ".jpg", ".jpeg", ".png", ".gif", ".txt"
    );
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024; // 20 MB

    @Inject
    AttachmentRepository attachmentRepository;

    @Inject
    @RestClient
    SystemParameterClient systemParameterClient;

    // #region GET

    public Uni<List<AttachmentDTO>> getAttachments(String jobTaskId) {
        return attachmentRepository.findByModuleAndReference("JOBTASKS", jobTaskId);
    }

    public Uni<Attachment> getAttachmentMeta(Long uniqId) {
        return attachmentRepository.findByIdMeta(uniqId);
    }

    // #endregion

    // #region DOWNLOAD

    public Uni<byte[]> downloadFile(Long uniqId) {
        return attachmentRepository.downloadFileContent(uniqId);
    }

    // #endregion

    // #region UPLOAD

    public Uni<AttachmentDTO> uploadFile(
            String jobTaskId,
            String originalName,
            String contentType,
            byte[] fileData) {

        // Validate
        String err = validate(originalName, fileData);
        if (err != null) return Uni.createFrom().failure(new IllegalArgumentException(err));

        // 1. Fetch ATTACHMENT-MAIN-URL from org-api
        return systemParameterClient.getByParameter(PARAM_MAIN_URL)
            .onFailure().recoverWithItem(e -> {
                System.err.println("[AttachmentService] Cannot fetch " + PARAM_MAIN_URL + ": " + e.getMessage());
                return null;
            })
            .flatMap(mainUrlParam -> {
                String mainUrl = (mainUrlParam != null && mainUrlParam.getParameterValue() != null)
                    ? mainUrlParam.getParameterValue().trim()
                    : "";

                // 2. Fetch ATTACHMENT-PATH-JOBTASKS from org-api
                return systemParameterClient.getByParameter(PARAM_SUBFOLDER)
                    .onFailure().recoverWithItem(e -> {
                        System.err.println("[AttachmentService] Cannot fetch " + PARAM_SUBFOLDER + ": " + e.getMessage());
                        return null;
                    })
                    .flatMap(subfolderParam -> {
                        String subfolder = (subfolderParam != null && subfolderParam.getParameterValue() != null)
                            ? subfolderParam.getParameterValue().trim()
                            : "JOBTASKS";

                        // 3. Build directory path
                        // e.g. /test.borneochemicalintl.com/pms-attachments/JOBTASKS/JT-2026-0001
                        String directoryPath = mainUrl + "/" + subfolder + "/" + jobTaskId;

                        // 4. Upload via FTP + persist to DB (in a transaction)
                        return Panache.withTransaction(() ->
                            attachmentRepository.createAttachment(
                                directoryPath,
                                "JOBTASKS",
                                jobTaskId,
                                originalName,
                                contentType,
                                (long) fileData.length,
                                fileData,
                                null  // entryStaff — caller can pass via overload if needed
                            )
                        ).map(this::toDTO);
                    });
            });
    }

    /**
     * Overload that accepts entryStaff (staffId of uploader).
     */
    public Uni<AttachmentDTO> uploadFile(
            String jobTaskId,
            String originalName,
            String contentType,
            byte[] fileData,
            String entryStaff) {

        String err = validate(originalName, fileData);
        if (err != null) return Uni.createFrom().failure(new IllegalArgumentException(err));

        return systemParameterClient.getByParameter(PARAM_MAIN_URL)
            .onFailure().recoverWithItem(e -> null)
            .flatMap(mainUrlParam -> {
                String mainUrl = (mainUrlParam != null && mainUrlParam.getParameterValue() != null)
                    ? mainUrlParam.getParameterValue().trim() : "";

                return systemParameterClient.getByParameter(PARAM_SUBFOLDER)
                    .onFailure().recoverWithItem(e -> null)
                    .flatMap(subfolderParam -> {
                        String subfolder = (subfolderParam != null && subfolderParam.getParameterValue() != null)
                            ? subfolderParam.getParameterValue().trim() : "JOBTASKS";

                        String directoryPath = mainUrl + "/" + subfolder + "/" + jobTaskId;

                        return Panache.withTransaction(() ->
                            attachmentRepository.createAttachment(
                                directoryPath, "JOBTASKS", jobTaskId,
                                originalName, contentType,
                                (long) fileData.length, fileData, entryStaff
                            )
                        ).map(this::toDTO);
                    });
            });
    }

    // #endregion

    // #region DELETE

    public Uni<Boolean> deleteAttachment(Long uniqId) {
        return Panache.withTransaction(() -> attachmentRepository.deleteAttachment(uniqId));
    }

    // #endregion

    // #region HELPERS

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

    // #endregion
}
