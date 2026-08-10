package com.aisolutions.jobtaskmanagement.config;

import io.smallrye.config.ConfigMapping;
import jakarta.validation.constraints.NotBlank;

/**
 * Configurable SMTP settings for email notifications.
 * Set these in application.properties or environment variables.
 */
@ConfigMapping(prefix = "app.email")
public interface SmtpConfig {

    @NotBlank
    String host();

    int port();

    @NotBlank
    String username();

    @NotBlank
    String password();

    @NotBlank
    String senderEmail();
}
