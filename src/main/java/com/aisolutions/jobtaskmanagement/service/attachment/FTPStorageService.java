package com.aisolutions.jobtaskmanagement.service.attachment;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

/**
 * FTP storage service for JobTasks attachments.
 *
 * Path structure (all from application.properties / env vars):
 *   {ftp.base-path}/{ftp.jobtasks-folder}/{jobTaskId}/{uuid-filename.ext}
 *
 * Example:
 *   /test.borneochemicalintl.com/pms-attachments/JOBTASKS/JT-2026-0001/a1b2c3d4-invoice.pdf
 */
@ApplicationScoped
public class FTPStorageService {

    @ConfigProperty(name = "ftp.host")
    String host;

    @ConfigProperty(name = "ftp.port", defaultValue = "21")
    int port;

    @ConfigProperty(name = "ftp.username")
    String username;

    @ConfigProperty(name = "ftp.password")
    String password;

    @ConfigProperty(name = "ftp.base-path", defaultValue = "/test.borneochemicalintl.com/pms-attachments")
    String basePath;

    @ConfigProperty(name = "ftp.jobtasks-folder", defaultValue = "JOBTASKS")
    String jobtasksFolder;

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final Duration DATA_TIMEOUT   = Duration.ofSeconds(60);

    @Inject
    Vertx vertx;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Build the full remote directory for a given jobTaskId.
     * e.g. /test.borneochemicalintl.com/pms-attachments/JOBTASKS/JT-2026-0001
     */
    public String buildDirectory(String jobTaskId) {
        return basePath + "/" + jobtasksFolder + "/" + jobTaskId;
    }

    /**
     * Upload a file. Returns the full remote path of the stored file.
     *
     * @param fileData      raw bytes
     * @param directoryPath full FTP directory path (already built by caller)
     * @param originalName  original filename, used to generate a unique stored name
     */
    public Uni<String> uploadFile(byte[] fileData, String directoryPath, String originalName) {
        return vertx.executeBlocking(Uni.createFrom().item(() -> {
            FTPClient ftp = new FTPClient();
            try {
                connect(ftp);
                createDirectories(ftp, directoryPath);
                String uniqueName = generateUniqueName(originalName);
                String remotePath = directoryPath + "/" + uniqueName;

                try (InputStream is = new ByteArrayInputStream(fileData)) {
                    if (!ftp.storeFile(remotePath, is)) {
                        throw new RuntimeException("FTP storeFile failed: " + ftp.getReplyString());
                    }
                }
                System.out.println("[FTP] Uploaded: " + remotePath);
                return remotePath;
            } catch (Exception e) {
                System.err.println("[FTP] Upload error: " + e.getMessage());
                throw new RuntimeException("FTP upload failed: " + e.getMessage(), e);
            } finally {
                disconnect(ftp);
            }
        }));
    }

    /**
     * Download file bytes from FTP.
     */
    public Uni<byte[]> downloadFile(String remotePath) {
        return vertx.executeBlocking(Uni.createFrom().item(() -> {
            FTPClient ftp = new FTPClient();
            try {
                connect(ftp);
                try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    if (!ftp.retrieveFile(remotePath, out)) {
                        throw new RuntimeException("FTP retrieveFile failed: " + ftp.getReplyString());
                    }
                    return out.toByteArray();
                }
            } catch (Exception e) {
                System.err.println("[FTP] Download error: " + e.getMessage());
                throw new RuntimeException("FTP download failed: " + e.getMessage(), e);
            } finally {
                disconnect(ftp);
            }
        }));
    }

    /**
     * Delete a file from FTP. Non-existent file is treated as success.
     */
    public Uni<Boolean> deleteFile(String remotePath) {
        return vertx.executeBlocking(Uni.createFrom().item(() -> {
            FTPClient ftp = new FTPClient();
            try {
                connect(ftp);
                boolean deleted = ftp.deleteFile(remotePath);
                System.out.println("[FTP] Delete " + (deleted ? "ok" : "not found") + ": " + remotePath);
                return true;
            } catch (Exception e) {
                System.err.println("[FTP] Delete error: " + e.getMessage());
                throw new RuntimeException("FTP delete failed: " + e.getMessage(), e);
            } finally {
                disconnect(ftp);
            }
        }));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void connect(FTPClient ftp) throws IOException {
        ftp.setConnectTimeout(CONNECT_TIMEOUT_MS);
        ftp.setDataTimeout(DATA_TIMEOUT);
        ftp.setDefaultTimeout(CONNECT_TIMEOUT_MS);
        ftp.connect(host, port);
        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
            ftp.disconnect();
            throw new RuntimeException("FTP refused connection. Reply: " + ftp.getReplyCode());
        }
        if (!ftp.login(username, password)) {
            ftp.disconnect();
            throw new RuntimeException("FTP login failed for user: " + username);
        }
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        ftp.enterLocalPassiveMode();
        System.out.println("[FTP] Connected to " + host + " as " + username);
    }

    private void disconnect(FTPClient ftp) {
        try {
            if (ftp.isConnected()) { ftp.logout(); ftp.disconnect(); }
        } catch (IOException ignored) {}
    }

    private void createDirectories(FTPClient ftp, String path) throws IOException {
        StringBuilder current = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            current.append("/").append(segment);
            if (!ftp.changeWorkingDirectory(current.toString())) {
                ftp.makeDirectory(current.toString());
                ftp.changeWorkingDirectory(current.toString());
            }
        }
        ftp.changeWorkingDirectory("/");
    }

    private String generateUniqueName(String originalName) {
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) {
            return uuid + "-" + sanitize(originalName.substring(0, dot)) + originalName.substring(dot);
        }
        return uuid + "-" + sanitize(originalName);
    }

    private String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
