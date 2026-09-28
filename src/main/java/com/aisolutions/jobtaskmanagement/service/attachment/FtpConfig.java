package com.aisolutions.jobtaskmanagement.service.attachment;

import java.util.Map;

/**
 * Immutable snapshot of FTP connection settings and path configuration,
 * loaded from m07SystemParameters at runtime.
 *
 * Parameters used:
 *   FTP-HOST                 → host
 *   FTP-USERNAME             → username
 *   FTP-PASSWORD             → password
 *   ATTACHMENT-MAIN-URL      → mainUrl (base URL/path on the FTP server)
 *   ATTACHMENT-PATH-JOBTASKS → folder for the JOBTASKS module type (optional)
 *
 * The module-level folder (e.g. "jobtasks-attachments") is a fixed code constant
 * in AttachmentService — it never changes and does not need a DB entry.
 *
 * Resulting remote directory per task:
 *   {mainUrl}/{moduleFolder}/{typeFolder}/{jobTaskId}
 * Example:
 *   /company-folder/jobtasks-attachments/JOBTASKS/JT-2026-0001
 */
public record FtpConfig(
    String host,
    int    port,
    String username,
    String password,
    String mainUrl,
    Map<String, String> typeFolders
) {
    /**
     * Full remote directory for an attachment.
     *
     * @param moduleFolder  fixed module-level folder (e.g. "jobtasks-attachments")
     * @param moduleType    the attachment's module type (e.g. "JOBTASKS")
     * @param referenceCode the job task the file belongs to (e.g. "JT-2026-0001")
     */
    public String buildDirectory(String moduleFolder, String moduleType, String referenceCode) {
        String key = moduleType == null ? "" : moduleType.toUpperCase();
        // The type folder is DB-configurable per module type; when its parameter is
        // absent, fall back to the (uppercased) module type itself — the same value
        // the parameter holds and the ModuleType column stores — so uploads never
        // break and the fallback path matches the configured one.
        String typeFolder = typeFolders.get(key);
        if (typeFolder == null || typeFolder.isBlank()) {
            typeFolder = key;
        }
        return mainUrl + "/" + moduleFolder + "/" + typeFolder + "/" + referenceCode;
    }
}
