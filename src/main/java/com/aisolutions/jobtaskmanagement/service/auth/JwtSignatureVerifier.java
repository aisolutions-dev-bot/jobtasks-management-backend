package com.aisolutions.jobtaskmanagement.service.auth;

import com.aisolutions.jobtaskmanagement.client.JwksClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies the RS256 signature on incoming access tokens against org-api's
 * published JWKS, rather than trusting an unverified base64 decode of the
 * payload (the previous behavior in JwtClaimsExtractor). Public keys are
 * cached by kid; an unrecognized kid triggers exactly one JWKS refetch, so a
 * key rotation on org-api is picked up without a background poller.
 */
@ApplicationScoped
public class JwtSignatureVerifier {

    private static final Logger LOG = Logger.getLogger(JwtSignatureVerifier.class);
    private static final ObjectMapper HEADER_MAPPER = new ObjectMapper();

    @Inject
    @RestClient
    JwksClient jwksClient;

    private final Map<String, PublicKey> publicKeysByKid = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent ev) {
        refreshKeysFromJwks();
    }

    /**
     * Verifies the token's signature and returns its claims if valid, or
     * empty if the signature is missing, invalid, or signed by an unknown
     * key (even after one refetch attempt).
     */
    public Claims verifyAndExtractClaims(String token) {
        String kid = readKeyId(token);
        PublicKey publicKey = resolvePublicKey(kid);
        if (publicKey == null) {
            return null;
        }
        return parseWithKey(token, publicKey);
    }

    /**
     * Reads the kid from the JWT header segment directly, without verifying
     * anything — jjwt refuses to parse a signed JWS at all without first
     * supplying the verification key, so the key must be identified before
     * jjwt ever touches the token.
     */
    private String readKeyId(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
            Map<?, ?> header = HEADER_MAPPER.readValue(headerBytes, Map.class);
            Object kid = header.get("kid");
            return kid == null ? null : kid.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Looks up a cached public key by kid, refetching the JWKS once if the kid is unrecognized. */
    private PublicKey resolvePublicKey(String kid) {
        if (kid == null) {
            return null;
        }
        PublicKey cached = publicKeysByKid.get(kid);
        if (cached != null) {
            return cached;
        }
        LOG.infof("Unknown JWT kid '%s' — refetching JWKS from org-api", kid);
        refreshKeysFromJwks();
        return publicKeysByKid.get(kid);
    }

    /** Parses and verifies the token with the given key, returning null on any failure. */
    private Claims parseWithKey(String token, PublicKey publicKey) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token);
            return jws.getPayload();
        } catch (Exception e) {
            LOG.warnf("JWT signature verification failed: %s", e.getMessage());
            return null;
        }
    }

    /** Fetches org-api's JWKS and rebuilds the kid-to-key cache. Failures leave the existing cache untouched. */
    private void refreshKeysFromJwks() {
        try {
            String jwksJson = jwksClient.fetchJwksJson().await().indefinitely();
            JwkSet jwkSet = Jwks.setParser().build().parse(jwksJson);
            for (Jwk<?> jwk : jwkSet.getKeys()) {
                publicKeysByKid.put(jwk.getId(), (PublicKey) jwk.toKey());
            }
        } catch (Exception e) {
            LOG.warnf("Failed to fetch JWKS from org-api: %s", e.getMessage());
        }
    }
}
