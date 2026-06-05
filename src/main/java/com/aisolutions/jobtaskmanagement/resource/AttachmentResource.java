package com.aisolutions.jobtaskmanagement.resource;

import com.aisolutions.jobtaskmanagement.service.SystemParameterService;
import com.aisolutions.jobtaskmanagement.service.attachment.AttachmentService;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.nio.file.Files;
import java.util.Map;

/**
 * REST resource for JobTasks attachment management.
 *
 * GET    /api/v1/attachments?jobTaskId=JT-2026-0001        — list metadata
 * GET    /api/v1/attachments/download/{id}                 — download file bytes
 * POST   /api/v1/attachments/upload  (multipart)           — upload single file
 * DELETE /api/v1/attachments/{id}                          — delete
 *
 * quarkus.http.body.handle-files-as-blocking=true in application.properties
 * is required for multipart file handling to work in RESTEasy Reactive.
 */
@Path("/api/v1/attachments")
@Produces(MediaType.APPLICATION_JSON)
public class AttachmentResource {

    private static final Logger LOG = Logger.getLogger(AttachmentResource.class);

    @Inject
    AttachmentService attachmentService;

    @Inject
    SystemParameterService systemParameterService;

    // ─── GET list ─────────────────────────────────────────────────────────────

    @GET
    public Uni<Response> getAttachments(@QueryParam("jobTaskId") String jobTaskId) {
        if (jobTaskId == null || jobTaskId.isBlank()) {
            return Uni.createFrom().item(Response.status(400)
                .entity(Map.of("error", "jobTaskId is required")).build());
        }
        return attachmentService.getAttachments(jobTaskId)
            .onItem().transform(list -> Response.ok(list).build())
            .onFailure().recoverWithItem(e -> Response.serverError()
                .entity(Map.of("error", e.getMessage())).build());
    }

    // ─── GET download ─────────────────────────────────────────────────────────

    @GET
    @Path("/download/{id}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Uni<Response> downloadAttachment(@PathParam("id") Long id) {
        return attachmentService.downloadAttachment(id)
            .onItem().transform(result -> Response.ok(result.bytes())
                .header("Content-Disposition",
                    "attachment; filename=\"" + result.originalName() + "\"")
                .header("Content-Type",
                    result.contentType() != null ? result.contentType() : "application/octet-stream")
                .build())
            .onFailure().recoverWithItem(e -> Response.serverError()
                .entity("Failed to download: " + e.getMessage()).build());
    }

    // ─── POST upload ──────────────────────────────────────────────────────────

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Uni<Response> uploadFile(
            @RestForm("file")        FileUpload file,
            @RestForm("jobTaskId")   String jobTaskId,
            @RestForm("entryStaff")  String entryStaff) {

        if (file == null) {
            return Uni.createFrom().item(Response.status(400)
                .entity(Map.of("error", "No file provided")).build());
        }
        if (jobTaskId == null || jobTaskId.isBlank()) {
            return Uni.createFrom().item(Response.status(400)
                .entity(Map.of("error", "jobTaskId is required")).build());
        }

        if (file.uploadedFile() == null) {
            return Uni.createFrom().item(Response.status(400)
                .entity(Map.of("error", "Uploaded file path is null — multipart not parsed correctly")).build());
        }

        byte[] fileData;
        try {
            fileData = Files.readAllBytes(file.uploadedFile());
        } catch (Exception e) {
            LOG.errorf("[Attachment] File read error: %s: %s", e.getClass().getSimpleName(), e.getMessage());
            return Uni.createFrom().item(Response.serverError()
                .entity(Map.of("error", "Failed to read uploaded file: " + e.getMessage())).build());
        }

        return attachmentService.uploadFile(
                jobTaskId, file.fileName(), file.contentType(), fileData,
                entryStaff != null ? entryStaff : "SYSTEM")
            .onItem().transform(dto -> Response.status(201).entity(dto).build())
            .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
                Response.status(400).entity(Map.of("error", e.getMessage())).build())
            .onFailure().recoverWithItem(e -> {
                LOG.errorf("[Attachment] Upload error: %s", e.getMessage());
                return Response.serverError().entity(Map.of("error", "Upload failed")).build();
            });
    }

    // ─── CACHE ────────────────────────────────────────────────────────────────

    @POST
    @Path("/config/refresh")
    public Response refreshConfig() {
        systemParameterService.clearFtpConfigCache();
        return Response.ok(Map.of("message", "FTP config cache cleared. Next attachment operation will reload from DB.")).build();
    }

    // ─── DELETE ───────────────────────────────────────────────────────────────

    @DELETE
    @Path("/{id}")
    public Uni<Response> deleteAttachment(@PathParam("id") Long id) {
        return attachmentService.deleteAttachment(id)
            .onItem().transform(ok -> ok
                ? Response.ok(Map.of("success", true)).build()
                : Response.status(404).entity(Map.of("error", "Attachment not found")).build())
            .onFailure().recoverWithItem(e -> Response.serverError()
                .entity(Map.of("error", e.getMessage())).build());
    }
}
