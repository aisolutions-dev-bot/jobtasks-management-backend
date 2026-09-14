package com.aisolutions.jobtaskmanagement.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * Plain domain object for a read-only mapping of m03Staff. Mapped by hand
 * from raw SqlClient rows (no Hibernate/Panache).
 *
 * PK is Code (bigint auto_increment).
 * StaffId is a separate varchar(25) unique field (e.g. "T6923", "SUPERDREW").
 * AssignorStaffID / AssigneeStaffID in m24JobTasks reference Code (not StaffId).
 */
@Getter
@Setter
public class Staff {

    private Long code;
    private String staffId;
    private String name;
    private String department;
    private String appointment;
    private String avatarColor;
    private String telMobile;
    private String emailCompany;
}
