package com.brandista.brandista.product;

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
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
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
class ProductDiscoveryIntegrationTests {

    private static final String PUBLIC_URL = "/api/v1/products";
    private static final String ADMIN_PRODUCTS_URL = "/api/v1/admin/products";
    private static final String ADMIN_BRANDS_URL = "/api/v1/admin/brands";
    private static final String ADMIN_CATEGORIES_URL = "/api/v1/admin/categories";
    private static final String PASSWORD = "Product-Discovery-Password-2026!";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void searchesNameAndDescriptionAndCombinesBrandAndCategoryFilters() throws Exception {
        String token = adminToken();
        References target = createReferences(token);
        References other = createReferences(token);
        JsonNode expected = createPublishedProduct(token, "Silk Linen Tunic", "summer breathable fabric", target,
                "1299.50");
        createPublishedProduct(token, "Leather Wallet", "handmade accessory", other, "499.00");

        JsonNode byName = getJson(uri("keyword", "linen"));
        JsonNode byDescription = getJson(uri("keyword", "BREATHABLE"));
        JsonNode wildcardIsLiteral = getJson(uri("keyword", "%"));
        JsonNode combined = getJson(uri("keyword", "summer", "brandId", target.brandId().toString(),
                "categoryId", target.categoryId().toString()));

        assertThat(ids(byName)).contains(expected.get("id").asText());
        assertThat(ids(byDescription)).contains(expected.get("id").asText());
        assertThat(wildcardIsLiteral.get("content")).isEmpty();
        assertThat(ids(combined)).containsExactly(expected.get("id").asText());
        JsonNode publicProduct = combined.get("content").get(0);
        assertThat(publicProduct.get("brandName").asText()).startsWith("Discovery Brand");
        assertThat(publicProduct.get("categoryName").asText()).startsWith("Discovery Category");
        assertThat(publicProduct.get("currency").asText()).isEqualTo("PKR");
        assertThat(publicProduct.has("publicationStatus")).isFalse();
        assertThat(publicProduct.has("createdAt")).isFalse();
        assertThat(publicProduct.has("updatedAt")).isFalse();
    }

    @Test
    void sortsByPriceAndPaginatesWithStableTies() throws Exception {
        String token = adminToken();
        References refs = createReferences(token);
        JsonNode expensive = createPublishedProduct(token, "Discovery C", "description", refs, "30.00");
        JsonNode cheapest = createPublishedProduct(token, "Discovery A", "description", refs, "10.00");
        JsonNode middle = createPublishedProduct(token, "Discovery B", "description", refs, "20.00");

        JsonNode firstPage = getJson(uri("brandId", refs.brandId().toString(), "categoryId", refs.categoryId().toString(),
                "size", "1", "page", "0", "sort", "price_asc"));
        JsonNode secondPage = getJson(uri("brandId", refs.brandId().toString(), "categoryId", refs.categoryId().toString(),
                "size", "1", "page", "1", "sort", "price_asc"));
        JsonNode descending = getJson(uri("brandId", refs.brandId().toString(), "categoryId", refs.categoryId().toString(),
                "size", "1", "sort", "price_desc"));

        assertThat(firstPage.get("totalElements").asLong()).isEqualTo(3);
        assertThat(firstPage.get("content").get(0).get("id").asText()).isEqualTo(cheapest.get("id").asText());
        assertThat(secondPage.get("content").get(0).get("id").asText()).isEqualTo(middle.get("id").asText());
        assertThat(descending.get("content").get(0).get("id").asText()).isEqualTo(expensive.get("id").asText());

        JsonNode defaultSort = getJson(uri("brandId", refs.brandId().toString(), "categoryId", refs.categoryId().toString()));
        assertThat(defaultSort.get("content").get(0).get("name").asText()).isEqualTo("Discovery A");
    }

