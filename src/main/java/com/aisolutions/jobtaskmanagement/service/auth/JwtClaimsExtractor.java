package com.aisolutions.jobtaskmanagement.service.auth;

import io.jsonwebtoken.Claims;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Extracts staffId/groupAuthority from the caller's access token. The
 * signature is verified against org-api's published JWKS via
 * {@link JwtSignatureVerifier} before any claim is trusted — a missing,
 * malformed, or invalid-signature token yields the same empty result as
 * before, so only genuinely signed tokens gain any additional trust.
 */
@ApplicationScoped
public class JwtClaimsExtractor {

    @Inject
    RoutingContext routingContext;

    @Inject
    JwtSignatureVerifier jwtSignatureVerifier;

    public JwtClaims extract() {
        String token = readBearerToken();
        if (token == null) {
            return JwtClaims.empty();
        }

        Claims claims = jwtSignatureVerifier.verifyAndExtractClaims(token);
        if (claims == null) {
            return JwtClaims.empty();
        }

        return toJwtClaims(claims);
    }

    /** Reads the raw JWT string out of the Authorization header, or null if absent/malformed. */
    private String readBearerToken() {
        String header = routingContext.request().getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7);
    }

    /** Maps verified claims onto the fields this service actually needs. */
    private JwtClaims toJwtClaims(Claims claims) {
        String staffId = claims.get("staffId", String.class);
        List<?> authorities = claims.get("authorities", List.class);
        return new JwtClaims(
                staffId == null ? "" : staffId,
                firstGroupAuthority(authorities));
    }

    /** Picks out the single GROUP_* authority (if any) and strips its prefix. */
    private String firstGroupAuthority(List<?> authorities) {
        if (authorities == null) {
            return "";
        }
        return authorities.stream()
                .filter(a -> a instanceof String)
                .map(a -> (String) a)
                .filter(a -> a.startsWith("GROUP_"))
                .map(a -> a.replace("GROUP_", ""))
                .findFirst()
                .orElse("");
    }

    public record JwtClaims(String staffId, String groupAuthority) {
        static JwtClaims empty() {
            return new JwtClaims("", "");
        }
    }
}
