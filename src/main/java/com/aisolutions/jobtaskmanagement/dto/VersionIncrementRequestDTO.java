package com.aisolutions.jobtaskmanagement.dto;

import lombok.Data;

@Data
public class VersionIncrementRequestDTO {
    private String releaseType;

    public VersionIncrementRequestDTO() {}

    public VersionIncrementRequestDTO(String releaseType) {
        this.releaseType = releaseType;
    }
}
