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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads attachment/FTP configuration from m07SystemParameters.
 *
 * Required parameters:
 *   ATTACHMENT-MODE          — must be "FTP" (case-insensitive)
 *   ATTACHMENT-MAIN-URL      — base URL/path on FTP server (e.g. /company-folder)
 *   FTP-HOST                 — FTP server hostname
 *   FTP-USERNAME             — FTP login user
 *   FTP-PASSWORD             — FTP login password
 *
 * Optional parameters:
 *   ATTACHMENT-PATH-JOBTASKS — sub-folder for the JOBTASKS module type (e.g. JOBTASKS);
 *                              defaults to the module type when absent
 *
 * Every repository call is routed through {@link CompanyPoolManager} to resolve
 * the correct per-company database pool.
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

    /**
     * Per-tenant config cache, keyed by the resolving {@code companyId} (blank =
     * default DB). Keying on companyId isolates each tenant so one company's FTP
     * host/path can never be served to another.
     */
    private record CacheEntry<T>(T value, Instant expiry) {
        boolean isFresh() {
            return Instant.now().isBefore(expiry);
        }
    }

    private final Map<String, CacheEntry<FtpConfig>> ftpConfigByCompany = new ConcurrentHashMap<>();

    /**
     * Load FTP configuration from m07SystemParameters for the caller's company.
     * Cached per tenant for 5 minutes — DB changes take effect within 5 minutes, no redeploy needed.
     */
    public Uni<FtpConfig> loadFtpConfig() {
        String companyId = accessControlService.getCurrentCompanyId();
        CacheEntry<FtpConfig> cached = ftpConfigByCompany.get(companyId);
        if (cached != null && cached.isFresh()) {
            return Uni.createFrom().item(cached.value());
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
                // Per-module-type sub-folders, DB-configurable. Key = the moduleType stored
                // on the attachment; value = the folder name from its parameter.
                Map<String, String> typeFolders = new HashMap<>();
                putIfPresent(typeFolders, "JOBTASKS", params.get("ATTACHMENT-PATH-JOBTASKS"));

                FtpConfig config = new FtpConfig(
                    require(params, "FTP-HOST"),
                    21,
                    require(params, "FTP-USERNAME"),
                    require(params, "FTP-PASSWORD"),
                    require(params, "ATTACHMENT-MAIN-URL"),
                    Map.copyOf(typeFolders)
                );
                ftpConfigByCompany.put(companyId, new CacheEntry<>(config, Instant.now().plus(CACHE_TTL)));
                return config;
            });
    }

    /** Force the next {@link #loadFtpConfig()} call to re-fetch from DB, for every tenant. */
    public void clearFtpConfigCache() {
        ftpConfigByCompany.clear();
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    private static String require(Map<String, String> params, String key) {
        String v = params.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("System parameter '" + key + "' is not configured in m07SystemParameters");
        }
        return v.trim();
    }
}
