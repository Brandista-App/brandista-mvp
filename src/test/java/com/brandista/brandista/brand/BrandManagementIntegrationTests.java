package com.brandista.brandista.brand;

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

import java.util.LinkedHashMap;
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
class BrandManagementIntegrationTests {

    private static final String ADMIN_BRANDS_URL = "/api/v1/admin/brands";
    private static final String PUBLIC_BRANDS_URL = "/api/v1/brands";
    private static final String PASSWORD = "Brand-Management-Password-2026!";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private BrandRepository brandRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void adminCanCreateBrandWithNormalizedSlugAndMetadata() throws Exception {
        ResponseEntity<String> response = createBrand(
                adminToken(), "  Example Atelier  ", " Example   Atelier ", "A local clothing label", "https://cdn.example/logo.svg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("id").asLong()).isPositive();
        assertThat(body.get("name").asText()).isEqualTo("Example Atelier");
        assertThat(body.get("slug").asText()).isEqualTo("example-atelier");
        assertThat(body.get("description").asText()).isEqualTo("A local clothing label");
        assertThat(body.get("logoReference").asText()).isEqualTo("https://cdn.example/logo.svg");
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.has("passwordHash")).isFalse();
    }

    @Test
    void duplicateNormalizedSlugReturnsSafeConflict() {
        String token = adminToken();
        String slug = "unique-" + UUID.randomUUID();
        assertThat(createBrand(token, "Brand One", slug, null, null)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> duplicate = createBrand(token, "Brand Two", " " + slug.toUpperCase() + " ", null, null);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).contains("A brand with this slug already exists.");
        assertThat(duplicate.getBody()).doesNotContain(slug);
    }

    @Test
    void invalidBrandInputReturnsValidationError() {
        ResponseEntity<String> invalid = createBrand(adminToken(), "  ", "bad_slug!", null, null);

        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).contains("Request validation failed", "name", "slug");
    }

    @Test
    void adminCanUpdateMetadataAndActivationStatus() throws Exception {
        String token = adminToken();
        JsonNode created = objectMapper.readTree(createBrand(token, "Before", "before-" + UUID.randomUUID(), null, null)
                .getBody());
        long id = created.get("id").asLong();
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("name", "Updated Label");
        update.put("slug", " UPDATED   LABEL ");
        update.put("description", "Updated metadata");
        update.put("logoReference", "object-store-key/label.svg");
        update.put("status", "INACTIVE");

        ResponseEntity<String> response = exchange(
                ADMIN_BRANDS_URL + "/" + id, HttpMethod.PUT, update, token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("name").asText()).isEqualTo("Updated Label");
        assertThat(body.get("slug").asText()).isEqualTo("updated-label");
        assertThat(body.get("description").asText()).isEqualTo("Updated metadata");
        assertThat(body.get("logoReference").asText()).isEqualTo("object-store-key/label.svg");
        assertThat(body.get("status").asText()).isEqualTo("INACTIVE");
    }

    @Test
    void deactivatePreservesRecordAndRemovesItFromPublicListing() throws Exception {
        String token = adminToken();
        JsonNode created = objectMapper.readTree(createBrand(token, "Keep History", "history-" + UUID.randomUUID(), null, null)
                .getBody());
        long id = created.get("id").asLong();

        ResponseEntity<String> deactivated = exchange(
                ADMIN_BRANDS_URL + "/" + id + "/deactivate", HttpMethod.PATCH, null, token);
        ResponseEntity<String> adminGet = get(ADMIN_BRANDS_URL + "/" + id, token);
        JsonNode publicBrands = objectMapper.readTree(restTemplate.getForObject(PUBLIC_BRANDS_URL, String.class));

        assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(deactivated.getBody()).get("status").asText()).isEqualTo("INACTIVE");
        assertThat(adminGet.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(brandRepository.findById(id)).isPresent();
        assertThat(publicBrands.findValuesAsText("id")).doesNotContain(Long.toString(id));
    }

    @Test
    void publicListingContainsActiveBrandsOnlyAndAdminCanListAll() throws Exception {
        String token = adminToken();
        String suffix = UUID.randomUUID().toString();
        JsonNode active = objectMapper.readTree(createBrand(token, "Public " + suffix, "public-" + suffix, null, null)
                .getBody());
        JsonNode inactive = objectMapper.readTree(createBrand(token, "Hidden " + suffix, "hidden-" + suffix, null, null)
                .getBody());
        long hiddenId = inactive.get("id").asLong();
        exchange(ADMIN_BRANDS_URL + "/" + hiddenId + "/deactivate", HttpMethod.PATCH, null, token);

        JsonNode publicBrands = objectMapper.readTree(restTemplate.getForObject(PUBLIC_BRANDS_URL, String.class));
        JsonNode adminBrands = objectMapper.readTree(get(ADMIN_BRANDS_URL, token).getBody());

        assertThat(publicBrands.findValuesAsText("id")).contains(active.get("id").asText());
        assertThat(publicBrands.findValuesAsText("id")).doesNotContain(inactive.get("id").asText());
        assertThat(adminBrands.findValuesAsText("id")).contains(active.get("id").asText(), inactive.get("id").asText());
    }

    @Test
    void onlyAdminsCanWriteBrands() {
        ResponseEntity<String> anonymous = createBrand(null, "Anonymous", "anonymous-" + UUID.randomUUID(), null, null);
        ResponseEntity<String> customer = createBrand(customerToken(), "Customer", "customer-" + UUID.randomUUID(), null, null);
        ResponseEntity<String> admin = createBrand(adminToken(), "Admin", "admin-" + UUID.randomUUID(), null, null);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(customer.getBody()).contains("Access is forbidden.");
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void missingBrandReturnsNotFound() {
        ResponseEntity<String> response = get(ADMIN_BRANDS_URL + "/9223372036854775807", adminToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("Brand was not found.");
    }

    private ResponseEntity<String> createBrand(String token, String name, String slug, String description, String logo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("slug", slug);
        body.put("description", description);
        body.put("logoReference", logo);
        return exchange(ADMIN_BRANDS_URL, HttpMethod.POST, body, token);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        return exchange(path, HttpMethod.GET, null, token);
    }

    private String adminToken() {
        return tokenFor(UserRole.ADMIN);
    }

    private String customerToken() {
        return tokenFor(UserRole.CUSTOMER);
    }

    private String tokenFor(UserRole role) {
        String email = role.name().toLowerCase() + "-brand-" + UUID.randomUUID() + "@example.com";
        userRepository.saveAndFlush(new User(email, passwordEncoder.encode(PASSWORD), role, AccountStatus.ENABLED));
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), LoginResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().accessToken();
    }
}
