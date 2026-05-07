package com.aisolutions.jobtaskmanagement.dto;

import lombok.Data;

@Data
public class GroupAuthorityAccessDTO {
    private Long uniqId;
    private String groupAuthority;
    private String moduleId;
    private String accessCode;
    private String accessName;
    private Boolean accessValue;
    private String entryStaff;
    private String lastEditStaff;
}
