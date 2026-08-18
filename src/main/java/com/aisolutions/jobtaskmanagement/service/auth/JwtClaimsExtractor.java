package com.aisolutions.jobtaskmanagement.service.auth;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Maps the verified identity JWT onto the two fields the Job Task services
 * need.
 *
 * The token carries a custom claim shape: {@code sub} is the login id, the
 * {@code staffId} claim is the staff master code, and the {@code authorities}
 * claim is a list of authorities of which the first {@code GROUP_*} entry
 * names the caller's group.
 *
 * Signature verification, expiry checks, keyId-based key lookup and JWKS
 * rotation are handled by quarkus-smallrye-jwt at the request boundary —
 * verified against org-api's published JWKS (mp.jwt.verify.publickey.location).
 * Nothing here trusts unverified input: an invalid or expired token is
 * rejected before this code ever runs.
 */
@ApplicationScoped
public class JwtClaimsExtractor {

    private static final String STAFF_ID_CLAIM_NAME = "staffId";
    private static final String AUTHORITIES_CLAIM_NAME = "authorities";
    private static final String GROUP_AUTHORITY_PREFIX = "GROUP_";
    private static final Logger LOG = Logger.getLogger(JwtClaimsExtractor.class);

    @Inject
    JsonWebToken jsonWebToken;

    /**
     * Orchestrator: builds the service-facing claims record from the
     * injected verified JWT by delegating to
     * {@link #extractStaffIdFrom(JsonWebToken)} and
     * {@link #extractGroupAuthorityFrom(JsonWebToken)}.
     */
    public JwtClaims extract() {
        Object rawAuthorities = jsonWebToken.getClaim(AUTHORITIES_CLAIM_NAME);
        LOG.infof("DIAG jwt class=%s name=%s claimNames=%s staffIdClaim=%s authoritiesClaim=%s authoritiesClass=%s",
                jsonWebToken.getClass().getName(),
                jsonWebToken.getName(),
                jsonWebToken.getClaimNames(),
                jsonWebToken.getClaim(STAFF_ID_CLAIM_NAME),
                rawAuthorities,
                rawAuthorities == null ? "null" : rawAuthorities.getClass().getName());
        return new JwtClaims(
                extractStaffIdFrom(jsonWebToken),
                extractGroupAuthorityFrom(jsonWebToken));
    }

    /**
     * Reads the staff master code from the token's {@code staffId} claim,
     * falling back to an empty string when the claim is absent.
     */
    private String extractStaffIdFrom(JsonWebToken token) {
        String staffId = token.getClaim(STAFF_ID_CLAIM_NAME);
        return staffId == null ? "" : staffId;
    }

    /**
     * Derives the caller's group authority from the token's
     * {@code authorities} claim, delegating to
     * {@link #findFirstGroupAuthorityIn(List)} for the actual selection.
     */
    private String extractGroupAuthorityFrom(JsonWebToken token) {
        Object authoritiesClaim = token.getClaim(AUTHORITIES_CLAIM_NAME);
        if (!(authoritiesClaim instanceof List<?> authorities)) {
            return "";
        }
        return findFirstGroupAuthorityIn(authorities);
    }

    /**
     * Picks the first authority that names a group (prefix {@code GROUP_})
     * and strips the prefix so the rest of the application sees the bare
     * group name (e.g. {@code GROUP_SUPERADMIN} -> {@code SUPERADMIN}).
     */
    private String findFirstGroupAuthorityIn(List<?> authorities) {
        return authorities.stream()
                .filter(authority -> authority instanceof String)
                .map(authority -> (String) authority)
                .filter(authority -> authority.startsWith(GROUP_AUTHORITY_PREFIX))
                .map(this::stripGroupPrefixFrom)
                .findFirst()
                .orElse("");
    }

    /** Removes the leading group prefix from a single authority string. */
    private String stripGroupPrefixFrom(String authority) {
        return authority.replace(GROUP_AUTHORITY_PREFIX, "");
    }

    /** The two service-facing identity fields extracted from the caller's JWT. */
    public record JwtClaims(String staffId, String groupAuthority) {
    }
}