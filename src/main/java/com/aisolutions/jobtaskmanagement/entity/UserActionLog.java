package com.aisolutions.jobtaskmanagement.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Maps to m07UserActionLog — shared audit log table across all modules. */
@Entity
@Table(name = "m07UserActionLog")
@Getter
@Setter
public class UserActionLog extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId")
    private Long uniqId;

    @Column(name = "StaffId", length = 25)
    private String staffId;

    @Column(name = "Module", length = 25)
    private String module;

    @Column(name = "ReferenceNo", length = 45)
    private String referenceNo;

    @Column(name = "Action", length = 25)
    private String action;

    @Column(name = "LogDate")
    private LocalDateTime logDate;

    @Column(name = "DeviceName", length = 45)
    private String deviceName;

    @Column(name = "DeviceIPAddress", length = 25)
    private String deviceIPAddress;

    @Column(name = "DeviceSerialNo", length = 50)
    private String deviceSerialNo;

    @Column(name = "Remarks", length = 255)
    private String remarks;
}
