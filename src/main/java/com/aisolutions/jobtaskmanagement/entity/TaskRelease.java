package com.aisolutions.jobtaskmanagement.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Plain domain object for m24TaskRelease — mapped by hand from raw
 * SqlClient rows (no Hibernate/Panache).
 *
 * UniqId = auto_increment PK (bigint)
 * ReleaseId = varchar(50) unique release code
 */
@Getter
@Setter
public class TaskRelease {

    private Long uniqId;
    private String releaseId;
    private LocalDateTime releaseDate;
    private String releaseVersion;
    private String releaseType;
    private String releaseRemarks;
    private String entryStaff;
    private LocalDateTime entryDate;
    private String lastEditStaff;
    private LocalDateTime lastEditDate;
}
