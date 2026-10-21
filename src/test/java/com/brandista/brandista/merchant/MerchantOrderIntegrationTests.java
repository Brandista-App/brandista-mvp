package com.brandista.brandista.merchant;

import com.brandista.brandista.brand.*;
import com.brandista.brandista.category.*;
import com.brandista.brandista.product.*;
import com.brandista.brandista.privilege.*;
import com.brandista.brandista.user.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:merchant-orders;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa","spring.datasource.password=","brandista.checkout.cod-enabled=true"})
class MerchantOrderIntegrationTests {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry properties) {
        String url=System.getenv("BRANDISTA_PR7A_TEST_MYSQL_URL");if(url==null)return;
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/brandista_pr7a_verify\\?.*"))throw new IllegalArgumentException("Only disposable PR7A MySQL allowed");
        properties.add("spring.datasource.url",()->url);properties.add("spring.datasource.username",()->"root");
        properties.add("spring.datasource.password",()->System.getenv("BRANDISTA_PR7A_TEST_MYSQL_PASSWORD"));
    }
    @Autowired TestRestTemplate http;
    @Autowired UserRepository users;
    @Autowired BrandRepository brands;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired PlatformGrantRepository grants;
    @Autowired BrandMembershipService memberships;
    @Autowired AccountLifecycleService lifecycle;
    @Autowired MerchantOrderService service;
    @Autowired BrandAccess access;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    String password=UUID.randomUUID()+"-Synthetic!";
    User operator,merchantA,merchantB,buyer,unassigned,legacy;
    String op,aToken,bToken,customerToken;
    Brand a,b,c;
    Product pa,pb;
    long orderId,foreignOrder;

    @BeforeEach void fixture() {
        operator=user(UserRole.CUSTOMER);merchantA=user(UserRole.CUSTOMER);merchantB=user(UserRole.CUSTOMER);
        buyer=user(UserRole.CUSTOMER);unassigned=user(UserRole.CUSTOMER);legacy=user(UserRole.ADMIN);
        grants.saveAndFlush(new PlatformGrant(operator,PlatformGrantType.SUPER_ADMIN));op=login(operator);
        a=brand();b=brand();c=brand();
        var category=categories.saveAndFlush(new Category("Synthetic category","c-"+UUID.randomUUID(),null));
        pa=product(a,category,"2750.50");pb=product(b,category,"999999.99");
        asOperator(()->memberships.grant(merchantA.getId(),a.getId()));asOperator(()->memberships.grant(merchantB.getId(),b.getId()));
        aToken=login(merchantA);bToken=login(merchantB);customerToken=login(buyer);
        orderId=place(customerToken,List.of(pa,pb));
        foreignOrder=place(customerToken,List.of(pb));
    }

    @Test void mixedOrderOnlyExposesOwnedSnapshotsAndBrandSubtotal() {
        var detail=get(a,orderId,aToken);assertStatus(detail,HttpStatus.OK);
        JsonNode json=detail.getBody();assertThat(json.path("items")).hasSize(1);
        assertThat(json.path("items").get(0).path("productId").asLong()).isEqualTo(pa.getId());
        assertThat(json.path("order").path("itemsSubtotal").decimalValue()).isEqualByComparingTo("2750.50");
        assertThat(json.path("order").path("availableTransitions").get(0).asText()).isEqualTo("ACCEPTED");
        assertThat(json.path("deliveryAddress").path("recipientName").asText()).isEqualTo("Synthetic recipient");
        assertThat(json.has("customerId")).isFalse();assertThat(json.has("paymentStatus")).isFalse();
        assertThat(json.path("order").has("total")).isFalse();assertThat(json.path("order").has("paymentStatus")).isFalse();
        assertThat(json.toString()).doesNotContain(pb.getSlug(),pb.getName(),"999999.99","1002750.49","requestHash","idempotencyKey");
        assertThat(get(b,orderId,bToken).getBody().path("order").path("itemsSubtotal").decimalValue()).isEqualByComparingTo("999999.99");
    }

    @Test void listsFilterTenantBeforeCountAndPaginationAndUseStableOrder() {
        var response=call(HttpMethod.GET,base(a)+"?page=0&size=1",aToken,null);assertStatus(response,HttpStatus.OK);
        assertThat(response.getBody().path("totalElements").asLong()).isEqualTo(1);
        assertThat(response.getBody().path("content").get(0).path("orderId").asLong()).isEqualTo(orderId);
        var bList=call(HttpMethod.GET,base(b)+"?size=1",bToken,null);
        assertThat(bList.getBody().path("totalElements").asLong()).isEqualTo(2);
        assertThat(bList.getBody().path("content").get(0).path("orderId").asLong()).isEqualTo(foreignOrder);
        assertThat(call(HttpMethod.GET,base(b)+"?page=1&size=1",bToken,null).getBody().path("content").get(0).path("orderId").asLong()).isEqualTo(orderId);
        assertThat(call(HttpMethod.GET,base(a)+"?page=1&size=1",aToken,null).getBody().path("content")).isEmpty();
        assertThat(response.getBody().path("content").get(0).has("deliveryAddress")).isFalse();
    }

    @Test void foreignAndMissingIdsHaveTheSame404AndNoActionsCrossBrands() {
        assertStatus(get(b,orderId,aToken),HttpStatus.FORBIDDEN);
        assertStatus(get(a,foreignOrder,aToken),HttpStatus.NOT_FOUND);
        assertStatus(get(a,Long.MAX_VALUE,aToken),HttpStatus.NOT_FOUND);
        assertStatus(transition(b,orderId,aToken,"ACCEPTED",0),HttpStatus.FORBIDDEN);
        assertStatus(transition(a,foreignOrder,aToken,"ACCEPTED",0),HttpStatus.NOT_FOUND);
        assertThat(status(b,orderId)).isEqualTo("PLACED");
    }

    @Test void anonymousCustomersLegacyAndUnassignedPlatformAccountsCannotManageOrders() {
        for(String token:List.of(customerToken,login(unassigned),login(legacy),op)) {
            assertStatus(get(a,orderId,token),HttpStatus.FORBIDDEN);
            assertStatus(transition(a,orderId,token,"ACCEPTED",0),HttpStatus.FORBIDDEN);
        }
        assertStatus(get(a,orderId,null),HttpStatus.UNAUTHORIZED);
        assertStatus(transition(a,orderId,null,"ACCEPTED",0),HttpStatus.UNAUTHORIZED);
    }

    @Test void supportedSequenceOnlyChangesTheSelectedBrandAndAuditsEachStep() {
        var before=financialSnapshot();
        long version=0;
        for(String next:List.of("ACCEPTED","PACKED","SHIPPED","DELIVERED")) {
            var response=transition(a,orderId,aToken,next,version++);assertStatus(response,HttpStatus.OK);
            assertThat(response.getBody().path("order").path("version").asLong()).isEqualTo(version);
            assertThat(response.getBody().path("order").path("fulfillmentStatus").asText()).isEqualTo(next);
            assertThat(status(b,orderId)).isEqualTo("PLACED");
        }
        assertThat(get(a,orderId,aToken).getBody().path("order").path("availableTransitions")).isEmpty();
        assertThat(get(a,orderId,aToken).getBody().path("timeline")).hasSize(4);
        assertThat(get(b,orderId,bToken).getBody().path("timeline")).isEmpty();
        assertThat(jdbc.queryForList("SELECT actor_user_id FROM merchant_order_events WHERE order_id=? AND brand_id=?",orderId,a.getId()))
                .allSatisfy(row->assertThat(((Number)row.get("actor_user_id")).longValue()).isEqualTo(merchantA.getId()));
        assertThat(financialSnapshot()).isEqualTo(before);
    }

    @Test void rejectsSkipsRepeatsBackwardsStaleVersionsAndTerminalChanges() {
        assertStatus(transition(a,orderId,aToken,"SHIPPED",0),HttpStatus.CONFLICT);
        assertStatus(transition(a,orderId,aToken,"PLACED",0),HttpStatus.CONFLICT);
        assertStatus(transition(a,orderId,aToken,"ACCEPTED",1),HttpStatus.CONFLICT);
        assertStatus(transition(a,orderId,aToken,"ACCEPTED",0),HttpStatus.OK);
        assertStatus(transition(a,orderId,aToken,"ACCEPTED",0),HttpStatus.CONFLICT);
        assertStatus(transition(a,orderId,aToken,"PACKED",0),HttpStatus.CONFLICT);
        assertStatus(transition(a,orderId,aToken,"PLACED",1),HttpStatus.CONFLICT);
        assertStatus(transition(a,orderId,aToken,"PACKED",1),HttpStatus.OK);
        assertStatus(transition(a,orderId,aToken,"SHIPPED",2),HttpStatus.OK);
        assertStatus(transition(a,orderId,aToken,"DELIVERED",3),HttpStatus.OK);
        assertStatus(transition(a,orderId,aToken,"DELIVERED",4),HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_events WHERE order_id=? AND brand_id=?",Long.class,orderId,a.getId())).isEqualTo(4);
    }

    @Test void statusFiltersAndInputValidationAreBackendEnforced() {
        transition(a,orderId,aToken,"ACCEPTED",0);
        assertThat(call(HttpMethod.GET,base(a)+"?status=ACCEPTED",aToken,null).getBody().path("totalElements").asLong()).isEqualTo(1);
        assertThat(call(HttpMethod.GET,base(a)+"?status=PLACED",aToken,null).getBody().path("totalElements").asLong()).isZero();
        for(String query:List.of("?size=0","?size=101","?page=-1","?status=PAID"))assertStatus(call(HttpMethod.GET,base(a)+query,aToken,null),HttpStatus.BAD_REQUEST);
        for(Object body:List.of(Map.of("status","CANCELLED","expectedVersion",0),Map.of("status","ACCEPTED","expectedVersion",-1),Map.of("status","ACCEPTED"),Map.of("expectedVersion",0)))
            assertStatus(call(HttpMethod.POST,base(a)+"/"+orderId+"/fulfillment",aToken,body),HttpStatus.BAD_REQUEST);
    }

    @Test void revokedMembershipDeniesSameJwtReadsAndWrites() {
        assertStatus(get(a,orderId,aToken),HttpStatus.OK);
        asOperator(()->memberships.revoke(merchantA.getId(),a.getId()));
        assertStatus(get(a,orderId,aToken),HttpStatus.FORBIDDEN);
        assertStatus(call(HttpMethod.GET,base(a),aToken,null),HttpStatus.FORBIDDEN);
        assertStatus(transition(a,orderId,aToken,"ACCEPTED",0),HttpStatus.FORBIDDEN);
        assertStatus(get(b,orderId,bToken),HttpStatus.OK);
    }

    @Test void disabledAccountRejectsOldJwtAndRestoreDoesNotRestoreRevokedMembership() {
        asOperator(()->memberships.revoke(merchantA.getId(),a.getId()));
        asOperator(()->lifecycle.disable(merchantA.getId(),merchantA.getEmail()));
        assertStatus(get(a,orderId,aToken),HttpStatus.UNAUTHORIZED);
        assertStatus(transition(a,orderId,aToken,"ACCEPTED",0),HttpStatus.UNAUTHORIZED);
        asOperator(()->lifecycle.restore(merchantA.getId(),merchantA.getEmail()));
        assertStatus(get(a,orderId,aToken),HttpStatus.FORBIDDEN);
    }

    @Test void inactiveBrandDeniesReadsAndWritesWithoutChangingOrder() {
        a.deactivate();brands.saveAndFlush(a);
        assertStatus(get(a,orderId,aToken),HttpStatus.FORBIDDEN);
        assertStatus(call(HttpMethod.GET,base(a),aToken,null),HttpStatus.FORBIDDEN);
        assertStatus(transition(a,orderId,aToken,"ACCEPTED",0),HttpStatus.FORBIDDEN);
        assertThat(status(a,orderId)).isEqualTo("PLACED");
    }

    @Test void historicalBrandOwnershipSurvivesProductReassignment() {
        pa.updateDetails(pa.getName(),pa.getSlug(),null,b,pa.getCategory(),new BigDecimal("1.00"),ProductAvailability.AVAILABLE);products.saveAndFlush(pa);
        var aDetail=get(a,orderId,aToken);assertStatus(aDetail,HttpStatus.OK);
        assertThat(aDetail.getBody().path("items").get(0).path("unitPrice").decimalValue()).isEqualByComparingTo("2750.50");
        assertThat(get(b,orderId,bToken).getBody().path("items")).hasSize(1);
    }

    @Test void concurrentSameVersionMakesOneTransitionAndOneAuditEvent() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<HttpStatusCode> action=()->{start.await();return transition(a,orderId,aToken,"ACCEPTED",0).getStatusCode();};
            var first=pool.submit(action);var second=pool.submit(action);start.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.CONFLICT);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_events WHERE order_id=? AND brand_id=?",Long.class,orderId,a.getId())).isEqualTo(1);
    }

    @Test void differentBrandsCanProcessTheSameMixedOrderIndependently() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->transition(a,orderId,aToken,"ACCEPTED",0));var second=pool.submit(()->transition(b,orderId,bToken,"ACCEPTED",0));
            assertStatus(first.get(30,TimeUnit.SECONDS),HttpStatus.OK);assertStatus(second.get(30,TimeUnit.SECONDS),HttpStatus.OK);
        }
        assertThat(get(a,orderId,aToken).getBody().path("timeline")).hasSize(1);
        assertThat(get(b,orderId,bToken).getBody().path("timeline")).hasSize(1);
    }

    @Test void customerOrderContractAndFinancialSnapshotRemainUnchangedAfterFulfillment() {
        JsonNode before=call(HttpMethod.GET,"/api/v1/customers/me/orders/"+orderId,customerToken,null).getBody();
        transition(a,orderId,aToken,"ACCEPTED",0);
        var after=call(HttpMethod.GET,"/api/v1/customers/me/orders/"+orderId,customerToken,null);assertStatus(after,HttpStatus.OK);
        assertThat(after.getBody()).isEqualTo(before);assertThat(after.getBody().path("items")).hasSize(2);
        assertThat(after.getBody().path("status").asText()).isEqualTo("PLACED");assertThat(after.getBody().path("paymentStatus").asText()).isEqualTo("UNPAID");
        assertStatus(call(HttpMethod.GET,"/api/v1/customers/me/orders/"+orderId,login(unassigned),null),HttpStatus.NOT_FOUND);
        assertStatus(call(HttpMethod.GET,"/api/v1/customers/me/cart",customerToken,null),HttpStatus.OK);
    }

    @Test void checkoutReplayDoesNotDuplicateFulfillmentsAndStockIsConsumedOnce() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_fulfillments WHERE order_id=?",Long.class,orderId)).isEqualTo(2);
        assertThat(products.findById(pa.getId()).orElseThrow().getStockQuantity()).isEqualTo(19);
        assertThat(products.findById(pb.getId()).orElseThrow().getStockQuantity()).isEqualTo(18);
    }

    @Test void operationalAuditFailureRollsBackFulfillmentTransition() {
        JdbcTemplate failing=spy(jdbc);
        doThrow(new IllegalStateException("synthetic audit outage")).when(failing).update(startsWith("INSERT INTO merchant_order_events"),any(Object[].class));
        var isolated=new MerchantOrderService(failing,access);
        try {
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(aToken),List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
            assertThatThrownBy(()->new TransactionTemplate(transactions).execute(s->isolated.transition(a.getId(),orderId,new MerchantOrderModels.Transition(MerchantOrderModels.Status.ACCEPTED,0L))))
                    .isInstanceOf(IllegalStateException.class);
        } finally {SecurityContextHolder.clearContext();}
        assertThat(status(a,orderId)).isEqualTo("PLACED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_events WHERE order_id=?",Long.class,orderId)).isZero();
    }

    @Test void multiAssignedMerchantStillMustUseExplicitBrandAndCannotProcessOtherSlice() {
        asOperator(()->memberships.grant(merchantA.getId(),b.getId()));
        assertStatus(transition(a,orderId,aToken,"ACCEPTED",0),HttpStatus.OK);
        assertThat(status(b,orderId)).isEqualTo("PLACED");
        assertThat(get(b,orderId,aToken).getBody().path("items")).hasSize(1);
    }

    @Test void assignedBrandWithoutOrdersReturnsAnEmptyPage() {
        asOperator(()->memberships.grant(merchantA.getId(),c.getId()));
        var response=call(HttpMethod.GET,base(c),aToken,null);assertStatus(response,HttpStatus.OK);
        assertThat(response.getBody().path("content")).isEmpty();
        assertThat(response.getBody().path("totalElements").asLong()).isZero();
        assertThat(response.getBody().path("totalPages").asInt()).isZero();
    }

    @Test void extraClientFieldsCannotChangeActorTenantFinancesOrPayment() {
        var before=financialSnapshot();
        var body=Map.of("status","ACCEPTED","expectedVersion",0,"brandId",b.getId(),"userId",operator.getId(),
                "total",0,"paymentStatus","PAID","stockQuantity",999);
        assertStatus(call(HttpMethod.POST,base(a)+"/"+orderId+"/fulfillment",aToken,body),HttpStatus.OK);
        assertThat(status(b,orderId)).isEqualTo("PLACED");
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM merchant_order_events WHERE order_id=? AND brand_id=?",Long.class,orderId,a.getId()))
                .isEqualTo(merchantA.getId());
    }

    private Map<String,Object> financialSnapshot() {
        return Map.of("orders",jdbc.queryForList("SELECT * FROM customer_orders WHERE id=?",orderId),
                "items",jdbc.queryForList("SELECT * FROM customer_order_items WHERE order_id=? ORDER BY id",orderId),
                "stock",jdbc.queryForList("SELECT id,stock_quantity FROM products WHERE id IN (?,?) ORDER BY id",pa.getId(),pb.getId()));
    }
    private String status(Brand brand,long id){return jdbc.queryForObject("SELECT status FROM merchant_order_fulfillments WHERE order_id=? AND brand_id=?",String.class,id,brand.getId());}
    private User user(UserRole role){return users.saveAndFlush(new User(UUID.randomUUID()+"@example.invalid",passwords.encode(password),role,AccountStatus.ENABLED));}
    private Brand brand(){return brands.saveAndFlush(new Brand("Synthetic "+UUID.randomUUID(),"brand-"+UUID.randomUUID(),null,null));}
    private Product product(Brand brand,Category category,String price){var p=new Product("Synthetic "+UUID.randomUUID(),"p-"+UUID.randomUUID(),null,brand,category,new BigDecimal(price),ProductAvailability.AVAILABLE);p.setStockQuantity(20);p.publish();return products.saveAndFlush(p);}
    private String login(User user){var r=call(HttpMethod.POST,"/api/v1/auth/login",null,Map.of("email",user.getEmail(),"password",password));assertStatus(r,HttpStatus.OK);return r.getBody().path("accessToken").asText();}
    private long place(String token,List<Product> lines){
        var address=call(HttpMethod.POST,"/api/v1/customers/me/addresses",token,Map.of("recipientName","Synthetic recipient","phoneNumber","+92 300 1234567","addressLine1","Synthetic street","city","Synthetic city"));assertStatus(address,HttpStatus.CREATED);
        long addressId=address.getBody().path("id").asLong();
        for(Product p:lines){var added=call(HttpMethod.POST,"/api/v1/customers/me/cart/items",token,Map.of("productId",p.getId(),"quantity",1));assertThat(added.getStatusCode().is2xxSuccessful()).isTrue();}
        var preview=call(HttpMethod.POST,"/api/v1/customers/me/checkout/preview",token,Map.of("addressId",addressId));assertStatus(preview,HttpStatus.OK);
        var headers=new HttpHeaders();headers.setBearerAuth(token);headers.set("Idempotency-Key",UUID.randomUUID().toString());
        var body=Map.of("addressId",addressId,"quoteToken",preview.getBody().path("quoteToken").asText());
        var placed=http.exchange("/api/v1/customers/me/orders",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);assertStatus(placed,HttpStatus.CREATED);
        var replay=http.exchange("/api/v1/customers/me/orders",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);assertStatus(replay,HttpStatus.OK);
        assertThat(replay.getBody().path("id")).isEqualTo(placed.getBody().path("id"));
        assertThat(replay.getBody().path("total").decimalValue()).isEqualByComparingTo(placed.getBody().path("total").decimalValue());
        return placed.getBody().path("id").asLong();
    }
    private String base(Brand brand){return "/api/v1/merchant/brands/"+brand.getId()+"/orders";}
    private ResponseEntity<JsonNode> get(Brand brand,long id,String token){return call(HttpMethod.GET,base(brand)+"/"+id,token,null);}
    private ResponseEntity<JsonNode> transition(Brand brand,long id,String token,String status,long version){return call(HttpMethod.POST,base(brand)+"/"+id+"/fulfillment",token,Map.of("status",status,"expectedVersion",version));}
    private ResponseEntity<JsonNode> call(HttpMethod method,String path,String token,Object body){var headers=new HttpHeaders();if(token!=null)headers.setBearerAuth(token);return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);}
    private void assertStatus(ResponseEntity<?> response,HttpStatus status){assertThat(response.getStatusCode()).isEqualTo(status);}
    private <T>T asOperator(Supplier<T> action){try{SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(op),List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));return action.get();}finally{SecurityContextHolder.clearContext();}}
}
