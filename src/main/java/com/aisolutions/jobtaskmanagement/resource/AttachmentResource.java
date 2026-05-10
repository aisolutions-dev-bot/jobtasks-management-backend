package com.aisolutions.jobtaskmanagement.resource;

import com.aisolutions.jobtaskmanagement.service.attachment.AttachmentService;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
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

    @Inject
    AttachmentService attachmentService;

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
        return attachmentService.getAttachmentMeta(id)
            .flatMap(meta -> {
                if (meta == null) {
                    return Uni.createFrom().item(Response.status(404)
                        .entity("Attachment not found").build());
                }
                return attachmentService.downloadFile(id)
                    .onItem().transform(bytes -> Response.ok(bytes)
                        .header("Content-Disposition",
                            "attachment; filename=\"" + meta.getOriginalName() + "\"")
                        .header("Content-Type",
                            meta.getContentType() != null ? meta.getContentType() : "application/octet-stream")
                        .build());
            })
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

        byte[] fileData;
        try {
            fileData = Files.readAllBytes(file.uploadedFile());
        } catch (IOException e) {
            return Uni.createFrom().item(Response.serverError()
                .entity(Map.of("error", "Failed to read uploaded file")).build());
        }

        return attachmentService.uploadFile(
                jobTaskId, file.fileName(), file.contentType(), fileData,
                entryStaff != null ? entryStaff : "SYSTEM")
            .onItem().transform(dto -> Response.status(201).entity(dto).build())
            .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
                Response.status(400).entity(Map.of("error", e.getMessage())).build())
            .onFailure().recoverWithItem(e -> {
                System.err.println("[AttachmentResource] upload error: " + e.getMessage());
                return Response.serverError().entity(Map.of("error", "Upload failed")).build();
            });
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
