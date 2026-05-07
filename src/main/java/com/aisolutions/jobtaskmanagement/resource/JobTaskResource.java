package com.aisolutions.jobtaskmanagement.resource;

import com.aisolutions.jobtaskmanagement.dto.JobTaskDTO.*;
import com.aisolutions.jobtaskmanagement.service.JobTaskService;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * REST resource for Job Task management.
 * Base path: /api/v1/job-tasks
 */
@Path("/api/v1/job-tasks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class JobTaskResource {

    @Inject
    JobTaskService service;

    /**
     * GET /api/v1/job-tasks/staff
     * Returns staff list for assignor/assignee dropdowns.
     * Must be declared before /{id} to avoid path conflict.
     */
    @GET
    @Path("/staff")
    public Uni<List<StaffSummary>> listStaff() {
        return service.listStaff();
    }

    /**
     * GET /api/v1/job-tasks?groupAuthority=X&staffCode=Y
     *
     * RBAC visibility:
     *   a2401.02=1 → ALL records
     *   a2401.02=0 + a2401.01=1 → same department as current user
     *   both 0 → only tasks where I am assignor or assignee
     */
    @GET
    public Uni<List<JobTaskResponse>> list(
            @QueryParam("groupAuthority") String groupAuthority,
            @QueryParam("staffCode") String staffCode) {
        return service.listWithRbac(groupAuthority, staffCode);
    }

    /**
     * GET /api/v1/job-tasks/{id}
     */
    @GET
    @Path("/{id}")
    public Uni<Response> getById(@PathParam("id") Long id) {
        return service.findById(id)
                .onItem().transform(r -> Response.ok(r).build())
                .onFailure(NotFoundException.class)
                .recoverWithItem(e -> Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", e.getMessage())).build());
    }

    /**
     * POST /api/v1/job-tasks
     */
    @POST
    public Uni<Response> create(CreateJobTaskRequest req) {
        return service.create(req)
                .onItem().transform(r -> Response.status(Response.Status.CREATED).entity(r).build())
                .onFailure().recoverWithItem(e ->
                        Response.serverError().entity(Map.of("error", e.getMessage())).build());
    }

    /**
     * PUT /api/v1/job-tasks/{id}
     */
    @PUT
    @Path("/{id}")
    public Uni<Response> update(@PathParam("id") Long id, UpdateJobTaskRequest req) {
        return service.update(id, req)
                .onItem().transform(r -> Response.ok(r).build())
                .onFailure().recoverWithItem(e ->
                        Response.serverError().entity(Map.of("error", e.getMessage())).build());
    }

    /**
     * PATCH /api/v1/job-tasks/{id}/status
     */
    @PATCH
    @Path("/{id}/status")
    public Uni<Response> updateStatus(@PathParam("id") Long id, UpdateStatusRequest req) {
        return service.updateStatus(id, req)
                .onItem().transform(r -> Response.ok(r).build())
                .onFailure().recoverWithItem(e ->
                        Response.serverError().entity(Map.of("error", e.getMessage())).build());
    }

    /**
     * DELETE /api/v1/job-tasks/{id}
     * Soft delete — sets JobStatus = 'Cancelled'
     */
    @DELETE
    @Path("/{id}")
    public Uni<Response> delete(@PathParam("id") Long id) {
        return service.delete(id)
                .onItem().transform(v -> Response.noContent().build())
                .onFailure().recoverWithItem(e ->
                        Response.serverError().entity(Map.of("error", e.getMessage())).build());
    }
}
