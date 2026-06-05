package com.aisolutions.jobtaskmanagement.service.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class JwtClaimsExtractor {

    @Inject
    RoutingContext routingContext;

    @Inject
    ObjectMapper objectMapper;

    public JwtClaims extract() {
        try {
            String header = routingContext.request().getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) return JwtClaims.empty();

            String[] parts = header.substring(7).split("\\.");
            if (parts.length != 3) return JwtClaims.empty();

            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            Map<String, Object> claims = objectMapper.readValue(payloadBytes, new TypeReference<>() {});

            String staffId = (String) claims.getOrDefault("staffId", "");

            @SuppressWarnings("unchecked")
            List<String> authorities = (List<String>) claims.getOrDefault("authorities", List.of());
            String groupAuthority = authorities.stream()
                    .filter(a -> a.startsWith("GROUP_"))
                    .map(a -> a.replace("GROUP_", ""))
                    .findFirst()
                    .orElse("");

            return new JwtClaims(staffId, groupAuthority);

        } catch (Exception e) {
            return JwtClaims.empty();
        }
    }

    public record JwtClaims(String staffId, String groupAuthority) {
        static JwtClaims empty() {
            return new JwtClaims("", "");
        }
    }
}
