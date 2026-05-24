package com.aisolutions.jobtaskmanagement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class JobTaskDTO {

    // ─── Staff summary ──────────────────────────────────────────────────────

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StaffSummary {
        private Long   staffCode;      // m03Staff.Code
        private String staffId;        // m03Staff.StaffId (varchar)
        private String name;
        private String department;
        private String appointment;
        private String avatarColor;
    }

    // ─── Full task response ─────────────────────────────────────────────────

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JobTaskResponse {
        private Long          uniqId;
        private String        jobTaskId;
        private String        taskTitle;
        private String        taskType;
        private String        taskDescription;
        private String        priority;
        private String        jobStatus;
        private LocalDateTime dueDate;
        private LocalDateTime startedDate;
        private LocalDateTime completedDate;
        private BigDecimal    estimatedHours;
        private BigDecimal    actualHours;
        private String        remarks;
        private String        progressRemarks;
        private String        attachmentPath;
        private String        entryStaff;
        private LocalDateTime entryDate;
        private String        lastEditStaff;
        private LocalDateTime lastEdtiDate;
        private StaffSummary  assignor;
        private StaffSummary  assignee;
    }

    // ─── Create request ─────────────────────────────────────────────────────

    @Data
    public static class CreateJobTaskRequest {
        private String        taskTitle;
        private String        taskType;
        private String        taskDescription;
        private String       assignorStaffId;   // m03Staff.StaffId (varchar)
        private String       assigneeStaffId;
        private String        priority;
        private java.time.LocalDate dueDate;
        private BigDecimal    estimatedHours;
        private String        entryStaff;
    }

    // ─── Update request ─────────────────────────────────────────────────────

    @Data
    public static class UpdateJobTaskRequest {
        private String        taskTitle;
        private String        taskType;
        private String        taskDescription;
        private String       assigneeStaffId;
        private String        priority;
        private java.time.LocalDate dueDate;
        private BigDecimal    estimatedHours;
        private BigDecimal    actualHours;
        private String        remarks;
        private String        lastEditStaff;
    }

    // ─── Reassign (assignor only) ───────────────────────────────────────────

    @Data
    public static class ReassignRequest {
        private String newAssigneeStaffId;
        private String lastEditStaff;
    }

    // ─── Reschedule (assignor only) ─────────────────────────────────────────

    @Data
    public static class RescheduleRequest {
        private java.time.LocalDate newDueDate;
        private String lastEditStaff;
    }

    // ─── Progress remarks update (assignee only) ────────────────────────────

    @Data
    public static class UpdateProgressRemarksRequest {
        private String progressRemarks;
        private String lastEditStaff;
    }

    // ─── Status update ──────────────────────────────────────────────────────

    @Data
    public static class UpdateStatusRequest {
        private String jobStatus;
        private String lastEditStaff;
        /** User-supplied started date (optional, set when moving to In Progress) */
        private java.time.LocalDate startedDate;
        /** User-supplied completed date (optional, set when moving to Completed) */
        private java.time.LocalDate completedDate;
    }
}
