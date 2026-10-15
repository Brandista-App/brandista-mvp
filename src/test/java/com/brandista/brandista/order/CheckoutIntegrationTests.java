package com.brandista.brandista.order;

import com.brandista.brandista.user.*;
import com.brandista.brandista.product.*;
import com.brandista.brandista.brand.*;
import com.brandista.brandista.category.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:brandista-checkout-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","brandista.checkout.cod-enabled=true"})
class CheckoutIntegrationTests {
    private static final String ROOT="/api/v1/customers/me";
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ProductRepository products;
    @Autowired BrandRepository brands;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.brandista.brandista.cart.CartRepository cartRepository;
    private record Identity(Long id,String token) { }
    private Identity customer() throws Exception {
        String email=UUID.randomUUID()+"@example.test", password="Checkout-test-password-2026!";
        User user=users.saveAndFlush(new User(email,encoder.encode(password),UserRole.CUSTOMER,AccountStatus.ENABLED));
        JsonNode login=json(call(HttpMethod.POST,"/api/v1/auth/login",null,Map.of("email",email,"password",password),null));
        return new Identity(user.getId(),login.get("accessToken").asText());
    }
    private Product product(int stock,String price) {
        String slug="checkout-"+UUID.randomUUID();
        var brand=brands.saveAndFlush(new Brand("Demo "+slug,slug,null,null));
        var category=categories.saveAndFlush(new Category("Demo "+slug,slug,null));
        var p=new Product("Demo item",slug,"Synthetic checkout fixture",brand,category,new BigDecimal(price),ProductAvailability.AVAILABLE);
        p.setStockQuantity(stock);p.publish();return products.saveAndFlush(p);
    }
    private Map<String,Object> addressBody(String city) {
        return Map.of("recipientName","Synthetic Customer","phoneNumber","+92 300 1234567","addressLine1","Demo House 1","city",city,"province","Punjab","deliveryInstructions","Ring once");
    }
    private long address(Identity owner) throws Exception { return json(call(HttpMethod.POST,ROOT+"/addresses",owner,addressBody("Lahore"),null)).get("id").asLong(); }
    private void add(Identity owner,Product product,int quantity) { assertThat(call(HttpMethod.POST,ROOT+"/cart/items",owner,Map.of("productId",product.getId(),"quantity",quantity),null).getStatusCode().is2xxSuccessful()).isTrue(); }
    private String quote(Identity owner,long address) throws Exception {
        var response=call(HttpMethod.POST,ROOT+"/checkout/preview",owner,Map.of("addressId",address),null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);return json(response).get("quoteToken").asText();
    }
    private ResponseEntity<String> place(Identity owner,long address,String quote,String key) {
        return call(HttpMethod.POST,ROOT+"/orders",owner,Map.of("addressId",address,"quoteToken",quote),key);
    }
    private ResponseEntity<String> call(HttpMethod method,String path,Identity owner,Object body,String key) {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(owner!=null)headers.setBearerAuth(owner.token());if(key!=null)headers.set("Idempotency-Key",key);
        return http.exchange(path,method,new HttpEntity<>(body,headers),String.class);
    }
    private JsonNode json(ResponseEntity<String> response) throws Exception { return mapper.readTree(response.getBody()); }

