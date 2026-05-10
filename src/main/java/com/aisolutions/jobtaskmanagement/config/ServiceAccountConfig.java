package com.aisolutions.jobtaskmanagement.config;

import io.smallrye.config.ConfigMapping;
import jakarta.validation.constraints.NotBlank;

@ConfigMapping(prefix = "app.service")
public interface ServiceAccountConfig {

    @NotBlank
    String username();

    @NotBlank
    String password();
}
