package com.aisolutions.jobtaskmanagement.resource;

import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.JobTaskResponse;
import com.aisolutions.jobtaskmanagement.dto.TaskReleaseDTO.*;
import com.aisolutions.jobtaskmanagement.service.TaskReleaseService;
import com.aisolutions.jobtaskmanagement.service.auth.AccessControlService;

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
    AccessControlService accessControlService;

    /**
     * GET /api/v1/task-releases
     * Requires a2402.
     */
    @GET
    public Uni<Response> list() {
        return service.listReleases(accessControlService.getCurrentGroupAuthority())
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
        List<String> statusList = statuses == null || statuses.isBlank()
                ? List.of("Tested")
                : Arrays.stream(statuses.split(",")).map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toList());
        return service.getReleasableJobTasks(accessControlService.getCurrentGroupAuthority(), statusList, search)
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
     * GET /api/v1/task-releases/next-release-id
     * Requires a2402.01. Preview only — the authoritative ID is assigned at creation time.
     */
    @GET
    @Path("/next-release-id")
    public Uni<Response> previewNextReleaseId() {
        return service.previewNextReleaseId(accessControlService.getCurrentGroupAuthority())
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
        return service.create(accessControlService.getCurrentGroupAuthority(), req)
                .onItem().transform(r -> Response.status(Response.Status.CREATED).entity(r).build())
                .onFailure(ForbiddenException.class).recoverWithItem(e ->
                        Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", e.getMessage())).build())
                .onFailure().recoverWithItem(e -> {
                        String msg = e.getMessage() != null ? e.getMessage()
                            : (e.getCause() != null ? e.getCause().getMessage() : e.getClass().getSimpleName());
                        return Response.serverError().entity(Map.of("error", msg)).build();
                });
    }

    /**
     * GET /api/v1/task-releases/{id}
     * Requires a2402.
     */
    @GET
    @Path("/{id}")
    public Uni<Response> getDetail(@PathParam("id") Long id) {
        return service.getDetail(accessControlService.getCurrentGroupAuthority(), id)
                .onItem().transform(r -> Response.ok(r).build())
                .onFailure(ForbiddenException.class).recoverWithItem(e ->
                        Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", e.getMessage())).build())
                .onFailure(NotFoundException.class).recoverWithItem(e ->
                        Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build())
                .onFailure().recoverWithItem(e -> {
                        String msg = e.getMessage() != null ? e.getMessage()
                            : (e.getCause() != null ? e.getCause().getMessage() : e.getClass().getSimpleName());
                        return Response.serverError().entity(Map.of("error", msg)).build();
                });
    }

    /**
     * PUT /api/v1/task-releases/{id}
     * Requires a2402.01.
     */
    @PUT
    @Path("/{id}")
    public Uni<Response> update(@PathParam("id") Long id, UpdateTaskReleaseRequest req) {
        return service.update(accessControlService.getCurrentGroupAuthority(), id, req)
                .onItem().transform(r -> Response.ok(r).build())
                .onFailure(ForbiddenException.class).recoverWithItem(e ->
                        Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", e.getMessage())).build())
                .onFailure(NotFoundException.class).recoverWithItem(e ->
                        Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build())
                .onFailure(BadRequestException.class).recoverWithItem(e ->
                        Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build())
                .onFailure().recoverWithItem(e -> {
                        String msg = e.getMessage() != null ? e.getMessage()
                            : (e.getCause() != null ? e.getCause().getMessage() : e.getClass().getSimpleName());
                        return Response.serverError().entity(Map.of("error", msg)).build();
                });
    }

    /**
     * POST /api/v1/task-releases/{id}/tasks
     * Requires a2402.01.
     */
    @POST
    @Path("/{id}/tasks")
    public Uni<Response> addJobTasks(@PathParam("id") Long id, AddJobTasksRequest req) {
        return service.addJobTasks(accessControlService.getCurrentGroupAuthority(), id, req)
                .onItem().transform(r -> Response.ok(r).build())
                .onFailure(ForbiddenException.class).recoverWithItem(e ->
                        Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", e.getMessage())).build())
                .onFailure(NotFoundException.class).recoverWithItem(e ->
                        Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build())
                .onFailure().recoverWithItem(e -> {
                        String msg = e.getMessage() != null ? e.getMessage()
                            : (e.getCause() != null ? e.getCause().getMessage() : e.getClass().getSimpleName());
                        return Response.serverError().entity(Map.of("error", msg)).build();
                });
    }

    /**
     * DELETE /api/v1/task-releases/{id}/tasks/{jobTaskUniqId}
     * Requires a2402.01.
     */
    @DELETE
    @Path("/{id}/tasks/{jobTaskUniqId}")
    public Uni<Response> removeJobTask(@PathParam("id") Long id, @PathParam("jobTaskUniqId") Long jobTaskUniqId) {
        return service.removeJobTask(accessControlService.getCurrentGroupAuthority(), id, jobTaskUniqId)
                .onItem().transform(v -> Response.noContent().build())
                .onFailure(ForbiddenException.class).recoverWithItem(e ->
                        Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", e.getMessage())).build())
                .onFailure(NotFoundException.class).recoverWithItem(e ->
                        Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build())
                .onFailure(BadRequestException.class).recoverWithItem(e ->
                        Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build())
                .onFailure().recoverWithItem(e -> {
                        String msg = e.getMessage() != null ? e.getMessage()
                            : (e.getCause() != null ? e.getCause().getMessage() : e.getClass().getSimpleName());
                        return Response.serverError().entity(Map.of("error", msg)).build();
                });
    }

    /**
     * DELETE /api/v1/task-releases/{id}
     * Requires a2402.01.
     */
    @DELETE
    @Path("/{id}")
    public Uni<Response> delete(@PathParam("id") Long id) {
        return service.delete(accessControlService.getCurrentGroupAuthority(), id)
                .onItem().transform(v -> Response.noContent().build())
                .onFailure(ForbiddenException.class).recoverWithItem(e ->
                        Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", e.getMessage())).build())
                .onFailure(NotFoundException.class).recoverWithItem(e ->
                        Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build())
                .onFailure().recoverWithItem(e -> {
                        String msg = e.getMessage() != null ? e.getMessage()
                            : (e.getCause() != null ? e.getCause().getMessage() : e.getClass().getSimpleName());
                        return Response.serverError().entity(Map.of("error", msg)).build();
                });
    }
}