    @Test void defaultAddressIsUniqueOwnedAndMovesOnDeletion() throws Exception {
        var owner=customer();var other=customer();long first=address(owner),second=address(owner);
        var saved=json(call(HttpMethod.GET,ROOT+"/addresses/"+first,owner,null,null));assertThat(saved.get("isDefault").asBoolean()).isTrue();
        assertThat(call(HttpMethod.PUT,ROOT+"/addresses/"+second+"/default",other,null,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT,ROOT+"/addresses/"+second+"/default",owner,null,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        var list=json(call(HttpMethod.GET,ROOT+"/addresses",owner,null,null));
        assertThat(list.findValues("isDefault").stream().filter(JsonNode::asBoolean).count()).isEqualTo(1);
        call(HttpMethod.DELETE,ROOT+"/addresses/"+second,owner,null,null);
        assertThat(users.findById(owner.id()).orElseThrow().getDefaultAddressId()).isEqualTo(first);
        call(HttpMethod.DELETE,ROOT+"/addresses/"+first,owner,null,null);
        assertThat(users.findById(owner.id()).orElseThrow().getDefaultAddressId()).isNull();
    }

    @Test void browserPreflightAllowsCheckoutIdempotencyHeaderOnlyForConfiguredOrigin() {
        HttpHeaders headers=new HttpHeaders();
        headers.setOrigin("https://client.brandista.test");
        headers.setAccessControlRequestMethod(HttpMethod.POST);
        headers.setAccessControlRequestHeaders(List.of("authorization","content-type","idempotency-key"));
        var response=http.exchange(ROOT+"/orders",HttpMethod.OPTIONS,new HttpEntity<>(headers),String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowHeaders()).anyMatch(value -> value.equalsIgnoreCase("idempotency-key"));
        headers.setOrigin("https://untrusted.example.test");
        var denied=http.exchange(ROOT+"/orders",HttpMethod.OPTIONS,new HttpEntity<>(headers),String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test void multiBrandOrderUsesServerTotalsAndImmutableSnapshotsAndReplays() throws Exception {
        var owner=customer();var other=customer();long address=address(owner);
        var first=product(10,"2499.95");var second=product(10,"1250.50");add(owner,first,2);add(owner,second,1);
        String review=quote(owner,address),key=UUID.randomUUID().toString();
        var placed=place(owner,address,review,key);assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var order=json(placed);long id=order.get("id").asLong();
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("6250.40");assertThat(order.get("items")).hasSize(2);
        assertThat(order.get("paymentStatus").asText()).isEqualTo("UNPAID");assertThat(order.get("status").asText()).isEqualTo("PLACED");
        assertThat(products.findById(first.getId()).orElseThrow().getStockQuantity()).isEqualTo(8);
        assertThat(json(call(HttpMethod.GET,ROOT+"/cart",owner,null,null)).get("items")).isEmpty();
        var replay=place(owner,address,review,key);assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(json(replay).get("id").asLong()).isEqualTo(id);
        assertThat(place(owner,address,"0".repeat(64),key).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.GET,ROOT+"/orders/"+id,other,null,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(call(HttpMethod.GET,ROOT+"/orders",other,null,null)).get("content")).isEmpty();
        call(HttpMethod.PUT,ROOT+"/addresses/"+address,owner,addressBody("Karachi"),null);call(HttpMethod.DELETE,ROOT+"/addresses/"+address,owner,null,null);
        first.updateDetails("Changed name",first.getSlug(),null,first.getBrand(),first.getCategory(),new BigDecimal("9000.00"),ProductAvailability.AVAILABLE);products.saveAndFlush(first);
        var historic=json(call(HttpMethod.GET,ROOT+"/orders/"+id,owner,null,null));
        assertThat(historic.get("deliveryAddress").get("city").asText()).isEqualTo("Lahore");
        assertThat(historic.get("deliveryAddress").get("deliveryInstructions").asText()).isEqualTo("Ring once");
        assertThat(historic.get("items").get(0).get("productName").asText()).isEqualTo("Demo item");
        assertThat(historic.get("total").decimalValue()).isEqualByComparingTo("6250.40");
        assertThat(place(owner,address,review,key).getStatusCode()).isEqualTo(HttpStatus.OK); // Replay survives deleted source address.
    }

    @Test void stalePricesOrOneUnavailableLineLeaveCartStockAndOrdersUnchanged() throws Exception {
        var owner=customer();long address=address(owner);var first=product(5,"10.00");var second=product(5,"20.00");add(owner,first,1);add(owner,second,1);
        String review=quote(owner,address);long count=orders.count();
        first.updateDetails(first.getName(),first.getSlug(),null,first.getBrand(),first.getCategory(),new BigDecimal("11.00"),ProductAvailability.AVAILABLE);products.saveAndFlush(first);
        assertThat(place(owner,address,review,UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        review=quote(owner,address);second.setStockQuantity(0);products.saveAndFlush(second);
        assertThat(place(owner,address,review,UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(products.findById(first.getId()).orElseThrow().getStockQuantity()).isEqualTo(5);
        assertThat(orders.count()).isEqualTo(count);assertThat(json(call(HttpMethod.GET,ROOT+"/cart",owner,null,null)).get("items")).hasSize(2);
    }

    @Test void rejectsForeignAddressEmptyCartInvalidKeyAndInactiveProducts() throws Exception {
        var owner=customer();var other=customer();long own=address(owner),foreign=address(other);
        assertThat(call(HttpMethod.POST,ROOT+"/checkout/preview",owner,Map.of("addressId",foreign),null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST,ROOT+"/checkout/preview",owner,Map.of("addressId",own),null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var product=product(3,"50.00");add(owner,product,1);String review=quote(owner,own);
        assertThat(place(owner,own,review,"not-a-uuid").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        product.unpublish();products.saveAndFlush(product);
        assertThat(place(owner,own,review,UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST,ROOT+"/orders",null,Map.of("addressId",own,"quoteToken",review),UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test void simultaneousSameKeyCreatesOneOrderAndConsumesStockOnce() throws Exception {
        var owner=customer();long address=address(owner);var p=product(5,"10.00");add(owner,p,2);
        String review=quote(owner,address),key=UUID.randomUUID().toString();
        List<ResponseEntity<String>> results=concurrent(() -> place(owner,address,review,key),() -> place(owner,address,review,key));
        assertThat(results.stream().map(r -> r.getStatusCode().value())).containsExactlyInAnyOrder(201,200);
        assertThat(json(results.get(0)).get("id")).isEqualTo(json(results.get(1)).get("id"));
        assertThat(products.findById(p.getId()).orElseThrow().getStockQuantity()).isEqualTo(3);
    }

    @Test void twoCustomersCompetingForLastUnitCannotOversell() throws Exception {
        var a=customer();var b=customer();long aa=address(a),bb=address(b);var p=product(1,"99.95");add(a,p,1);add(b,p,1);
        String qa=quote(a,aa),qb=quote(b,bb);
        var results=concurrent(() -> place(a,aa,qa,UUID.randomUUID().toString()),() -> place(b,bb,qb,UUID.randomUUID().toString()));
        assertThat(results.stream().map(r -> r.getStatusCode().value())).containsExactlyInAnyOrder(201,409);
        assertThat(products.findById(p.getId()).orElseThrow().getStockQuantity()).isZero();
    }

    @Test void failureAfterOrderFlushRollsBackOrderStockAndCart() throws Exception {
        var owner=customer();long address=address(owner);var p=product(5,"10.00");add(owner,p,2);
        String review=quote(owner,address);long count=orders.count();
        org.mockito.Mockito.doThrow(new IllegalStateException("Synthetic cart persistence failure"))
                .when(cartRepository).saveAndFlush(org.mockito.ArgumentMatchers.any(com.brandista.brandista.cart.Cart.class));
        try {
            assertThat(place(owner,address,review,UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        } finally { org.mockito.Mockito.reset(cartRepository); }
        assertThat(orders.count()).isEqualTo(count);
        assertThat(products.findById(p.getId()).orElseThrow().getStockQuantity()).isEqualTo(5);
        assertThat(json(call(HttpMethod.GET,ROOT+"/cart",owner,null,null)).get("items").get(0).get("quantity").asInt()).isEqualTo(2);
    }
    private List<ResponseEntity<String>> concurrent(Callable<ResponseEntity<String>> first,Callable<ResponseEntity<String>> second) throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            var a=executor.submit(() -> { ready.countDown();start.await();return first.call(); });
            var b=executor.submit(() -> { ready.countDown();start.await();return second.call(); });
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
        }
    }
}
