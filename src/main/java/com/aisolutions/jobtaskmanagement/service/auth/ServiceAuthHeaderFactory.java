package com.aisolutions.jobtaskmanagement.service.auth;

import com.aisolutions.jobtaskmanagement.config.ServiceAccountConfig;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Injects Basic Auth for service-to-service calls to org-api.
 * Used by SystemParameterClient — no incoming request context needed.
 */
@ApplicationScoped
public class ServiceAuthHeaderFactory implements ClientHeadersFactory {

    @Inject
    ServiceAccountConfig serviceAccountConfig;

    @Override
    public MultivaluedMap<String, String> update(
            MultivaluedMap<String, String> incoming,
            MultivaluedMap<String, String> clientOutgoing) {

        String credentials = serviceAccountConfig.username() + ":" + serviceAccountConfig.password();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        clientOutgoing.putSingle("Authorization", "Basic " + encoded);
        return clientOutgoing;
    }
}
