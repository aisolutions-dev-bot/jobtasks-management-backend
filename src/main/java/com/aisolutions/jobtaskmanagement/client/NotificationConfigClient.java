package com.aisolutions.jobtaskmanagement.client;

import com.aisolutions.jobtaskmanagement.dto.NotificationConfigDTO;
import com.aisolutions.jobtaskmanagement.service.auth.ServiceAuthHeaderFactory;

import io.smallrye.mutiny.Uni;

import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Internal REST client for the org-api's notification channel toggles
 * (NOTIFICATION-EMAIL / NOTIFICATION-SMS / NOTIFICATION-WHATSAPP).
 * Uses a dedicated service account (Basic Auth) — safe to call outside of
 * request context (e.g. startup).
 */
@Path("/api/system-parameters")
@RegisterRestClient(configKey = "organization-api")
@RegisterClientHeaders(ServiceAuthHeaderFactory.class)
public interface NotificationConfigClient {

    @GET
    @Path("/notifications")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<NotificationConfigDTO> getNotificationConfig();
}
