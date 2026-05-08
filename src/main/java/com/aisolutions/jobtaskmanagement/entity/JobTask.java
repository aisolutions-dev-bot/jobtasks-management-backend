package com.aisolutions.jobtaskmanagement.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps to m24JobTasks.
 *
 * UniqID  = auto_increment PK (bigint)
 * JobTaskId = varchar(25) display code e.g. "JT-2026-0001"
 * JobStatus = status column (NOT "Status")
 * AssignorStaffID / AssigneeStaffID = int FK → m03Staff.Code
 * LastEdtiDate = typo in DB column name — preserved as-is
 * No IsActive column — soft delete = set JobStatus = 'Cancelled'
 */
@Entity
@Table(name = "m24JobTasks")
@Getter
@Setter
public class JobTask extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqID")
    private Long uniqId;

    @Column(name = "JobTaskId", length = 25)
    private String jobTaskId;

    @Column(name = "TaskTitle", length = 200)
    private String taskTitle;

    @Column(name = "TaskType", length = 50)
    private String taskType;

    @Column(name = "TaskDescription", length = 5000)
    private String taskDescription;

    @Column(name = "AssignorStaffID", length = 25)
    private String assignorStaffId;

    @Column(name = "AssigneeStaffID", length = 25)
    private String assigneeStaffId;

    @Column(name = "Priority", length = 20)
    private String priority;

    @Column(name = "JobStatus", length = 20)
    private String jobStatus;

    @Column(name = "DueDate")
    private LocalDateTime dueDate;

    @Column(name = "StartedDate")
    private LocalDateTime startedDate;

    @Column(name = "CompletedDate")
    private LocalDateTime completedDate;

    @Column(name = "EstimatedHours", precision = 6, scale = 2)
    private BigDecimal estimatedHours;

    @Column(name = "ActualHours", precision = 6, scale = 2)
    private BigDecimal actualHours;

    @Column(name = "Remarks", length = 2000)
    private String remarks;

    @Column(name = "AttachmentPath", length = 500)
    private String attachmentPath;

    @Column(name = "EntryStaff", length = 25)
    private String entryStaff;

    @Column(name = "EntryDate")
    private LocalDateTime entryDate;

    @Column(name = "LastEditStaff", length = 25)
    private String lastEditStaff;

    /** DB column name has a typo: LastEdtiDate (not LastEditDate) */
    @Column(name = "LastEdtiDate")
    private LocalDateTime lastEdtiDate;
}
