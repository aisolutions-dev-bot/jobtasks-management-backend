package com.aisolutions.jobtaskmanagement.client;

import com.aisolutions.jobtaskmanagement.dto.SystemParameterDTO;
import com.aisolutions.jobtaskmanagement.service.auth.AuthHeaderFactory;

import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * REST client for m07SystemParameters lookups via the org-api.
 *
 * GET /api/system-parameters?parameter=ATTACHMENT-MAIN-URL
 * GET /api/system-parameters/batch?parameters=ATTACHMENT-MAIN-URL,ATTACHMENT-PATH-JOBTASKS
 */
@Path("/api/system-parameters")
@RegisterRestClient(configKey = "organization-api")
@RegisterClientHeaders(AuthHeaderFactory.class)
public interface SystemParameterClient {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    Uni<SystemParameterDTO> getByParameter(@QueryParam("parameter") String parameter);

    @GET
    @Path("/batch")
    @Produces(MediaType.APPLICATION_JSON)
    Uni<List<SystemParameterDTO>> getBatch(@QueryParam("parameters") List<String> parameters);
}
