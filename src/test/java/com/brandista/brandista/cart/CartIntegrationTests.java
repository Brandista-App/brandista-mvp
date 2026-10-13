package com.brandista.brandista.cart;

import com.brandista.brandista.auth.api.LoginResponse;
import com.brandista.brandista.brand.Brand;
import com.brandista.brandista.brand.BrandRepository;
import com.brandista.brandista.category.Category;
import com.brandista.brandista.category.CategoryRepository;
import com.brandista.brandista.product.Product;
import com.brandista.brandista.product.ProductAvailability;
import com.brandista.brandista.product.ProductRepository;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:brandista-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class CartIntegrationTests {

    private static final String CART_URL = "/api/v1/customers/me/cart";
    private static final String PASSWORD = "Cart-Integration-Password-2026!";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private BrandRepository brandRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void customerCanRetrieveAnEmptyPersistentCart() throws Exception {
        Identity customer = identity();
        ResponseEntity<String> response = exchange(CART_URL, HttpMethod.GET, null, customer.token());
        JsonNode cart = objectMapper.readTree(response.getBody());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cart.get("items")).isEmpty();
        assertThat(cart.get("subtotal").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cart.get("currency").asText()).isEqualTo("PKR");
    }

    @Test
    void addingSameProductIncreasesQuantityAndUsesDecimalSafeCurrentPrice() throws Exception {
        Identity customer = identity();
        Product product = product("1234.55", ProductAvailability.AVAILABLE);

        JsonNode first = add(customer, product, 2);
        JsonNode second = add(customer, product, 3);

        assertThat(first.get("items")).hasSize(1);
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("items").get(0).get("quantity").asInt()).isEqualTo(5);
        assertThat(second.get("items").get(0).get("unitPrice").decimalValue())
                .isEqualByComparingTo("1234.55");
        assertThat(second.get("items").get(0).get("lineSubtotal").decimalValue())
                .isEqualByComparingTo("6172.75");
        assertThat(second.get("subtotal").decimalValue()).isEqualByComparingTo("6172.75");
        assertThat(second.get("items").get(0).get("available").asBoolean()).isTrue();
    }

    @Test
    void updatingRemovingAndClearingItemsAffectsOnlyTheAuthenticatedCart() throws Exception {
        Identity owner = identity();
        Identity other = identity();
        Product p1 = product("100.25", ProductAvailability.AVAILABLE);
        Product p2 = product("50.10", ProductAvailability.AVAILABLE);

        JsonNode withOne = add(owner, p1, 1);
        long firstId = withOne.get("items").get(0).get("itemId").asLong();
        JsonNode updated = objectMapper.readTree(exchange(CART_URL + "/items/" + firstId, HttpMethod.PATCH,
                Map.of("quantity", 4), owner.token()).getBody());
        assertThat(updated.get("subtotal").decimalValue()).isEqualByComparingTo("401.00");

        ResponseEntity<String> foreignUpdate = exchange(CART_URL + "/items/" + firstId, HttpMethod.PATCH,
                Map.of("quantity", 8), other.token());
        ResponseEntity<String> foreignDelete = exchange(CART_URL + "/items/" + firstId, HttpMethod.DELETE,
                null, other.token());
        assertThat(foreignUpdate.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreignDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(exchange(CART_URL + "/items/" + firstId, HttpMethod.DELETE, null, owner.token())
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        add(owner, p1, 2);
        add(owner, p2, 1);
        JsonNode otherCart = add(other, p2, 2);
        ResponseEntity<String> clear = exchange(CART_URL + "/items", HttpMethod.DELETE, null, owner.token());
        assertThat(clear.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(clear.getBody()).get("items")).isEmpty();
        assertThat(objectMapper.readTree(exchange(CART_URL, HttpMethod.GET, null, other.token()).getBody())
                .get("items")).hasSize(1);
        assertThat(otherCart.get("items")).hasSize(1);
    }

    @Test
    void invalidQuantitiesAndUnavailableProductsAreRejected() {
        Identity customer = identity();
        Product product = product("12.99", ProductAvailability.AVAILABLE);

        ResponseEntity<String> zero = exchange(CART_URL + "/items", HttpMethod.POST,
                Map.of("productId", product.getId(), "quantity", 0), customer.token());
        ResponseEntity<String> negative = exchange(CART_URL + "/items", HttpMethod.POST,
                Map.of("productId", product.getId(), "quantity", -2), customer.token());
        ResponseEntity<String> unavailable = exchange(CART_URL + "/items", HttpMethod.POST,
                Map.of("productId", product("12.99", ProductAvailability.UNAVAILABLE).getId(), "quantity", 1),
                customer.token());
        ResponseEntity<String> unpublished = exchange(CART_URL + "/items", HttpMethod.POST,
                Map.of("productId", productDraft().getId(), "quantity", 1), customer.token());

        assertThat(zero.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(negative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(unpublished.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void inactiveBrandOrCategoryPreventsAddingAndRepeatedAddsCannotOverflowQuantity() {
        Identity customer = identity();
        Product inactiveBrandProduct = product("12.99", ProductAvailability.AVAILABLE);
        inactiveBrandProduct.getBrand().deactivate();
        brandRepository.saveAndFlush(inactiveBrandProduct.getBrand());

        Product inactiveCategoryProduct = product("12.99", ProductAvailability.AVAILABLE);
        inactiveCategoryProduct.getCategory().deactivate();
        categoryRepository.saveAndFlush(inactiveCategoryProduct.getCategory());

        Product valid = product("12.99", ProductAvailability.AVAILABLE);
        ResponseEntity<String> fill = exchange(CART_URL + "/items", HttpMethod.POST,
                Map.of("productId", valid.getId(), "quantity", Integer.MAX_VALUE), customer.token());
        ResponseEntity<String> overflow = exchange(CART_URL + "/items", HttpMethod.POST,
                Map.of("productId", valid.getId(), "quantity", 1), customer.token());
        ResponseEntity<String> inactiveBrand = exchange(CART_URL + "/items", HttpMethod.POST,
                Map.of("productId", inactiveBrandProduct.getId(), "quantity", 1), customer.token());
        ResponseEntity<String> inactiveCategory = exchange(CART_URL + "/items", HttpMethod.POST,
                Map.of("productId", inactiveCategoryProduct.getId(), "quantity", 1), customer.token());

        assertThat(fill.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(overflow.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(overflow.getBody()).contains("positive integer within the supported range");
        assertThat(inactiveBrand.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inactiveCategory.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cartRoutesRequireCustomerAuthenticationAndDoNotAcceptOwnerIds() throws Exception {
        Identity customer = identity();
        Product product = product("9.99", ProductAvailability.AVAILABLE);
        ResponseEntity<String> anonymous = exchange(CART_URL, HttpMethod.GET, null, null);
        ResponseEntity<String> admin = exchange(CART_URL, HttpMethod.GET, null, identity(UserRole.ADMIN).token());
        ResponseEntity<String> bodyWithOwnerId = exchange(CART_URL + "/items", HttpMethod.POST,
                Map.of("productId", product.getId(), "quantity", 1, "customerId", customer.userId()), customer.token());

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bodyWithOwnerId.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(bodyWithOwnerId.getBody()).has("customerId")).isFalse();
    }

    @Test
    void aProductMadeIneligibleAfterBeingAddedIsMarkedUnavailable() throws Exception {
        Identity customer = identity();
        Product product = product("77.77", ProductAvailability.AVAILABLE);
        add(customer, product, 1);
        product.unpublish();
        productRepository.saveAndFlush(product);

        JsonNode cart = objectMapper.readTree(exchange(CART_URL, HttpMethod.GET, null, customer.token()).getBody());
        assertThat(cart.get("items").get(0).get("available").asBoolean()).isFalse();
    }

    private JsonNode add(Identity identity, Product product, int quantity) throws Exception {
        ResponseEntity<String> response = exchange(CART_URL + "/items", HttpMethod.POST,
                Map.of("productId", product.getId(), "quantity", quantity), identity.token());
        assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private Product product(String price, ProductAvailability availability) {
        Brand brand = brandRepository.saveAndFlush(new Brand("Cart Brand " + UUID.randomUUID(),
                "cart-brand-" + UUID.randomUUID(), null, null));
        Category category = categoryRepository.saveAndFlush(new Category("Cart Category " + UUID.randomUUID(),
                "cart-category-" + UUID.randomUUID(), null));
        Product product = new Product("Cart Product " + UUID.randomUUID(), "cart-product-" + UUID.randomUUID(),
                null, brand, category, new BigDecimal(price), availability);
        product.publish();
        return productRepository.saveAndFlush(product);
    }

    private Product productDraft() {
        Brand brand = brandRepository.saveAndFlush(new Brand("Draft Brand " + UUID.randomUUID(),
                "draft-brand-" + UUID.randomUUID(), null, null));
        Category category = categoryRepository.saveAndFlush(new Category("Draft Category " + UUID.randomUUID(),
                "draft-category-" + UUID.randomUUID(), null));
        return productRepository.saveAndFlush(new Product("Draft Product " + UUID.randomUUID(),
                "draft-product-" + UUID.randomUUID(), null, brand, category,
                new BigDecimal("10.00"), ProductAvailability.AVAILABLE));
    }

    private Identity identity() { return identity(UserRole.CUSTOMER); }

    private Identity identity(UserRole role) {
        String email = "cart-" + UUID.randomUUID() + "@example.com";
        User user = userRepository.saveAndFlush(new User(email, passwordEncoder.encode(PASSWORD), role, AccountStatus.ENABLED));
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity("/api/v1/auth/login",
                Map.of("email", email, "password", PASSWORD), LoginResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Identity(user.getId(), response.getBody().accessToken());
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private record Identity(Long userId, String token) { }
}
