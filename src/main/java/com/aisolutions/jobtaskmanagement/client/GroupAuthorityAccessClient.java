package com.aisolutions.jobtaskmanagement.client;

import com.aisolutions.jobtaskmanagement.dto.GroupAuthorityAccessDTO;
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

@Path("/api/group-authority-access")
@RegisterRestClient(configKey = "organization-api")
@RegisterClientHeaders(AuthHeaderFactory.class)
public interface GroupAuthorityAccessClient {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    Uni<List<GroupAuthorityAccessDTO>> getAccessByModule(
            @QueryParam("groupAuthority") String groupAuthority,
            @QueryParam("moduleId") String moduleId);
}
