package com.aisolutions.jobtaskmanagement.service;

import com.aisolutions.jobtaskmanagement.repository.SystemParameterRepository;
import com.aisolutions.jobtaskmanagement.service.attachment.FtpConfig;
import com.aisolutions.jobtaskmanagement.service.auth.AccessControlService;
import com.aisolutions.shared.tenancy.CompanyPoolManager;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads attachment/FTP configuration from m07SystemParameters.
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

    @Inject
    CompanyPoolManager companyPoolManager;

    @Inject
    AccessControlService accessControlService;

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /** Keyed by companyId ("" for the default/single-tenant company) — each company can have its own FTP config. */
    private final Map<String, FtpConfig> cachedFtpConfigByCompany = new ConcurrentHashMap<>();
    private final Map<String, Instant> cacheExpiryByCompany = new ConcurrentHashMap<>();

    /**
     * Load FTP configuration from m07SystemParameters.
     * Cached for 5 minutes per company — DB changes take effect within 5 minutes, no redeploy needed.
     */
    public Uni<FtpConfig> loadFtpConfig() {
        String companyId = accessControlService.getCurrentCompanyId();
        FtpConfig cached = cachedFtpConfigByCompany.get(companyId);
        Instant expiry = cacheExpiryByCompany.get(companyId);
        if (cached != null && expiry != null && Instant.now().isBefore(expiry)) {
            return Uni.createFrom().item(cached);
        }
        return companyPoolManager.poolFor(companyId)
            .flatMap(pool -> systemParameterRepository.getParameterMap(pool, FTP_PARAMS))
            .map(params -> {
                String mode = params.get("ATTACHMENT-MODE");
                if (mode == null || mode.isBlank()) {
                    throw new IllegalStateException("ATTACHMENT-MODE not found in m07SystemParameters");
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
                cachedFtpConfigByCompany.put(companyId, config);
                cacheExpiryByCompany.put(companyId, Instant.now().plus(CACHE_TTL));
                return config;
            });
    }

    /** Force the next {@link #loadFtpConfig()} call to re-fetch from DB, for every company. */
    public void clearFtpConfigCache() {
        cachedFtpConfigByCompany.clear();
        cacheExpiryByCompany.clear();
    }

    private static String require(Map<String, String> params, String key) {
        String v = params.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("System parameter '" + key + "' is not configured in m07SystemParameters");
        }
        return v.trim();
    }
}
