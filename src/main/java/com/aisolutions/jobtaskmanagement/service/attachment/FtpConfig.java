package com.aisolutions.jobtaskmanagement.service.attachment;

/**
 * Immutable snapshot of FTP connection settings and path configuration,
 * loaded from m07SystemParameter at runtime.
 *
 * Parameters used:
 *   FTP-HOST                 → host
 *   FTP-USERNAME             → username
 *   FTP-PASSWORD             → password
 *   ATTACHMENT-MAIN-URL      → mainUrl   (e.g. /test.borneochemicalintl.com)
 *   ATTACHMENT-PATH-JOBTASKS → folder    (e.g. JOBTASKS)
 *
 * The module-level folder (e.g. "jobtasks-attachments") is a fixed code constant
 * in AttachmentService — it never changes and does not need a DB entry.
 *
 * Resulting remote directory per task:
 *   {mainUrl}/{moduleFolder}/{folder}/{jobTaskId}
 * Example:
 *   /test.borneochemicalintl.com/jobtasks-attachments/JOBTASKS/JT-2026-0001
 */
public record FtpConfig(
    String host,
    int    port,
    String username,
    String password,
    String mainUrl,
    String folder
) {
    /**
     * Full remote directory for a given task.
     *
     * @param moduleFolder fixed module-level folder (e.g. "jobtasks-attachments")
     * @param jobTaskId    task identifier (e.g. "JT-2026-0001")
     */
    public String buildDirectory(String moduleFolder, String jobTaskId) {
        return mainUrl + "/" + moduleFolder + "/" + folder + "/" + jobTaskId;
    }
}
