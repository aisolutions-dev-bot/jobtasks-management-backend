package com.aisolutions.jobtaskmanagement.service.attachment;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

/**
 * FTP storage service for JobTasks attachments.
 *
 * Path structure (all resolved from m07SystemParameters):
 *   {ATTACHMENT-MAIN-URL}/{ATTACHMENT-PATH-JOBTASKS}/{jobTaskId}/{uuid-filename.ext}
 *
 * Example:
 *   /test.borneochemicalintl.com/pms-attachments/JOBTASKS/JT-2026-0001/a1b2c3d4-invoice.pdf
 *
 * The base path is NOT hardcoded — it is passed in by the caller (AttachmentService)
 * after it has been resolved from the org-api system-parameters endpoint.
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

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final Duration DATA_TIMEOUT   = Duration.ofSeconds(60);

    // #region PUBLIC METHODS

    /**
     * Upload a file to FTP.
     *
     * @param fileData      raw bytes
     * @param directoryPath full remote directory, e.g. /pms-attachments/JOBTASKS/JT-2026-0001
     * @param originalName  original filename for generating a unique stored name
     * @return full remote path to the stored file
     */
    public Uni<String> uploadFile(byte[] fileData, String directoryPath, String originalName) {
        return Uni.createFrom().item(() -> {
            FTPClient ftpClient = new FTPClient();
            try {
                connect(ftpClient);
                createDirectories(ftpClient, directoryPath);

                String uniqueFileName = generateUniqueFileName(originalName);
                String remotePath     = directoryPath + "/" + uniqueFileName;

                try (InputStream is = new ByteArrayInputStream(fileData)) {
                    boolean ok = ftpClient.storeFile(remotePath, is);
                    if (!ok) {
                        throw new RuntimeException("FTP storeFile failed: " + ftpClient.getReplyString());
                    }
                }
                System.out.println("[FTP] Uploaded: " + remotePath);
                return remotePath;
            } catch (Exception e) {
                System.err.println("[FTP] Upload error: " + e.getMessage());
                throw new RuntimeException("Failed to upload to FTP: " + e.getMessage(), e);
            } finally {
                disconnect(ftpClient);
            }
        });
    }

    /**
     * Download file bytes from FTP.
     */
    public Uni<byte[]> downloadFile(String remotePath) {
        return Uni.createFrom().item(() -> {
            FTPClient ftpClient = new FTPClient();
            try {
                connect(ftpClient);
                try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    boolean ok = ftpClient.retrieveFile(remotePath, out);
                    if (!ok) {
                        throw new RuntimeException("FTP retrieveFile failed: " + ftpClient.getReplyString());
                    }
                    return out.toByteArray();
                }
            } catch (Exception e) {
                System.err.println("[FTP] Download error: " + e.getMessage());
                throw new RuntimeException("Failed to download from FTP: " + e.getMessage(), e);
            } finally {
                disconnect(ftpClient);
            }
        });
    }

    /**
     * Delete a file from FTP (best-effort — non-existent file is not an error).
     */
    public Uni<Boolean> deleteFile(String remotePath) {
        return Uni.createFrom().item(() -> {
            FTPClient ftpClient = new FTPClient();
            try {
                connect(ftpClient);
                boolean deleted = ftpClient.deleteFile(remotePath);
                System.out.println("[FTP] Delete " + (deleted ? "ok" : "not found") + ": " + remotePath);
                return true;
            } catch (Exception e) {
                System.err.println("[FTP] Delete error: " + e.getMessage());
                throw new RuntimeException("Failed to delete from FTP: " + e.getMessage(), e);
            } finally {
                disconnect(ftpClient);
            }
        });
    }

    // #endregion

    // #region PRIVATE HELPERS

    private void connect(FTPClient ftp) throws IOException {
        ftp.setConnectTimeout(CONNECT_TIMEOUT_MS);
        ftp.setDataTimeout(DATA_TIMEOUT);
        ftp.setDefaultTimeout(CONNECT_TIMEOUT_MS);
        ftp.connect(host, port);

        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
            ftp.disconnect();
            throw new RuntimeException("FTP server refused connection. Code: " + ftp.getReplyCode());
        }
        if (!ftp.login(username, password)) {
            ftp.disconnect();
            throw new RuntimeException("FTP login failed — check credentials.");
        }
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        ftp.enterLocalPassiveMode();
        System.out.println("[FTP] Connected to " + host);
    }

    private void disconnect(FTPClient ftp) {
        try {
            if (ftp.isConnected()) { ftp.logout(); ftp.disconnect(); }
        } catch (IOException ignored) {}
    }

    private void createDirectories(FTPClient ftp, String directoryPath) throws IOException {
        StringBuilder current = new StringBuilder();
        for (String segment : directoryPath.split("/")) {
            if (segment.isEmpty()) continue;
            current.append("/").append(segment);
            String path = current.toString();
            if (!ftp.changeWorkingDirectory(path)) {
                ftp.makeDirectory(path);
                ftp.changeWorkingDirectory(path);
            }
        }
        ftp.changeWorkingDirectory("/");
    }

    private String generateUniqueFileName(String originalName) {
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) {
            String name = sanitize(originalName.substring(0, dot));
            String ext  = originalName.substring(dot);
            return uuid + "-" + name + ext;
        }
        return uuid + "-" + sanitize(originalName);
    }

    private String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // #endregion
}
