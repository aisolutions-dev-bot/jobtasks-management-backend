package com.aisolutions.jobtaskmanagement.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Plain domain object for m07UserActionLog — shared audit log table across
 * all modules. Mapped by hand from raw SqlClient rows (no Hibernate/Panache).
 */
@Getter
@Setter
public class UserActionLog {

    private Long uniqId;
    private String staffId;
    private String module;
    private String referenceNo;
    private String action;
    private LocalDateTime logDate;
    private String deviceName;
    private String deviceIPAddress;
    private String deviceSerialNo;
    private String remarks;
}
