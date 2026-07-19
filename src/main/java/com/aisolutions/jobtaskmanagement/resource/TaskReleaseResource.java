package com.aisolutions.jobtaskmanagement.resource;

import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.JobTaskResponse;
import com.aisolutions.jobtaskmanagement.dto.TaskReleaseDTO.*;
import com.aisolutions.jobtaskmanagement.service.TaskReleaseService;
import com.aisolutions.jobtaskmanagement.service.auth.JwtClaimsExtractor;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST resource for Task Release management (Job Tasks module, mod24 / a2402).
 * Base path: /api/v1/task-releases
 */
@Path("/api/v1/task-releases")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TaskReleaseResource {

    @Inject
    TaskReleaseService service;

    @Inject
    JwtClaimsExtractor jwtClaimsExtractor;

    /**
     * GET /api/v1/task-releases
     * Requires a2402.
     */
    @GET
    public Uni<Response> list() {
        JwtClaimsExtractor.JwtClaims claims = jwtClaimsExtractor.extract();
        return service.listReleases(claims.groupAuthority())
                .onItem().transform(r -> Response.ok(r).build())
                .onFailure(ForbiddenException.class).recoverWithItem(e ->
                        Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", e.getMessage())).build())
                .onFailure().recoverWithItem(e -> {
                        String msg = e.getMessage() != null ? e.getMessage()
                            : (e.getCause() != null ? e.getCause().getMessage() : e.getClass().getSimpleName());
                        return Response.serverError().entity(Map.of("error", msg)).build();
                });
    }

    /**
     * GET /api/v1/task-releases/releasable-job-tasks
     * Requires a2402.
     */
    @GET
    @Path("/releasable-job-tasks")
    public Uni<Response> releasableJobTasks(
            @QueryParam("statuses") String statuses,
            @QueryParam("search") String search) {
        JwtClaimsExtractor.JwtClaims claims = jwtClaimsExtractor.extract();
        List<String> statusList = statuses == null || statuses.isBlank()
                ? List.of("Tested")
                : Arrays.stream(statuses.split(",")).map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toList());
        return service.getReleasableJobTasks(claims.groupAuthority(), statusList, search)
                .onItem().transform(r -> Response.ok(r).build())
                .onFailure(ForbiddenException.class).recoverWithItem(e ->
                        Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", e.getMessage())).build())
                .onFailure().recoverWithItem(e -> {
                        String msg = e.getMessage() != null ? e.getMessage()
                            : (e.getCause() != null ? e.getCause().getMessage() : e.getClass().getSimpleName());
                        return Response.serverError().entity(Map.of("error", msg)).build();
                });
    }

    /**
     * POST /api/v1/task-releases
     * Requires a2402.01.
     */
    @POST
    public Uni<Response> create(CreateTaskReleaseRequest req) {
        JwtClaimsExtractor.JwtClaims claims = jwtClaimsExtractor.extract();
        return service.create(claims.groupAuthority(), req)
                .onItem().transform(r -> Response.status(Response.Status.CREATED).entity(r).build())
                .onFailure(ForbiddenException.class).recoverWithItem(e ->
                        Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", e.getMessage())).build())
                .onFailure().recoverWithItem(e -> {
                        String msg = e.getMessage() != null ? e.getMessage()
                            : (e.getCause() != null ? e.getCause().getMessage() : e.getClass().getSimpleName());
                        return Response.serverError().entity(Map.of("error", msg)).build();
                });
    }
}
