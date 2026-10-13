package com.brandista.brandista.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigurationTests {

    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private final JwtConfiguration configuration = new JwtConfiguration();

    @Test
    void missingSigningKeyFailsWithSafeMessage() {
        assertThatThrownBy(() -> new JwtProperties(" ", "issuer", "audience", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT signing key is required; configure JWT_SECRET_BASE64");
    }

    @Test
    void invalidAndShortSigningKeysFailClearly() {
        JwtProperties invalidBase64 = properties("definitely-not-base64!");
        assertThatThrownBy(() -> configuration.jwtSigningKey(invalidBase64))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT signing key must be valid Base64");

        JwtProperties shortKey = properties(Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(() -> configuration.jwtSigningKey(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT signing key must contain at least 32 decoded bytes");
    }

    @Test
    void decoderRejectsExpiredTokens() {
        JwtProperties properties = properties(TEST_KEY);
        var key = configuration.jwtSigningKey(properties);
        JwtEncoder encoder = configuration.jwtEncoder(key);
        var decoder = configuration.jwtDecoder(key, properties);
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject("123")
                .issuedAt(now.minusSeconds(120))
                .expiresAt(now.minusSeconds(60))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private JwtProperties properties(String key) {
        return new JwtProperties(key, "brandista-test", "brandista-test-api", Duration.ofMinutes(15));
    }
}
