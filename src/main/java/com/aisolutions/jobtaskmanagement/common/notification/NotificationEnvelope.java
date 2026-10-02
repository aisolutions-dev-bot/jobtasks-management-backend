package com.aisolutions.jobtaskmanagement.common.notification;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Versioned tenant context and delivery content carried by notification topics. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationEnvelope(
        String notificationId,
        String companyId,
        String recipient,
        String subject,
        String body,
        String templateName,
        String languageCode,
        List<Map<String, Object>> templateComponents) {

    /** Validates required event identity and channel-independent recipient fields. */
    public NotificationEnvelope {
        notificationId = requireText(notificationId, "notificationId");
        companyId = requireText(companyId, "companyId");
        recipient = requireText(recipient, "recipient");
        templateComponents = templateComponents == null ? List.of() : List.copyOf(templateComponents);
        if (isBlank(body) && isBlank(templateName)) {
            throw new IllegalArgumentException("body or templateName is required");
        }
    }

    /** Rejects missing event fields without silently assigning another tenant. */
    private static String requireText(String value, String fieldName) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    /** Reports whether a value is absent or contains only whitespace. */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
