package com.aisolutions.jobtaskmanagement.client;

import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Fetches org-api's public JWK Set, published unauthenticated at
 * /.well-known/jwks.json, so JwtSignatureVerifier can verify JWT signatures
 * locally instead of trusting an unverified base64 decode of the payload.
 */
@Path("/.well-known/jwks.json")
@RegisterRestClient(configKey = "organization-api")
public interface JwksClient {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    Uni<String> fetchJwksJson();
}
