package com.aisolutions.jobtaskmanagement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@RegisterForReflection
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AttachmentDTO {
    private Long          uniqId;
    private String        moduleType;
    private String        referenceCode;
    private String        fileName;
    private String        originalName;
    private Long          fileSize;
    private String        storageType;
    private String        contentType;
    private String        fileExtension;
    private String        filePath;
    private String        description;
    private String        uploadSource;
    private String        entryStaff;
    private LocalDateTime entryDate;
}
