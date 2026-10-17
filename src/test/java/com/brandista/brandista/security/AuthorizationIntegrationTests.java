package com.brandista.brandista.security;

import com.brandista.brandista.user.AccountStatus;
import com.brandista.brandista.user.User;
import com.brandista.brandista.user.UserRepository;
import com.brandista.brandista.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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
@Import(AuthorizationIntegrationTests.TestRoutesConfiguration.class)
class AuthorizationIntegrationTests {

    private static final String PASSWORD = "Authorization-Integration-Password-2026!";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void publicHealthRegistrationAndLoginRemainPublicAndRegistrationAlwaysCreatesCustomer() throws Exception {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.postForEntity("/api/v1/auth/login", Map.of("email", "nobody@example.com", "password", PASSWORD), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> registration = restTemplate.postForEntity(
                "/api/v1/registrations",
                Map.of("email", "authorization-" + UUID.randomUUID() + "@example.com",
                        "password", PASSWORD,
                        "role", "ADMIN"),
                String.class);

        assertThat(registration.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode registrationBody = objectMapper.readTree(registration.getBody());
        String email = registrationBody.get("email").asText();
        assertThat(userRepository.findByEmail(email).orElseThrow().getRole()).isEqualTo(UserRole.CUSTOMER);
    }

    @Test
    void unauthenticatedIsDeniedAndValidCustomerCanReachCustomerRoute() {
        ResponseEntity<String> anonymous = get("/api/v1/customers/security-test/private", null);
        String customerToken = tokenFor(UserRole.CUSTOMER);
        ResponseEntity<String> authenticated = get("/api/v1/customers/security-test/private", customerToken);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getBody()).contains("Authentication is required");
        assertThat(authenticated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authenticated.getBody()).isEqualTo("authenticated");
    }

    @Test
    void invalidBearerTokenReturnsSafeUnauthorizedResponse() {
        ResponseEntity<String> response = get("/api/v1/customers/security-test/private", "not.a.valid.token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("bearer token is invalid");
        assertThat(response.getBody()).doesNotContain("not.a.valid.token");
    }

    @Test
    void adminRouteRequiresAdminRole() {
        ResponseEntity<String> anonymous = get("/api/v1/admin/brands/security-test", null);
        ResponseEntity<String> customer = get("/api/v1/admin/brands/security-test", tokenFor(UserRole.CUSTOMER));
        ResponseEntity<String> admin = get("/api/v1/admin/brands/security-test", tokenFor(UserRole.ADMIN));

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(customer.getBody()).contains("Access is forbidden.");
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void methodAuthorizationCanFurtherRestrictPublicUrlMatchers() {
        ResponseEntity<String> customer = get(
                "/api/v1/products/security-method-test", tokenFor(UserRole.CUSTOMER));
        ResponseEntity<String> admin = get(
                "/api/v1/products/security-method-test", tokenFor(UserRole.ADMIN));

        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unknownRoutesAreNotMadePublicByDefault() {
        ResponseEntity<String> anonymous = get("/api/v1/not-yet-implemented", null);
        ResponseEntity<String> customer = get(
                "/api/v1/not-yet-implemented", tokenFor(UserRole.CUSTOMER));

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void corsAllowsOnlyConfiguredOriginsWithoutCredentials() {
        ResponseEntity<String> allowed = preflight("https://client.brandista.test");
        ResponseEntity<String> rejected = preflight("https://untrusted.example");

        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowed.getHeaders().getAccessControlAllowOrigin()).isEqualTo("https://client.brandista.test");
        assertThat(allowed.getHeaders().getAccessControlAllowCredentials()).isFalse();
        assertThat(rejected.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    private String tokenFor(UserRole role) {
        String email = role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        userRepository.saveAndFlush(new User(email, passwordEncoder.encode(PASSWORD), role, AccountStatus.ENABLED));
        ResponseEntity<LoginPayload> response = restTemplate.postForEntity(
                "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), LoginPayload.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().accessToken();
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> preflight(String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.setAccessControlRequestMethod(HttpMethod.GET);
        headers.setAccessControlRequestHeaders(java.util.List.of("Authorization"));
        return restTemplate.exchange(
                "/api/v1/customers/security-test", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }

    private record LoginPayload(String accessToken) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestRoutesConfiguration {
        @Bean
        TestAuthorizationRoutes testAuthorizationRoutes() {
            return new TestAuthorizationRoutes();
        }
    }

    @RestController
    static class TestAuthorizationRoutes {
        @GetMapping("/api/v1/customers/security-test/private")
        String authenticated() {
            return "authenticated";
        }

        @GetMapping("/api/v1/admin/brands/security-test")
        String adminRoute() {
            return "admin";
        }

        @GetMapping("/api/v1/products/security-method-test")
        @PreAuthorize("hasRole('ADMIN')")
        String adminMethod() {
            return "admin-method";
        }
    }
}