    @Test
    void emptyResultsAndUnpublishedOrInactiveProductsStayHidden() throws Exception {
        String token = adminToken();
        References refs = createReferences(token);
        JsonNode draft = createProduct(token, "Hidden Draft", "hidden description", refs, "15.00");
        JsonNode published = createPublishedProduct(token, "Visible Item", "visible description", refs, "25.00");

        JsonNode noMatches = getJson(uri("brandId", "9223372036854770000", "size", "7"));
        assertThat(noMatches.get("content")).isEmpty();
        assertThat(noMatches.get("totalElements").asLong()).isZero();
        assertThat(restTemplate.getForEntity(PUBLIC_URL + "/" + draft.get("slug").asText(), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity(PUBLIC_URL + "/" + published.get("slug").asText(), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        exchange(ADMIN_BRANDS_URL + "/" + refs.brandId() + "/deactivate", HttpMethod.PATCH, null, token);
        JsonNode afterBrandDeactivation = getJson(uri("brandId", refs.brandId().toString()));
        assertThat(afterBrandDeactivation.get("content")).isEmpty();
        assertThat(restTemplate.getForEntity(PUBLIC_URL + "/" + published.get("slug").asText(), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void inactiveCategoryIsExcludedFromPublicDiscovery() throws Exception {
        String token = adminToken();
        References refs = createReferences(token);
        JsonNode product = createPublishedProduct(token, "Category-hidden Item", "details", refs, "15.00");

        exchange(ADMIN_CATEGORIES_URL + "/" + refs.categoryId() + "/deactivate", HttpMethod.PATCH, null, token);

        assertThat(getJson(uri("categoryId", refs.categoryId().toString())).get("content")).isEmpty();
        assertThat(restTemplate.getForEntity(PUBLIC_URL + "/" + product.get("slug").asText(), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidPaginationFiltersSortAndKeywordReturnSafeBadRequest() {
        assertBadRequest(PUBLIC_URL + "?page=-1");
        assertBadRequest(PUBLIC_URL + "?size=0");
        assertBadRequest(PUBLIC_URL + "?size=101");
        assertBadRequest(PUBLIC_URL + "?sort=name");
        assertBadRequest(PUBLIC_URL + "?brandId=0");
        assertBadRequest(PUBLIC_URL + "?categoryId=-1");
        assertBadRequest(PUBLIC_URL + "?keyword=" + "x".repeat(101));
    }

    private void assertBadRequest(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Request validation failed.");
        assertThat(response.getBody()).doesNotContain("Exception", "stackTrace", "select ");
    }

    private JsonNode createPublishedProduct(String token, String name, String description,
                                            References refs, String price) throws Exception {
        JsonNode product = createProduct(token, name, description, refs, price);
        ResponseEntity<String> published = exchange(ADMIN_PRODUCTS_URL + "/" + product.get("id").asLong() + "/publish",
                HttpMethod.PATCH, null, token);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        return product;
    }

    private JsonNode createProduct(String token, String name, String description, References refs, String price)
            throws Exception {
        String suffix = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("slug", name.toLowerCase().replace(' ', '-') + "-" + suffix);
        body.put("description", description);
        body.put("brandId", refs.brandId());
        body.put("categoryId", refs.categoryId());
        body.put("price", new BigDecimal(price));
        body.put("availability", "AVAILABLE");
        ResponseEntity<String> response = exchange(ADMIN_PRODUCTS_URL, HttpMethod.POST, body, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody());
    }

    private References createReferences(String token) throws Exception {
        String suffix = UUID.randomUUID().toString();
        ResponseEntity<String> brand = exchange(ADMIN_BRANDS_URL, HttpMethod.POST,
                Map.of("name", "Discovery Brand " + suffix, "slug", "discovery-brand-" + suffix), token);
        ResponseEntity<String> category = exchange(ADMIN_CATEGORIES_URL, HttpMethod.POST,
                Map.of("name", "Discovery Category " + suffix, "slug", "discovery-category-" + suffix), token);
        assertThat(brand.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(category.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new References(objectMapper.readTree(brand.getBody()).get("id").asLong(),
                objectMapper.readTree(category.getBody()).get("id").asLong());
    }

    private JsonNode getJson(URI uri) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private URI uri(String... queryPairs) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(PUBLIC_URL);
        for (int i = 0; i < queryPairs.length; i += 2) builder.queryParam(queryPairs[i], queryPairs[i + 1]);
        return builder.build().encode().toUri();
    }

    private java.util.List<String> ids(JsonNode page) {
        java.util.List<String> result = new java.util.ArrayList<>();
        page.get("content").forEach(product -> result.add(product.get("id").asText()));
        return result;
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private String adminToken() {
        String email = "admin-discovery-" + UUID.randomUUID() + "@example.com";
        userRepository.saveAndFlush(new User(email, passwordEncoder.encode(PASSWORD), UserRole.ADMIN, AccountStatus.ENABLED));
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), LoginResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().accessToken();
    }

    private record References(Long brandId, Long categoryId) { }
}
