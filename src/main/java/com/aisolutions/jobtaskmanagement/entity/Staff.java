package com.aisolutions.jobtaskmanagement.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Read-only mapping of m03Staff.
 *
 * PK is Code (bigint auto_increment).
 * StaffId is a separate varchar(25) unique field (e.g. "T6923", "SUPERDREW").
 * AssignorStaffID / AssigneeStaffID in m24JobTasks reference Code (not StaffId).
 */
@Entity
@Table(name = "m03Staff")
@Getter
@Setter
public class Staff extends PanacheEntityBase {

    @Id
    @Column(name = "Code")
    private Long code;

    @Column(name = "StaffId", unique = true, length = 25)
    private String staffId;

    @Column(name = "Name")
    private String name;

    @Column(name = "Department", length = 25)
    private String department;

    @Column(name = "Appointment")
    private String appointment;

    @Column(name = "AvatarColor", length = 10)
    private String avatarColor;
}
