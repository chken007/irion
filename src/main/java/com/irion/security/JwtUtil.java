package com.irion.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT utility for generating, parsing, and validating tokens.
 * Uses HMAC-SHA256 with a configurable secret.
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey secretKey;

    @Value("${irion.jwt.expiration-ms:86400000}")
    private long expirationMs;

    public JwtUtil(@Value("${irion.jwt.secret:irion-jwt-secret-key-change-in-production}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate a signed JWT for the given user.
     */
    public String generateToken(String email, Long tenantId, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(email)
                .claim("tenantId", tenantId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Extract all claims from a token.
     */
    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Validate a token. Returns true if the token is properly signed and not expired.
     */
    public boolean validateToken(String token) {
        try {
            extractClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Extract the subject (email) from a token.
     */
    public String getEmail(String token) {
        return extractClaims(token).getSubject();
    }

    /**
     * Extract the tenant ID from a token.
     */
    public Long getTenantId(String token) {
        return extractClaims(token).get("tenantId", Long.class);
    }

    /**
     * Extract the role from a token.
     */
    public String getRole(String token) {
        return extractClaims(token).get("role", String.class);
    }
}
