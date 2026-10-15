package com.brandista.brandista.admin;

import com.brandista.brandista.user.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies existing global administration, not a production provisioning mechanism. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-audit;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="})
class AdminWorkflowIntegrationTests {
    @Autowired TestRestTemplate http;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;

    @Test
    void globalAdminCatalogueLifecycleControlsCustomerVisibility() throws Exception {
        String admin = identity(UserRole.ADMIN);
        String suffix = UUID.randomUUID().toString();
        long brand = body(call(HttpMethod.POST, "/admin/brands", admin,
                Map.of("name", "Audit Brand", "slug", "audit-brand-" + suffix), 201)).get("id").asLong();
        long category = body(call(HttpMethod.POST, "/admin/categories", admin,
                Map.of("name", "Audit Category", "slug", "audit-category-" + suffix), 201)).get("id").asLong();
        String slug = "audit-product-" + suffix;
        Map<String, Object> details = Map.of("name", "Audit Product", "slug", slug,
                "brandId", brand, "categoryId", category, "price", "1250.50", "availability", "AVAILABLE");
        JsonNode created = body(call(HttpMethod.POST, "/admin/products", admin, details, 201));
        long product = created.get("id").asLong();
        String path = "/admin/products/" + product;
        assertThat(created.get("publicationStatus").asText()).isEqualTo("DRAFT");
        assertThat(created.get("stockQuantity").asInt()).isZero();
        call(HttpMethod.GET, "/products/" + slug, null, null, 404);
        call(HttpMethod.PUT, path, admin, details, 200);
        call(HttpMethod.PUT, path + "/stock", admin, Map.of("stockQuantity", 3), 200);
        call(HttpMethod.PATCH, path + "/publish", admin, null, 200);
        JsonNode visible = body(call(HttpMethod.GET, "/products/" + slug, null, null, 200));
        assertThat(visible.get("stockQuantity").asInt()).isEqualTo(3);
        assertThat(visible.get("price").decimalValue()).isEqualByComparingTo("1250.50");
        JsonNode filtered = body(call(HttpMethod.GET, "/products?brandId=" + brand, null, null, 200));
        assertThat(filtered.get("content").findValuesAsText("slug")).containsExactly(slug);
        call(HttpMethod.PATCH, path + "/unpublish", admin, null, 200);
        call(HttpMethod.GET, "/products/" + slug, null, null, 404);
        call(HttpMethod.PATCH, path + "/publish", admin, null, 200);
        call(HttpMethod.PATCH, "/admin/brands/" + brand + "/deactivate", admin, null, 200);
        call(HttpMethod.GET, "/products/" + slug, null, null, 404);
        call(HttpMethod.PATCH, path + "/deactivate", admin, null, 200);
        assertThat(body(call(HttpMethod.GET, path, admin, null, 200))
                .get("publicationStatus").asText()).isEqualTo("DEACTIVATED");
    }

    @Test
    void inventoryRejectsAnonymousCustomersAndInvalidCounts() throws Exception {
        String path = "/admin/products/999999/stock";
        call(HttpMethod.PUT, path, null, Map.of("stockQuantity", 1), 401);
        call(HttpMethod.PUT, path, identity(UserRole.CUSTOMER), Map.of("stockQuantity", 1), 403);
        String admin = identity(UserRole.ADMIN);
        call(HttpMethod.PUT, path, admin, Map.of("stockQuantity", -1), 400);
        call(HttpMethod.PUT, path, admin, Map.of(), 400);
        call(HttpMethod.PUT, path, admin, Map.of("stockQuantity", 1), 404);
    }

    private String identity(UserRole role) throws Exception {
        String email = "admin-audit-" + UUID.randomUUID() + "@example.test";
        String password = UUID.randomUUID() + "Audit!9";
        users.saveAndFlush(new User(email, passwords.encode(password), role, AccountStatus.ENABLED));
        return body(call(HttpMethod.POST, "/auth/login", null,
                Map.of("email", email, "password", password), 200)).get("accessToken").asText();
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token, Object payload, int status) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        ResponseEntity<String> response = http.exchange("/api/v1" + path, method,
                new HttpEntity<>(payload, headers), String.class);
        assertThat(response.getStatusCode().value()).as("%s %s", method, path).isEqualTo(status);
        return response;
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }
}
