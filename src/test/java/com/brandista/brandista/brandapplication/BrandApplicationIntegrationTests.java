package com.brandista.brandista.brandapplication;

import com.brandista.brandista.auth.api.LoginResponse;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:brandista-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="
})
class BrandApplicationIntegrationTests {
    private static final String APPLICATIONS = "/api/v1/brand-applications";
    private static final String ADMIN_APPLICATIONS = "/api/v1/admin/brand-applications";
    private static final String PASSWORD = "Brand-Application-Password-2026!";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.brandista.brandista.privilege.PlatformGrantRepository grants;

    @Test
    void publicSubmissionValidatesNormalizesAndPreventsDuplicateOpenEmail() throws Exception {
        String email = "Founding-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<String> submitted = submit(email);
        ResponseEntity<String> duplicate = submit(email.toUpperCase());
        ResponseEntity<String> invalid = exchange(APPLICATIONS, HttpMethod.POST,
                Map.of("brandName", " ", "contactName", "A", "contactEmail", "not-email", "website", "ftp://bad.test"), null);

        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode result = objectMapper.readTree(submitted.getBody());
        assertThat(result.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(result.has("contactEmail")).isFalse();
        assertThat(result.has("reviewedAt")).isFalse();
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).contains("open application");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).contains("brandName", "contactEmail", "website");
    }

    @Test
    void onlyAdminCanReadOrReviewAndApprovalCreatesInactiveBrand() throws Exception {
        String contactEmail = "pilot-" + UUID.randomUUID() + "@example.com";
        JsonNode submitted = objectMapper.readTree(submit(contactEmail).getBody());
        long id = submitted.get("id").asLong();

        assertThat(exchange(ADMIN_APPLICATIONS, HttpMethod.GET, null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(ADMIN_APPLICATIONS, HttpMethod.GET, null, tokenFor(UserRole.CUSTOMER)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(review(id, "APPROVED", tokenFor(UserRole.CUSTOMER)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(review(id, "APPROVED", adminToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> listed = exchange(ADMIN_APPLICATIONS, HttpMethod.GET, null, adminToken());
        JsonNode application = null;
        for (JsonNode candidate : objectMapper.readTree(listed.getBody())) {
            if (candidate.get("id").asLong() == id) application = candidate;
        }
        assertThat(application).isNotNull();
        assertThat(application.get("status").asText()).isEqualTo("APPROVED");
        assertThat(application.get("reviewedAt").asText()).isNotBlank();
        assertThat(application.get("brandId").asLong()).isPositive();
        assertThat(application.get("approvedBrandStatus").asText()).isEqualTo("INACTIVE");
        assertThat(application.get("contactEmail").asText()).isEqualTo(contactEmail);
        assertThat(objectMapper.readTree(restTemplate.getForObject("/api/v1/brands", String.class))
                .findValuesAsText("id")).doesNotContain(application.get("brandId").asText());
    }

    @Test
    void canRequestInformationThenRejectButCannotReviewClosedApplication() {
        JsonNodeValue application = submitValue("workflow-" + UUID.randomUUID() + "@example.com");
        long id = application.id();
        String admin = adminToken();
        ResponseEntity<String> requested = review(id, "NEEDS_INFORMATION", admin);
        ResponseEntity<String> rejected = review(id, "REJECTED", admin);
        ResponseEntity<String> repeat = review(id, "APPROVED", admin);

        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getBody()).contains("REJECTED", "reviewedAt");
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void approvalConflictAndInvalidDecisionAreSafe() throws Exception {
        String admin = adminToken();
        JsonNodeValue conflict = submitValue("slug-conflict-" + UUID.randomUUID() + "@example.com", "Conflict Label");
        ResponseEntity<String> existingBrand = exchange("/api/v1/admin/brands", HttpMethod.POST,
                Map.of("name", "Conflict Label", "slug", "conflict-label"), admin);
        assertThat(existingBrand.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> approval = review(conflict.id(), "APPROVED", admin);
        assertThat(approval.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        JsonNodeValue invalid = submitValue("decision-" + UUID.randomUUID() + "@example.com");
        ResponseEntity<String> badDecision = review(invalid.id(), "SUBMITTED", admin);
        assertThat(badDecision.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badDecision.getBody()).contains("decisionAllowed");
        assertThat(review(Long.MAX_VALUE, "APPROVED", admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> submit(String email) {
        return submit(email, "Pilot Atelier " + UUID.randomUUID());
    }

    private ResponseEntity<String> submit(String email, String brandName) {
        return exchange(APPLICATIONS, HttpMethod.POST, Map.of(
                "brandName", brandName,
                "contactName", "Pilot Contact", "contactEmail", email,
                "website", "https://example.test", "message", "Interested in the controlled pilot."), null);
    }

    private JsonNodeValue submitValue(String email) {
        return submitValue(email, "Pilot Atelier " + UUID.randomUUID());
    }

    private JsonNodeValue submitValue(String email, String brandName) {
        try {
            ResponseEntity<String> response = submit(email, brandName);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            return new JsonNodeValue(objectMapper.readTree(response.getBody()).get("id").asLong());
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private ResponseEntity<String> review(long id, String decision, String token) {
        return exchange(ADMIN_APPLICATIONS + "/" + id + "/review", HttpMethod.PATCH,
                Map.of("decision", decision, "reviewNote", "Operator review note."), token);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private String adminToken() { return tokenFor(UserRole.ADMIN); }

    private String tokenFor(UserRole role) {
        String email = role.name().toLowerCase() + "-application-" + UUID.randomUUID() + "@example.com";
        User actor=userRepository.saveAndFlush(new User(email, passwordEncoder.encode(PASSWORD), role, AccountStatus.ENABLED));
        // Approval fixtures now carry an explicit grant; legacy ADMIN alone cannot approve.
        if(role==UserRole.ADMIN) grants.saveAndFlush(new com.brandista.brandista.privilege.PlatformGrant(actor,
                com.brandista.brandista.privilege.PlatformGrantType.SUPER_ADMIN));
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), LoginResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().accessToken();
    }

    private record JsonNodeValue(long id) { }
}
