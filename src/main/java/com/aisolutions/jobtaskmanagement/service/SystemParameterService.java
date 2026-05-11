package com.aisolutions.jobtaskmanagement.service;

import com.aisolutions.jobtaskmanagement.repository.SystemParameterRepository;
import com.aisolutions.jobtaskmanagement.service.attachment.FtpConfig;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Reads attachment/FTP configuration from m07SystemParameter.
 *
 * Required parameters:
 *   ATTACHMENT-MODE          — must be "FTP" (case-insensitive)
 *   ATTACHMENT-MAIN-URL      — base URL/path on FTP server (e.g. /test.borneochemicalintl.com)
 *   ATTACHMENT-PATH-JOBTASKS — sub-folder for JobTasks files (e.g. JOBTASKS)
 *   FTP-HOST                 — FTP server hostname
 *   FTP-USERNAME             — FTP login user
 *   FTP-PASSWORD             — FTP login password
 */
@ApplicationScoped
public class SystemParameterService {

    private static final List<String> FTP_PARAMS = List.of(
        "ATTACHMENT-MODE",
        "ATTACHMENT-MAIN-URL",
        "ATTACHMENT-PATH-JOBTASKS",
        "FTP-HOST",
        "FTP-USERNAME",
        "FTP-PASSWORD"
    );

    @Inject
    SystemParameterRepository systemParameterRepository;

    /**
     * Load FTP configuration from m07SystemParameter in a single query.
     * Fails with {@link IllegalStateException} if ATTACHMENT-MODE is not "FTP"
     * or if any required parameter is missing.
     */
    public Uni<FtpConfig> loadFtpConfig() {
        return systemParameterRepository.getParameterMap(FTP_PARAMS)
            .map(params -> {
                String mode = params.get("ATTACHMENT-MODE");
                if (mode == null || mode.isBlank()) {
                    throw new IllegalStateException("ATTACHMENT-MODE not found in m07SystemParameter");
                }
                if (!"FTP".equalsIgnoreCase(mode.trim())) {
                    throw new IllegalStateException(
                        "ATTACHMENT-MODE is '" + mode + "' — only FTP is supported by this module");
                }
                return new FtpConfig(
                    require(params, "FTP-HOST"),
                    21,
                    require(params, "FTP-USERNAME"),
                    require(params, "FTP-PASSWORD"),
                    require(params, "ATTACHMENT-MAIN-URL"),
                    require(params, "ATTACHMENT-PATH-JOBTASKS")
                );
            });
    }

    private static String require(Map<String, String> params, String key) {
        String v = params.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("System parameter '" + key + "' is not configured in m07SystemParameter");
        }
        return v.trim();
    }
}
