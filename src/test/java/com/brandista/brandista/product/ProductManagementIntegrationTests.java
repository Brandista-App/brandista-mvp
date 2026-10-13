package com.brandista.brandista.product;

import com.brandista.brandista.auth.api.LoginResponse;
import com.brandista.brandista.product.api.ProductResponse;
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

import java.math.BigDecimal;
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
class ProductManagementIntegrationTests {

    private static final String ADMIN_PRODUCTS_URL = "/api/v1/admin/products";
    private static final String PUBLIC_PRODUCTS_URL = "/api/v1/products";
    private static final String ADMIN_BRANDS_URL = "/api/v1/admin/brands";
    private static final String ADMIN_CATEGORIES_URL = "/api/v1/admin/categories";
    private static final String PASSWORD = "Product-Management-Password-2026!";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void adminCanCreateProductWithDecimalPrice() throws Exception {
        String token = adminToken();
        References refs = createReferences(token);
        String slug = "linen-shirt-" + UUID.randomUUID();

        ResponseEntity<String> response = createProduct(token, "  Linen Shirt ", " Linen   Shirt " + slug.substring("linen-shirt-".length()),
                "Soft cotton", refs, new BigDecimal("1299.95"), "AVAILABLE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode product = objectMapper.readTree(response.getBody());
        assertThat(product.get("name").asText()).isEqualTo("Linen Shirt");
        assertThat(product.get("slug").asText()).isEqualTo(slug);
        assertThat(product.get("price").decimalValue()).isEqualByComparingTo("1299.95");
        assertThat(product.get("currency").asText()).isEqualTo("PKR");
        assertThat(product.get("publicationStatus").asText()).isEqualTo("DRAFT");
        assertThat(product.get("brandId").asLong()).isEqualTo(refs.brandId());
        assertThat(product.get("categoryId").asLong()).isEqualTo(refs.categoryId());
        assertThat(product.has("brand")).isFalse();
        JsonNode adminPage = objectMapper.readTree(get(ADMIN_PRODUCTS_URL + "?page=0&size=100", token).getBody());
        assertThat(adminPage.get("content").findValuesAsText("id")).contains(product.get("id").asText());
    }

    @Test
    void invalidNegativeOrOverPrecisionPriceIsRejected() {
        String token = adminToken();
        References refs = createReferences(token);

        ResponseEntity<String> negative = createProduct(token, "Invalid negative", "invalid-negative-" + UUID.randomUUID(),
                null, refs, new BigDecimal("-0.01"), "AVAILABLE");
        ResponseEntity<String> tooPrecise = createProduct(token, "Invalid scale", "invalid-scale-" + UUID.randomUUID(),
                null, refs, new BigDecimal("1.999"), "AVAILABLE");

        assertThat(negative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(negative.getBody()).contains("Request validation failed", "price");
        assertThat(tooPrecise.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownBrandOrCategoryReturnsNotFound() {
        String token = adminToken();
        References refs = createReferences(token);

        ResponseEntity<String> missingBrand = createProduct(token, "Missing brand", "missing-brand-" + UUID.randomUUID(),
                null, new References(Long.MAX_VALUE, refs.categoryId()), new BigDecimal("1.00"), "AVAILABLE");
        ResponseEntity<String> missingCategory = createProduct(token, "Missing category", "missing-category-" + UUID.randomUUID(),
                null, new References(refs.brandId(), Long.MAX_VALUE), new BigDecimal("1.00"), "AVAILABLE");

        assertThat(missingBrand.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missingBrand.getBody()).contains("selected brand or category was not found");
        assertThat(missingCategory.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void normalizedDuplicateSlugReturnsConflict() {
        String token = adminToken();
        References refs = createReferences(token);
        String slug = "duplicate-product-" + UUID.randomUUID();

        assertThat(createProduct(token, "First", slug, null, refs, new BigDecimal("10.00"), "AVAILABLE")
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> duplicate = createProduct(token, "Second", " " + slug.toUpperCase() + " ",
                null, refs, new BigDecimal("11.00"), "AVAILABLE");

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).contains("A product with this slug already exists.");
        assertThat(duplicate.getBody()).doesNotContain(slug);
    }

    @Test
    void adminCanUpdateProductAndPrice() throws Exception {
        String token = adminToken();
        References refs = createReferences(token);
        JsonNode created = objectMapper.readTree(createProduct(token, "Before", "before-" + UUID.randomUUID(),
                null, refs, new BigDecimal("12.50"), "AVAILABLE").getBody());
        long id = created.get("id").asLong();
        Map<String, Object> update = productRequest("After", " Updated   Product ", "New details", refs,
                new BigDecimal("34.20"), "UNAVAILABLE");

        ResponseEntity<String> response = exchange(ADMIN_PRODUCTS_URL + "/" + id, HttpMethod.PUT, update, token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode updated = objectMapper.readTree(response.getBody());
        assertThat(updated.get("name").asText()).isEqualTo("After");
        assertThat(updated.get("slug").asText()).isEqualTo("updated-product");
        assertThat(updated.get("description").asText()).isEqualTo("New details");
        assertThat(updated.get("price").decimalValue()).isEqualByComparingTo("34.20");
        assertThat(updated.get("availability").asText()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void draftUnpublishedAndDeactivatedProductsAreHiddenFromPublicReads() throws Exception {
        String token = adminToken();
        References refs = createReferences(token);
        JsonNode created = objectMapper.readTree(createProduct(token, "Public Candidate", "candidate-" + UUID.randomUUID(),
                null, refs, new BigDecimal("100.00"), "AVAILABLE").getBody());
        long id = created.get("id").asLong();
        String slug = created.get("slug").asText();

        assertThat(publicListContains(id)).isFalse();
        ResponseEntity<String> published = exchange(ADMIN_PRODUCTS_URL + "/" + id + "/publish", HttpMethod.PATCH, null, token);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicListContains(id)).isTrue();
        assertThat(restTemplate.getForEntity(PUBLIC_PRODUCTS_URL + "/" + slug, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        exchange(ADMIN_PRODUCTS_URL + "/" + id + "/unpublish", HttpMethod.PATCH, null, token);
        assertThat(publicListContains(id)).isFalse();
        exchange(ADMIN_PRODUCTS_URL + "/" + id + "/publish", HttpMethod.PATCH, null, token);
        ResponseEntity<String> deactivated = exchange(ADMIN_PRODUCTS_URL + "/" + id + "/deactivate", HttpMethod.PATCH, null, token);

        assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(get(ADMIN_PRODUCTS_URL + "/" + id, token).getBody())
                .get("publicationStatus").asText()).isEqualTo("DEACTIVATED");
        assertThat(publicListContains(id)).isFalse();
        assertThat(restTemplate.getForEntity(PUBLIC_PRODUCTS_URL + "/" + slug, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void cannotPublishWhenBrandOrCategoryIsInactive() throws Exception {
        String token = adminToken();
        References refs = createReferences(token);
        JsonNode product = createProductNode(token, "Parent status", "parent-status-" + UUID.randomUUID(), refs);
        long productId = product.get("id").asLong();
        assertThat(exchange(ADMIN_PRODUCTS_URL + "/" + productId + "/publish", HttpMethod.PATCH, null, token)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicListContains(productId)).isTrue();
        exchange(ADMIN_CATEGORIES_URL + "/" + refs.categoryId() + "/deactivate", HttpMethod.PATCH, null, token);

        ResponseEntity<String> response = exchange(
                ADMIN_PRODUCTS_URL + "/" + productId + "/publish", HttpMethod.PATCH, null, token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("Products require active brands and categories.");
        assertThat(publicListContains(productId)).isFalse();
        assertThat(get(ADMIN_PRODUCTS_URL + "/" + productId, token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void productWriteRequiresAdminAndPublicListIsPublic() {
        References refs = createReferences(adminToken());
        ResponseEntity<String> anonymous = createProduct(null, "Anonymous", "anonymous-" + UUID.randomUUID(),
                null, refs, new BigDecimal("1.00"), "AVAILABLE");
        ResponseEntity<String> customer = createProduct(customerToken(), "Customer", "customer-" + UUID.randomUUID(),
                null, refs, new BigDecimal("1.00"), "AVAILABLE");
        ResponseEntity<String> publicList = restTemplate.getForEntity(PUBLIC_PRODUCTS_URL, String.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(publicList.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private JsonNode createProductNode(String token, String name, String slug, References refs) {
        try {
            return objectMapper.readTree(createProduct(token, name, slug, null, refs,
                    new BigDecimal("25.00"), "AVAILABLE").getBody());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private boolean publicListContains(long id) throws Exception {
        JsonNode body = objectMapper.readTree(restTemplate.getForObject(PUBLIC_PRODUCTS_URL + "?size=100", String.class));
        return body.get("content").findValuesAsText("id").contains(Long.toString(id));
    }

    private References createReferences(String token) {
        try {
            String suffix = UUID.randomUUID().toString();
            ResponseEntity<String> brand = exchange(ADMIN_BRANDS_URL, HttpMethod.POST,
                    Map.of("name", "Product Brand " + suffix, "slug", "product-brand-" + suffix), token);
            ResponseEntity<String> category = exchange(ADMIN_CATEGORIES_URL, HttpMethod.POST,
                    Map.of("name", "Product Category " + suffix, "slug", "product-category-" + suffix), token);
            assertThat(brand.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(category.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            return new References(objectMapper.readTree(brand.getBody()).get("id").asLong(),
                    objectMapper.readTree(category.getBody()).get("id").asLong());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private ResponseEntity<String> createProduct(String token, String name, String slug, String description,
                                                 References refs, BigDecimal price, String availability) {
        return exchange(ADMIN_PRODUCTS_URL, HttpMethod.POST,
                productRequest(name, slug, description, refs, price, availability), token);
    }

    private Map<String, Object> productRequest(String name, String slug, String description,
                                               References refs, BigDecimal price, String availability) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("slug", slug);
        body.put("description", description);
        body.put("brandId", refs.brandId());
        body.put("categoryId", refs.categoryId());
        body.put("price", price);
        body.put("availability", availability);
        return body;
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
        String email = role.name().toLowerCase() + "-product-" + UUID.randomUUID() + "@example.com";
        userRepository.saveAndFlush(new User(email, passwordEncoder.encode(PASSWORD), role, AccountStatus.ENABLED));
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), LoginResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().accessToken();
    }

    private record References(Long brandId, Long categoryId) { }
}
