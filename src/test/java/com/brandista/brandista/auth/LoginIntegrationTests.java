package com.brandista.brandista.auth;

import com.brandista.brandista.user.AccountStatus;
import com.brandista.brandista.user.User;
import com.brandista.brandista.user.UserRepository;
import com.brandista.brandista.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:brandista-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
class LoginIntegrationTests {

    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String PASSWORD = "Secure-Login-Password-2026!";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void validCredentialsReturnShortLivedBearerTokenAndSafeAccount() throws Exception {
        User user = createUser(AccountStatus.ENABLED);

        ResponseEntity<String> response = login(user.getEmail(), PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("account").get("email").asText()).isEqualTo(user.getEmail());
        assertThat(body.get("account").has("passwordHash")).isFalse();
        assertThat(response.getBody()).doesNotContain(PASSWORD, user.getPasswordHash());

        Jwt jwt = jwtDecoder.decode(body.get("accessToken").asText());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("brandista-test");
        assertThat(jwt.getAudience()).contains("brandista-test-api");
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void wrongPasswordAndUnknownEmailHaveTheSameSafeResponse() {
        User user = createUser(AccountStatus.ENABLED);

        ResponseEntity<String> wrongPassword = login(user.getEmail(), "Wrong-Password-0000!");
        ResponseEntity<String> unknownEmail = login("missing-" + UUID.randomUUID() + "@example.com", PASSWORD);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).contains("Invalid email or password.");
        assertThat(wrongPassword.getBody()).isEqualTo(unknownEmail.getBody());
        assertThat(wrongPassword.getBody()).doesNotContain(PASSWORD, user.getPasswordHash(), user.getEmail());
    }

    @Test
    void disabledAccountIsRejectedWithoutRevealingItsStatus() {
        User user = createUser(AccountStatus.DISABLED);

        ResponseEntity<String> response = login(user.getEmail(), PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("Invalid email or password.");
        assertThat(response.getBody()).doesNotContain("DISABLED", user.getEmail(), user.getPasswordHash());
    }

    @Test
    void bearerTokenIsAcceptedForProtectedRoutesAndTamperingIsRejected() throws Exception {
        User user = createUser(AccountStatus.ENABLED);
        JsonNode body = objectMapper.readTree(login(user.getEmail(), PASSWORD).getBody());
        String token = body.get("accessToken").asText();
        String[] tokenParts = token.split("\\.");
        char originalSignaturePrefix = tokenParts[2].charAt(0);
        tokenParts[2] = (originalSignaturePrefix == 'a' ? "b" : "a") + tokenParts[2].substring(1);
        String tampered = String.join(".", tokenParts);

        ResponseEntity<String> authorized = getPrivateRoute(token);
        ResponseEntity<String> rejected = getPrivateRoute(tampered);

        assertThat(authorized.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rejected.getBody()).contains("bearer token is invalid");
    }

    private User createUser(AccountStatus status) {
        return userRepository.saveAndFlush(new User(
                "login-" + UUID.randomUUID() + "@example.com",
                passwordEncoder.encode(PASSWORD),
                UserRole.CUSTOMER,
                status));
    }

    private ResponseEntity<String> login(String email, String password) {
        return restTemplate.postForEntity(LOGIN_URL, Map.of("email", email, "password", password), String.class);
    }

    private ResponseEntity<String> getPrivateRoute(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/api/v1/private", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
