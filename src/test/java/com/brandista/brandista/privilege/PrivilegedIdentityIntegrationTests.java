package com.brandista.brandista.privilege;

import com.brandista.brandista.user.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:brandista-privilege-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="})
class PrivilegedIdentityIntegrationTests {
    private static final String PASSWORD = "Privilege-Integration-Password-2026!";
    private static final String IDENTITY = "/api/v1/super-admin/identity";
    @Autowired TestRestTemplate http;
    @Autowired UserRepository users;
    @Autowired PlatformGrantRepository grants;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired SuperAdminIdentityController identityController;

    @Test
    void publicRegistrationAndLoginIgnoreSubmittedRolesGrantsAndAnotherUserIdentifier() throws Exception {
        User victim = user(UserRole.ADMIN);
        String email = UUID.randomUUID() + "@example.com";
        ResponseEntity<String> registered = http.postForEntity("/api/v1/registrations", Map.of(
                "email", email, "password", PASSWORD, "role", "SUPER_ADMIN",
                "roles", List.of("ADMIN", "SUPER_ADMIN"), "platformGrants", List.of("SUPER_ADMIN"),
                "id", victim.getId(), "userId", victim.getId()), String.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        User customer = users.findByEmail(email).orElseThrow();
        assertThat(customer.getId()).isNotEqualTo(victim.getId());
        assertThat(customer.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(grants.existsByUserIdAndGrantTypeAndRevokedAtIsNull(customer.getId(), PlatformGrantType.SUPER_ADMIN)).isFalse();
        ResponseEntity<String> login = http.postForEntity("/api/v1/auth/login", Map.of(
                "email", email, "password", PASSWORD, "role", "SUPER_ADMIN", "userId", victim.getId()), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode payload = json.readTree(login.getBody());
        assertThat(payload.path("account").path("role").asText()).isEqualTo("CUSTOMER");
        assertThat(payload.path("tokenType").asText()).isEqualTo("Bearer");
        String token = payload.path("accessToken").asText();
        assertThat(decoder.decode(token).getSubject()).isEqualTo(customer.getId().toString());
        assertThat(get(IDENTITY, token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/customers/me/cart", token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anonymousAndCustomerCannotAccessSuperAdminIdentity() {
        assertThat(get(IDENTITY, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(IDENTITY, login(user(UserRole.CUSTOMER))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void legacyAdminRetainsCatalogueAccessButDoesNotAcquireSuperAdmin() {
        User admin = user(UserRole.ADMIN);
        String token = login(admin);
        assertThat(get("/api/v1/admin/brands", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(IDENTITY, token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(grants.existsByUserIdAndGrantTypeAndRevokedAtIsNull(admin.getId(), PlatformGrantType.SUPER_ADMIN)).isFalse();
    }

    @Test
    void explicitPlatformGrantPreservesCustomerIdentityAndCommerce() throws Exception {
        User customer = user(UserRole.CUSTOMER);
        grant(customer);
        String token = login(customer);
        ResponseEntity<String> response = get(IDENTITY, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(response.getBody()).path("userId").asLong()).isEqualTo(customer.getId());
        assertThat(json.readTree(response.getBody()).path("platformGrants").get(0).asText()).isEqualTo("SUPER_ADMIN");
        assertThat(decoder.decode(token).getClaimAsString("role")).isEqualTo("CUSTOMER");
        assertThat(get("/api/v1/admin/brands", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/customers/me/cart", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/customers/me/addresses", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/customers/me/orders", token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void superAdminGrantAuthorizesExistingMethodProtectedCatalogueWrites() {
        User customer = user(UserRole.CUSTOMER);
        grant(customer);
        String slug = "privilege-" + UUID.randomUUID();
        ResponseEntity<String> result = call(HttpMethod.POST, "/api/v1/admin/brands", login(customer),
                Map.of("name", "Synthetic permission test", "slug", slug));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void revocationRejectsTheSameStillValidTokenAndLeavesCustomerAccessIntact() {
        User customer = user(UserRole.CUSTOMER);
        PlatformGrant grant = grant(customer);
        String token = login(customer);
        assertThat(get(IDENTITY, token).getStatusCode()).isEqualTo(HttpStatus.OK);
        grant.revoke();
        grants.saveAndFlush(grant);
        assertThat(decoder.decode(token).getExpiresAt()).isAfter(Instant.now());
        assertThat(get(IDENTITY, token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/admin/brands", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/customers/me/cart", token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void disabledGranteeCannotUseOldTokenOrLogin() {
        User customer = user(UserRole.CUSTOMER);
        grant(customer);
        String token = login(customer);
        customer.changeStatus(AccountStatus.DISABLED);
        users.saveAndFlush(customer);
        assertThat(decoder.decode(token).getExpiresAt()).isAfter(Instant.now());
        assertThat(get(IDENTITY, token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/admin/brands", token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/customers/me/cart", token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity("/api/v1/auth/login", Map.of("email", customer.getEmail(), "password", PASSWORD), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void disabledLegacyAdminCannotUseOldAuthority() {
        User admin = user(UserRole.ADMIN);
        String token = login(admin);
        assertThat(get("/api/v1/admin/brands", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        admin.changeStatus(AccountStatus.DISABLED);
        users.saveAndFlush(admin);
        assertThat(get("/api/v1/admin/brands", token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void demotedLegacyAdminCannotUseOldRoleClaim() {
        User admin = user(UserRole.ADMIN);
        String token = login(admin);
        admin.changeRole(UserRole.CUSTOMER);
        users.saveAndFlush(admin);
        assertThat(decoder.decode(token).getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(get("/api/v1/admin/brands", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/customers/me/cart", token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void deletedAccountCannotAuthenticateWithAnUnexpiredToken() {
        User customer = user(UserRole.CUSTOMER);
        String token = login(customer);
        users.deleteById(customer.getId());
        assertThat(get(IDENTITY, token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void signedElevatedClaimsWithoutCurrentDatabaseGrantsAreNotAuthority() {
        User customer = user(UserRole.CUSTOMER);
        String token = signed(customer.getId().toString());
        assertThat(decoder.decode(token).getClaimAsString("role")).isEqualTo("SUPER_ADMIN");
        assertThat(get(IDENTITY, token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/admin/brands", token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/customers/me/cart", token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void invalidOrUnknownSignedSubjectsFailClosedWithoutServerErrors() {
        for (String subject : List.of("-1", "not-an-id", "9223372036854775808", "9223372036854775807"))
            assertThat(get(IDENTITY, signed(subject)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void editingTokenSubjectOrRolesWithoutSignatureReturnsUnauthorized() throws Exception {
        String token = login(user(UserRole.CUSTOMER));
        String[] parts = token.split("[.]");
        JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("sub", user(UserRole.ADMIN).getId().toString());
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("role", "SUPER_ADMIN");
        String edited = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.writeValueAsBytes(payload)) + "." + parts[2];
        assertThat(get(IDENTITY, edited).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void clientRoleAndGrantMutationRequestsAreDeniedWithoutChangingAuthority() {
        User customer = user(UserRole.CUSTOMER);
        User admin = user(UserRole.ADMIN);
        long count = grants.count();
        for (String token : List.of(login(customer), login(admin))) {
            for (String path : List.of("/api/v1/super-admin/grants", "/api/v1/admin/accounts", "/api/v1/users/" + customer.getId() + "/roles"))
                assertThat(call(HttpMethod.POST, path, token, Map.of("role", "SUPER_ADMIN", "userId", admin.getId()))
                        .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(users.findById(customer.getId()).orElseThrow().getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(users.findById(admin.getId()).orElseThrow().getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(grants.count()).isEqualTo(count);
    }

    @Test
    void methodSecurityProtectsIdentityEvenWithoutUrlAuthorization() {
        User admin = user(UserRole.ADMIN);
        Jwt jwt = decoder.decode(login(admin));
        try {
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            assertThatThrownBy(() -> identityController.identity(jwt)).isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void errorRenderingPreservesPublicValidationButErrorUrlIsNotPublic() {
        assertThat(get("/api/v1/products?page=not-a-number", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/error", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/error", login(user(UserRole.CUSTOMER))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private User user(UserRole role) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", passwords.encode(PASSWORD), role, AccountStatus.ENABLED));
    }
    private PlatformGrant grant(User user) {
        return grants.saveAndFlush(new PlatformGrant(user, PlatformGrantType.SUPER_ADMIN));
    }
    private String login(User user) {
        ResponseEntity<JsonNode> result = http.postForEntity("/api/v1/auth/login", Map.of("email", user.getEmail(), "password", PASSWORD), JsonNode.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        return result.getBody().path("accessToken").asText();
    }
    private String signed(String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(subject).issuer("brandista-test")
                .audience(List.of("brandista-test-api")).issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim("role", "SUPER_ADMIN").claim("roles", List.of("ADMIN", "SUPER_ADMIN"))
                .claim("scope", "ADMIN SUPER_ADMIN").claim("grants", List.of("SUPER_ADMIN")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    private ResponseEntity<String> get(String path, String token) { return call(HttpMethod.GET, path, token, null); }
    private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}
