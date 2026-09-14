package com.aisolutions.jobtaskmanagement.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * Plain domain object for m07SystemParameters. Mapped by hand from raw
 * SqlClient rows (no Hibernate/Panache).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemParameter {

    private Long uniqId;
    private String entryStaff;
    private LocalDateTime entryDate;
    private String lastEditStaff;
    private LocalDateTime lastEditDate;
    private String parameter;
    private String parameterValue;
    private String parameterDescription;
}
