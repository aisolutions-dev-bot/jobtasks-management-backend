package com.aisolutions.jobtaskmanagement.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * Plain domain object for m10Attachments — the shared attachment table used
 * across all modules. Mapped by hand from raw SqlClient rows (no
 * Hibernate/Panache).
 *
 * For JobTasks:
 *   ModuleType    = "JOBTASKS"
 *   ReferenceCode = JobTaskId (e.g. "JT-2026-0001")
 *   FilePath      = full FTP path: {ATTACHMENT-MAIN-URL}/{ATTACHMENT-PATH-JOBTASKS}/{JobTaskId}/{uuid-file.ext}
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Attachment {

    private Long uniqId;
    private String moduleType;
    private String referenceCode;
    private String fileName;
    private String originalName;
    private Long fileSize;
    private String storageType;
    private String contentType;
    private String fileExtension;
    private String filePath;
    private byte[] fileData;
    private String description;
    private String uploadSource = "WEB";
    private String entryStaff;
    private LocalDateTime entryDate;
    private String lastEditStaff;
    private LocalDateTime lastEditDate;
}
