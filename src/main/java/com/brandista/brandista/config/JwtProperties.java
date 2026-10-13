package com.brandista.brandista.config;

import java.time.Duration;

public record JwtProperties(String secretBase64, String issuer, String audience, Duration accessTokenTtl) {

    public JwtProperties {
        secretBase64 = secretBase64 == null ? "" : secretBase64.trim();
        issuer = requireText(issuer, "JWT issuer must be configured");
        audience = requireText(audience, "JWT audience must be configured");
        if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("JWT access token lifetime must be positive");
        }
        if (accessTokenTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("JWT access token lifetime must not exceed one hour");
        }
        if (secretBase64.isBlank()) {
            throw new IllegalStateException("JWT signing key is required; configure JWT_SECRET_BASE64");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
