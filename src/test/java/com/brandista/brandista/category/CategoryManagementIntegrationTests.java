package com.brandista.brandista.category;

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
class CategoryManagementIntegrationTests {

    private static final String ADMIN_CATEGORIES_URL = "/api/v1/admin/categories";
    private static final String PUBLIC_CATEGORIES_URL = "/api/v1/categories";
    private static final String PASSWORD = "Category-Management-Password-2026!";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void adminCanCreateCategoryAndReadPaginatedLists() throws Exception {
        String suffix = UUID.randomUUID().toString();
        ResponseEntity<String> response = createCategory(
                adminToken(), "  Home Living " + suffix + " ", " Home   Living " + suffix + " ", "Useful items");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = objectMapper.readTree(response.getBody());
        assertThat(created.get("id").asLong()).isPositive();
        assertThat(created.get("name").asText()).isEqualTo("Home Living " + suffix);
        assertThat(created.get("slug").asText()).isEqualTo(("home-living-" + suffix).toLowerCase());
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");

        JsonNode adminPage = objectMapper.readTree(get(ADMIN_CATEGORIES_URL + "?page=0&size=1", adminToken()).getBody());
        JsonNode publicPage = objectMapper.readTree(restTemplate.getForObject(PUBLIC_CATEGORIES_URL + "?page=0&size=1", String.class));
        assertThat(adminPage.get("size").asInt()).isEqualTo(1);
        assertThat(adminPage.get("totalElements").asLong()).isPositive();
        assertThat(publicPage.get("size").asInt()).isEqualTo(1);
    }

    @Test
    void duplicateNormalizedSlugReturnsSafeConflict() {
        String token = adminToken();
        String slug = "unique-" + UUID.randomUUID();
        assertThat(createCategory(token, "First", slug, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> duplicate = createCategory(token, "Second", " " + slug.toUpperCase() + " ", null);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).contains("A category with this slug already exists.");
        assertThat(duplicate.getBody()).doesNotContain(slug);
    }

    @Test
    void invalidInputReturnsValidationError() {
        ResponseEntity<String> invalid = createCategory(adminToken(), "  ", "bad_slug!", null);

        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).contains("Request validation failed", "name", "slug");
    }

    @Test
    void adminCanUpdateCategoryMetadataAndStatus() throws Exception {
        String token = adminToken();
        JsonNode created = objectMapper.readTree(createCategory(token, "Before", "before-" + UUID.randomUUID(), null).getBody());
        long id = created.get("id").asLong();
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("name", "Updated Category");
        update.put("slug", " UPDATED   CATEGORY ");
        update.put("description", "Updated description");
        update.put("status", "INACTIVE");

        ResponseEntity<String> response = exchange(ADMIN_CATEGORIES_URL + "/" + id, HttpMethod.PUT, update, token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("name").asText()).isEqualTo("Updated Category");
        assertThat(body.get("slug").asText()).isEqualTo("updated-category");
        assertThat(body.get("description").asText()).isEqualTo("Updated description");
        assertThat(body.get("status").asText()).isEqualTo("INACTIVE");
    }

    @Test
    void deactivationPreservesCategoryAndHidesItFromPublicDiscovery() throws Exception {
        String token = adminToken();
        JsonNode created = objectMapper.readTree(createCategory(token, "Retained", "retained-" + UUID.randomUUID(), null).getBody());
        long id = created.get("id").asLong();

        ResponseEntity<String> deactivated = exchange(
                ADMIN_CATEGORIES_URL + "/" + id + "/deactivate", HttpMethod.PATCH, null, token);
        JsonNode adminRead = objectMapper.readTree(get(ADMIN_CATEGORIES_URL + "/" + id, token).getBody());
        JsonNode publicPage = objectMapper.readTree(restTemplate.getForObject(PUBLIC_CATEGORIES_URL, String.class));

        assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminRead.get("status").asText()).isEqualTo("INACTIVE");
        assertThat(categoryRepository.findById(id)).isPresent();
        assertThat(publicPage.get("content").findValuesAsText("id")).doesNotContain(Long.toString(id));
    }

    @Test
    void onlyAdminsCanWriteCategoriesAndPublicReadIsUnauthenticated() {
        ResponseEntity<String> anonymous = createCategory(null, "Anonymous", "anonymous-" + UUID.randomUUID(), null);
        ResponseEntity<String> customer = createCategory(customerToken(), "Customer", "customer-" + UUID.randomUUID(), null);
        ResponseEntity<String> admin = createCategory(adminToken(), "Admin", "admin-" + UUID.randomUUID(), null);
        ResponseEntity<String> publicRead = restTemplate.getForEntity(PUBLIC_CATEGORIES_URL, String.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(publicRead.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void missingCategoryReturnsNotFound() {
        ResponseEntity<String> response = get(ADMIN_CATEGORIES_URL + "/9223372036854775807", adminToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("Category was not found.");
    }

    private ResponseEntity<String> createCategory(String token, String name, String slug, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("slug", slug);
        body.put("description", description);
        return exchange(ADMIN_CATEGORIES_URL, HttpMethod.POST, body, token);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        return exchange(path, HttpMethod.GET, null, token);
    }

    private String adminToken() { return tokenFor(UserRole.ADMIN); }
    private String customerToken() { return tokenFor(UserRole.CUSTOMER); }

    private String tokenFor(UserRole role) {
        String email = role.name().toLowerCase() + "-category-" + UUID.randomUUID() + "@example.com";
        userRepository.saveAndFlush(new User(email, passwordEncoder.encode(PASSWORD), role, AccountStatus.ENABLED));
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), LoginResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().accessToken();
    }
}
