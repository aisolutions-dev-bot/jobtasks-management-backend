package com.aisolutions.jobtaskmanagement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public class TaskReleaseDTO {

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TaskReleaseResponse {
        private Long          uniqId;
        private String        releaseId;
        private LocalDateTime releaseDate;
        private String        releaseVersion;
        private String        releaseRemarks;
        private String        entryStaff;
        private LocalDateTime entryDate;
        private String        lastEditStaff;
        private LocalDateTime lastEditDate;
        private Long          taskCount;
    }

    @Data
    public static class CreateTaskReleaseRequest {
        private String        releaseId;
        private java.time.LocalDate releaseDate;
        private String        releaseVersion;
        private String        releaseRemarks;
        private String        entryStaff;
        private List<Long>    jobTaskIds;   // m24JobTasks.UniqID values to attach to this release
    }
}
