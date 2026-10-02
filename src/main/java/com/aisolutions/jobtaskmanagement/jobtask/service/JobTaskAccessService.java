package com.aisolutions.jobtaskmanagement.jobtask.service;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.aisolutions.jobtaskmanagement.client.GroupAuthorityAccessClient;
import com.aisolutions.jobtaskmanagement.dto.GroupAuthorityAccessDTO;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Resolves the RBAC access codes granted to a group authority.
 *
 * Delegates the remote lookup to {@link GroupAuthorityAccessClient} through the cached
 * {@link #getCachedAccess(String)} method and degrades to an empty grant list when the
 * organization API is unreachable, so reads fail closed rather than failing the request.
 */
@ApplicationScoped
public class JobTaskAccessService {

    private static final Logger LOG = Logger.getLogger(JobTaskAccessService.class);

    private static final String MODULE_ID = "mod24";

    @Inject
    @RestClient
    GroupAuthorityAccessClient accessClient;

    /** Cached access grants per group authority — rarely changes. */
    @CacheResult(cacheName = "jobtasks-rbac-access")
    public Uni<List<GroupAuthorityAccessDTO>> getCachedAccess(@CacheKey String groupAuthority) {
        return accessClient.getAccessByModule(groupAuthority, MODULE_ID);
    }

    /** Resolves grants for the caller, returning an empty list when the lookup fails. */
    public Uni<List<GroupAuthorityAccessDTO>> resolveAccess(String groupAuthority) {
        if (groupAuthority == null || groupAuthority.isBlank()) {
            return Uni.createFrom().item(List.of());
        }
        return getCachedAccess(groupAuthority).onFailure().recoverWithItem(failure -> {
            LOG.warnf("RBAC fetch failed: %s", failure.getMessage());
            return List.of();
        });
    }

    /** True when the granted access codes contain an enabled entry for the given code. */
    public boolean hasAccess(List<GroupAuthorityAccessDTO> accesses, String accessCode) {
        return accesses.stream()
                .anyMatch(access ->
                        accessCode.equals(access.getAccessCode()) && Boolean.TRUE.equals(access.getAccessValue()));
    }
}
