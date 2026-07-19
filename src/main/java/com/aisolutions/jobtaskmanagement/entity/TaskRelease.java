package com.aisolutions.jobtaskmanagement.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Maps to m24TaskRelease.
 *
 * UniqId = auto_increment PK (bigint)
 * ReleaseId = varchar(50) unique release code
 */
@Entity
@Table(name = "m24TaskRelease")
@Getter
@Setter
public class TaskRelease extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId")
    private Long uniqId;

    @Column(name = "ReleaseId", length = 50)
    private String releaseId;

    @Column(name = "ReleaseDate")
    private LocalDateTime releaseDate;

    @Column(name = "ReleaseVersion", length = 50)
    private String releaseVersion;

    @Column(name = "ReleaseRemarks", length = 2000)
    private String releaseRemarks;

    @Column(name = "EntryStaff", length = 25)
    private String entryStaff;

    @Column(name = "EntryDate")
    private LocalDateTime entryDate;

    @Column(name = "LastEditStaff", length = 25)
    private String lastEditStaff;

    @Column(name = "LastEditDate")
    private LocalDateTime lastEditDate;
}
