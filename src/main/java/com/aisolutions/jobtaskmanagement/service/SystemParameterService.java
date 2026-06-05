package com.aisolutions.jobtaskmanagement.service;

import com.aisolutions.jobtaskmanagement.repository.SystemParameterRepository;
import com.aisolutions.jobtaskmanagement.service.attachment.FtpConfig;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
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

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private volatile FtpConfig cachedFtpConfig;
    private volatile Instant   cacheExpiry = Instant.MIN;

    /**
     * Load FTP configuration from m07SystemParameter.
     * Cached for 5 minutes — DB changes take effect within 5 minutes, no redeploy needed.
     */
    public Uni<FtpConfig> loadFtpConfig() {
        if (cachedFtpConfig != null && Instant.now().isBefore(cacheExpiry)) {
            return Uni.createFrom().item(cachedFtpConfig);
        }
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
                FtpConfig config = new FtpConfig(
                    require(params, "FTP-HOST"),
                    21,
                    require(params, "FTP-USERNAME"),
                    require(params, "FTP-PASSWORD"),
                    require(params, "ATTACHMENT-MAIN-URL"),
                    require(params, "ATTACHMENT-PATH-JOBTASKS")
                );
                cachedFtpConfig = config;
                cacheExpiry = Instant.now().plus(CACHE_TTL);
                return config;
            });
    }

    /** Force the next {@link #loadFtpConfig()} call to re-fetch from DB. */
    public void clearFtpConfigCache() {
        cachedFtpConfig = null;
        cacheExpiry     = Instant.MIN;
    }

    private static String require(Map<String, String> params, String key) {
        String v = params.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("System parameter '" + key + "' is not configured in m07SystemParameter");
        }
        return v.trim();
    }
}
