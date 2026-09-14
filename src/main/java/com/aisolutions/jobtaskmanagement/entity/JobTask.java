package com.aisolutions.jobtaskmanagement.entity;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Plain domain object for m24JobTasks — mapped by hand from raw SqlClient
 * rows (no Hibernate/Panache; DB access is per-company routed via
 * {@code CompanyPoolManager}, incompatible with Hibernate's single ambient
 * session).
 *
 * UniqID  = auto_increment PK (bigint)
 * JobTaskId = varchar(25) display code e.g. "JT-2026-0001"
 * JobStatus = status column (NOT "Status")
 * AssignorStaffID / AssigneeStaffID = int FK → m03Staff.Code
 * LastEdtiDate = typo in DB column name — preserved as-is
 * No IsActive column — soft delete = set JobStatus = 'Cancelled'
 */
@Getter
@Setter
public class JobTask {

    private Long uniqId;
    private String jobTaskId;
    private String taskTitle;
    private String taskType;
    private String taskDescription;
    private String assignorStaffId;
    private String assigneeStaffId;
    private String priority;
    private String jobStatus;
    private LocalDateTime dueDate;
    private LocalDateTime startedDate;
    private LocalDateTime completedDate;
    private BigDecimal estimatedHours;
    private BigDecimal actualHours;
    private String remarks;
    private String progressRemarks;
    private String attachmentPath;
    private String releaseId;
    private String entryStaff;
    private LocalDateTime entryDate;
    private String lastEditStaff;

    /** DB column name has a typo: LastEdtiDate (not LastEditDate) */
    private LocalDateTime lastEdtiDate;
}
