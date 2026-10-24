package com.brandista.brandista.onboarding;

import com.brandista.brandista.user.*;
import com.brandista.brandista.privilege.*;
import com.brandista.brandista.merchant.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.brandista.brandista.privilege.SecurityAudit.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:invitations-test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa","spring.datasource.password=","brandista.checkout.cod-enabled=true"})
@Import(BrandInvitationIntegrationTests.DeliveryFixture.class)
class BrandInvitationIntegrationTests {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry properties) {
        String url=System.getenv("BRANDISTA_PR4_TEST_MYSQL_URL");
        if(url==null) return;
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/brandista_pr4_verify\\?.*"))
            throw new IllegalArgumentException("Only the disposable local PR4 schema is allowed");
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.username",()->"root");
        properties.add("spring.datasource.password",()->System.getenv("BRANDISTA_PR4_TEST_MYSQL_PASSWORD"));
    }
    @TestConfiguration static class DeliveryFixture {
        @Bean @Primary CaptureDelivery captureDelivery() { return new CaptureDelivery(); }
    }
    static class CaptureDelivery implements InvitationDelivery {
        record Message(String email,String token) { @Override public String toString() { return "[REDACTED]"; } }
        final Map<Long,Message> messages=new ConcurrentHashMap<>();
        volatile boolean unavailable;
        public void deliver(long id,String email,String token) {
            if(unavailable) throw new IllegalStateException("Synthetic unavailable delivery");
            messages.put(id,new Message(email,token));
        }
    }
    @Autowired TestRestTemplate http;
    @Autowired UserRepository users;
    @Autowired PlatformGrantRepository grants;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtDecoder decoder;
    @Autowired BrandInvitationService invitations;
    @Autowired BrandMembershipService memberships;
    @Autowired BrandAccess access;
    @Autowired OnboardingPolicy policy;
    @Autowired SecurityInvariant invariant;
    @Autowired InvitationRateLimiter limiter;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CaptureDelivery delivery;
    @Autowired com.brandista.brandista.brand.BrandRepository brands;
    @Autowired com.brandista.brandista.category.CategoryRepository categories;
    @Autowired com.brandista.brandista.product.ProductRepository products;
    User platform,recipient,other,legacy;
    PlatformGrant platformGrant;
    String platformToken,recipientToken,password=UUID.randomUUID()+"-Synthetic!";
    long application,brand;

    @BeforeEach void fixture() {
        delivery.unavailable=false;
        platform=user(UserRole.CUSTOMER); recipient=user(UserRole.CUSTOMER);
        other=user(UserRole.CUSTOMER); legacy=user(UserRole.ADMIN);
        platformGrant=grants.saveAndFlush(new PlatformGrant(platform,PlatformGrantType.SUPER_ADMIN));
        platformToken=login(platform); recipientToken=login(recipient);
        application=submit(recipient.getEmail());
        var reviewed=review(application,"APPROVED",platformToken);
        assertThat(reviewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        brand=reviewed.getBody().path("brandId").asLong();
    }

    @Test void completeSyntheticMarketplaceJourneyKeepsTwoBrandsIsolatedThroughFulfillment() {
        // Existing test-only platform fixture; no operator/runtime account is provisioned.
        assertThat(accept(raw(issue(application)),recipientToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(activate().getStatusCode()).isEqualTo(HttpStatus.OK);
        String brandBToken=login(other);
        long appB=submit(other.getEmail());
        var approvedB=review(appB,"APPROVED",platformToken);
        assertThat(approvedB.getStatusCode()).isEqualTo(HttpStatus.OK);
        long brandB=approvedB.getBody().path("brandId").asLong();
        assertThat(accept(raw(issue(appB)),brandBToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST,base(appB)+"/activate",platformToken,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,"/api/v1/merchant/brands",recipientToken,null).getBody()).hasSize(1);
        assertThat(call(HttpMethod.GET,"/api/v1/merchant/brands",brandBToken,null).getBody()).hasSize(1);

        var category=categories.saveAndFlush(new com.brandista.brandista.category.Category("Presentation category","pitch-"+UUID.randomUUID(),null));
        long[] stores={brand,brandB}; String[] tokens={recipientToken,brandBToken}; long[] productIds=new long[2];
        for(int index=0;index<2;index++) {
            String slug="presentation-"+UUID.randomUUID();
            var body=new HashMap<String,Object>(Map.of("name","Fictional presentation piece "+index,"slug",slug,
                    "brandId",stores[index],"categoryId",category.getId(),"price",index==0?150:200,"availability","AVAILABLE"));
            var created=call(HttpMethod.POST,merchant(stores[index]),tokens[index],body);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            productIds[index]=created.getBody().path("id").asLong();
            String product=merchant(stores[index])+"/"+productIds[index];
            body.put("description","Synthetic demonstration only");
            assertThat(call(HttpMethod.PUT,product,tokens[index],body).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(call(HttpMethod.PUT,product+"/stock",tokens[index],Map.of("stockQuantity",20)).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(call(HttpMethod.PATCH,product+"/publish",tokens[index],null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(call(HttpMethod.GET,"/api/v1/products/"+slug,null,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(call(HttpMethod.GET,merchant(brandB),recipientToken,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,merchant(brand),brandBToken,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String buyerEmail=UUID.randomUUID()+"@example.invalid";
        assertThat(call(HttpMethod.POST,"/api/v1/registrations",null,Map.of("email",buyerEmail,"password",password)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String buyerToken=login(users.findByEmail(buyerEmail).orElseThrow());
        String customer="/api/v1/customers/me";
        var address=call(HttpMethod.POST,customer+"/addresses",buyerToken,Map.of("recipientName","Synthetic presentation customer",
                "phoneNumber","+92 300 1234567","addressLine1","Synthetic residence","city","Lahore","province","Punjab"));
        assertThat(address.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long addressId=address.getBody().path("id").asLong();
        for(int index=0;index<2;index++) assertThat(call(HttpMethod.POST,customer+"/cart/items",buyerToken,
                Map.of("productId",productIds[index],"quantity",index==0?2:1)).getStatusCode().is2xxSuccessful()).isTrue();
        var quote=call(HttpMethod.POST,customer+"/checkout/preview",buyerToken,Map.of("addressId",addressId));
        assertThat(quote.getStatusCode()).isEqualTo(HttpStatus.OK);
        var headers=new HttpHeaders();headers.setBearerAuth(buyerToken);headers.set("Idempotency-Key",UUID.randomUUID().toString());
        var placed=http.exchange(customer+"/orders",HttpMethod.POST,new HttpEntity<>(Map.of("addressId",addressId,
                "quoteToken",quote.getBody().path("quoteToken").asText()),headers),JsonNode.class);
        assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long order=placed.getBody().path("id").asLong();
        assertThat(placed.getBody().path("total").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(placed.getBody().path("paymentStatus").asText()).isEqualTo("UNPAID");
        assertThat(placed.getBody().path("paymentMethod").asText()).isEqualTo("COD");
        String orderA="/api/v1/merchant/brands/"+brand+"/orders/"+order;
        String orderB="/api/v1/merchant/brands/"+brandB+"/orders/"+order;
        assertThat(call(HttpMethod.GET,orderA,recipientToken,null).getBody().path("items")).hasSize(1);
        assertThat(call(HttpMethod.GET,orderB,brandBToken,null).getBody().path("items")).hasSize(1);
        assertThat(call(HttpMethod.GET,orderB,recipientToken,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,orderA,buyerToken,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        long version=0;
        for(String state:List.of("ACCEPTED","PACKED","SHIPPED","DELIVERED")) {
            var result=call(HttpMethod.POST,orderA+"/fulfillment",recipientToken,Map.of("status",state,"expectedVersion",version++));
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().path("order").path("fulfillmentStatus").asText()).isEqualTo(state);
        }
        assertThat(call(HttpMethod.GET,orderB,brandBToken,null).getBody().path("order").path("fulfillmentStatus").asText()).isEqualTo("PLACED");
        assertThat(call(HttpMethod.GET,customer+"/orders/"+order,buyerToken,null).getBody().path("paymentStatus").asText()).isEqualTo("UNPAID");
        assertThat(products.findById(productIds[0]).orElseThrow().getStockQuantity()).isEqualTo(18);
        assertThat(products.findById(productIds[1]).orElseThrow().getStockQuantity()).isEqualTo(19);
    }

    @Test void validExistingCustomerAcceptanceIsTraceableAndRequiresExplicitActivation() {
        long id=issue(application); String token=raw(id);
        assertThat(jdbc.queryForObject("SELECT status FROM brands WHERE id=?",String.class,brand)).isEqualTo("INACTIVE");
        assertThat(activate().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(accept(token,recipientToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,merchant(brand),recipientToken,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(state().path("activationEligible").asBoolean()).isTrue();
        assertThat(activate().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,merchant(brand),recipientToken,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(users.findById(recipient.getId()).orElseThrow().getRole()).isEqualTo(UserRole.CUSTOMER);
        for(String path:List.of("cart","addresses","orders"))
            assertThat(call(HttpMethod.GET,"/api/v1/customers/me/"+path,recipientToken,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        var row=jdbc.queryForMap("SELECT * FROM brand_admin_invitations WHERE id=?",id);
        assertThat(((Number)row.get("accepted_user_id")).longValue()).isEqualTo(recipient.getId());
        assertThat(row.get("membership_id")).isNotNull();
        assertThat(((Number)row.get("application_id")).longValue()).isEqualTo(application);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_platform_grants WHERE user_id=?",Long.class,recipient.getId())).isZero();
        assertThat(call(HttpMethod.GET,"/api/v1/super-admin/identity",recipientToken,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void acceptingInvitationPreservesExistingAddressCartAndCodCheckout() {
        String customer="/api/v1/customers/me";
        var address=call(HttpMethod.POST,customer+"/addresses",recipientToken,Map.of(
                "recipientName","Synthetic recipient","phoneNumber","+92 300 1234567",
                "addressLine1","Synthetic street","city","Lahore","province","Punjab"));
        assertThat(address.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long addressId=address.getBody().path("id").asLong();
        var store=brands.saveAndFlush(new com.brandista.brandista.brand.Brand("Synthetic store","store-"+UUID.randomUUID(),null,null));
        var category=categories.saveAndFlush(new com.brandista.brandista.category.Category("Synthetic category","cat-"+UUID.randomUUID(),null));
        var product=new com.brandista.brandista.product.Product("Synthetic item","item-"+UUID.randomUUID(),null,store,category,
                new java.math.BigDecimal("150.00"),com.brandista.brandista.product.ProductAvailability.AVAILABLE);
        product.setStockQuantity(5); product.publish(); products.saveAndFlush(product);
        assertThat(call(HttpMethod.POST,customer+"/cart/items",recipientToken,Map.of("productId",product.getId(),"quantity",2)).getStatusCode().is2xxSuccessful()).isTrue();
        accept(raw(issue(application)),recipientToken); activate();
        assertThat(call(HttpMethod.GET,customer+"/cart",recipientToken,null).getBody().path("items")).hasSize(1);
        assertThat(call(HttpMethod.GET,customer+"/addresses/"+addressId,recipientToken,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        var preview=call(HttpMethod.POST,customer+"/checkout/preview",recipientToken,Map.of("addressId",addressId));
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        var headers=new HttpHeaders(); headers.setBearerAuth(recipientToken); headers.set("Idempotency-Key",UUID.randomUUID().toString());
        var order=http.exchange(customer+"/orders",HttpMethod.POST,new HttpEntity<>(Map.of("addressId",addressId,"quoteToken",preview.getBody().path("quoteToken").asText()),headers),JsonNode.class);
        assertThat(order.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(order.getBody().path("paymentMethod").asText()).isEqualTo("COD");
        assertThat(order.getBody().path("total").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(call(HttpMethod.GET,customer+"/orders/"+order.getBody().path("id").asLong(),recipientToken,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(products.findById(product.getId()).orElseThrow().getStockQuantity()).isEqualTo(3);
    }

    @Test void wrongEmailUnknownExpiredRevokedAndReplayedTokensHaveUniformRejection() {
        long id=issue(application); String token=raw(id);
        var wrong=accept(token,login(other));
        var unknown=accept("x".repeat(43),recipientToken);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(wrong.getBody().path("message")).isEqualTo(unknown.getBody().path("message"));
        jdbc.update("UPDATE brand_admin_invitations SET expires_at=? WHERE id=?",LocalDateTime.now(Clock.systemUTC()).minusMinutes(1),id);
        assertThat(accept(token,recipientToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        long next=issue(application); String revoked=raw(next);
        assertThat(revoke(next).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accept(revoked,recipientToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        long finalId=issue(application); String valid=raw(finalId);
        assertThat(accept(valid,recipientToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accept(valid,recipientToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countMemberships()).isEqualTo(1);
    }

    @Test void reissueRotatesPendingOrExpiredTokensAndNeverReturnsSecrets() {
        long first=issue(application); String old=raw(first);
        assertThat(create(application).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var rotated=reissue(first);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long second=rotated.getBody().path("id").asLong();
        assertThat(rotated.getBody().path("replacesInvitationId").asLong()).isEqualTo(first);
        assertThat(raw(second)).isNotEqualTo(old);
        assertThat(accept(old,recipientToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reissue(first).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        jdbc.update("UPDATE brand_admin_invitations SET expires_at=? WHERE id=?",LocalDateTime.now(Clock.systemUTC()).minusSeconds(1),second);
        long third=reissue(second).getBody().path("id").asLong();
        var listing=call(HttpMethod.GET,base(application)+"/invitations",platformToken,null);
        assertThat(listing.getBody().path("totalElements").asInt()).isEqualTo(3);
        assertThat(listing.getBody().toString()).doesNotContain(old,raw(second),raw(third),"token_hash","tokenHash");
        assertThat(accept(raw(third),recipientToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reissue(third).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(revoke(third).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void simultaneousAcceptanceCreatesExactlyOneMembershipAndSuccessfulAudit() throws Exception {
        long id=issue(application); String token=raw(id);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<HttpStatusCode> attempt=()->{start.await();return accept(token,recipientToken).getStatusCode();};
            var first=pool.submit(attempt); var second=pool.submit(attempt); start.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.BAD_REQUEST);
        }
        assertThat(countMemberships()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_events WHERE target_invitation_id=? AND operation='INVITATION_ACCEPTED'",Long.class,id)).isEqualTo(1);
    }

    @Test void simultaneousIssueAndReviewDoNotDuplicateInvitationsOrBrands() throws Exception {
        var start=new CountDownLatch(1);
        long nextApplication=submit(UUID.randomUUID()+"@example.invalid");
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<HttpStatusCode> attempt=()->{start.await();return review(nextApplication,"APPROVED",platformToken).getStatusCode();};
            var first=pool.submit(attempt); var second=pool.submit(attempt); start.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.CONFLICT);
            var issueStart=new CountDownLatch(1);
            Callable<HttpStatusCode> issueAttempt=()->{issueStart.await();return create(application).getStatusCode();};
            var a=pool.submit(issueAttempt); var b=pool.submit(issueAttempt); issueStart.countDown();
            assertThat(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(HttpStatus.CREATED,HttpStatus.CONFLICT);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brands WHERE name=(SELECT brand_name FROM brand_applications WHERE id=?)",Long.class,nextApplication)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_invitations WHERE application_id=?",Long.class,application)).isEqualTo(1);
        assertThat(review(application,"APPROVED",platformToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void legacyAdminsCustomersAndAnonymousCannotInviteApproveOrActivate() {
        for(String token:List.of(login(legacy),recipientToken)) {
            assertThat(call(HttpMethod.POST,base(application)+"/invitations",token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(HttpMethod.GET,base(application)+"/onboarding",token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(HttpMethod.POST,base(application)+"/activate",token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            long pending=submit(UUID.randomUUID()+"@example.invalid");
            assertThat(review(pending,"APPROVED",token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(call(HttpMethod.POST,base(application)+"/invitations",null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        long pending=submit(UUID.randomUUID()+"@example.invalid");
        assertThat(review(pending,"NEEDS_INFORMATION",login(legacy)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(review(pending,"REJECTED",login(legacy)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(create(pending).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(create(submit(UUID.randomUUID()+"@example.invalid")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void staleSuperAdminContextIsRejectedAgainstCurrentDatabaseGrant() {
        Jwt old=decoder.decode(platformToken);
        grants.saveAndFlush(new PlatformGrant(user(UserRole.CUSTOMER),PlatformGrantType.SUPER_ADMIN));
        platformGrant.revoke(); grants.saveAndFlush(platformGrant);
        assertThat(create(application).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        withPlatform(old,()->{
            assertThatThrownBy(()->invitations.create(application)).isInstanceOf(AccessDeniedException.class);
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_invitations WHERE application_id=?",Long.class,application)).isZero();
    }

    @Test void existingGlobalBrandUpdateCannotBypassOnboardingPolicy() {
        var body=Map.of("name","Synthetic approved","slug","approved-"+UUID.randomUUID(),"status","ACTIVE");
        String path="/api/v1/admin/brands/"+brand;
        assertThat(call(HttpMethod.PUT,path,login(legacy),body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT,path,platformToken,body).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(accept(raw(issue(application)),recipientToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,path,platformToken,body).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void customerCannotOverrideRecipientOrTenantAndHasNoBrandBAccess() {
        long another=submit(other.getEmail());
        long brandB=review(another,"APPROVED",platformToken).getBody().path("brandId").asLong();
        var response=call(HttpMethod.POST,base(application)+"/invitations",platformToken,
                Map.of("recipientEmail",other.getEmail(),"brandId",brandB,"userId",other.getId()));
        long id=response.getBody().path("id").asLong();
        assertThat(delivery.messages.get(id).email()).isEqualTo(recipient.getEmail());
        assertThat(call(HttpMethod.POST,"/api/v1/brand-invitations/accept",recipientToken,
                Map.of("token",raw(id),"brandId",brandB,"userId",other.getId(),"role","SUPER_ADMIN")).getStatusCode()).isEqualTo(HttpStatus.OK);
        activate();
        assertThat(call(HttpMethod.GET,merchant(brand),recipientToken,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,merchant(brandB),recipientToken,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships WHERE user_id=? AND brand_id=?",Long.class,recipient.getId(),brandB)).isZero();
    }

    @Test void revokedMembershipInvalidatesOldJwtPendingTokensAndActivationEvidence() {
        long accepted=issue(application); String used=raw(accepted);
        accept(used,recipientToken); activate();
        long pending=issue(application); String stale=raw(pending);
        withPlatform(decoder.decode(platformToken),()->memberships.revoke(recipient.getId(),brand));
        assertThat(call(HttpMethod.GET,merchant(brand),recipientToken,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(accept(used,recipientToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(accept(stale,recipientToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(state().path("activationEligible").asBoolean()).isFalse();
        assertThat(activate().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // An explicit new invitation after revocation is the only invitation-based regrant.
        long fresh=reissue(pending).getBody().path("id").asLong();
        assertThat(accept(raw(fresh),recipientToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(state().path("activationEligible").asBoolean()).isTrue();
        assertThat(countMemberships()).isEqualTo(1);
    }

    @Test void disabledAccountsCannotAcceptOrQualifyBrandForActivation() {
        long id=issue(application); String token=raw(id);
        recipient.changeStatus(AccountStatus.DISABLED); users.saveAndFlush(recipient);
        assertThat(accept(token,recipientToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(countMemberships()).isZero();
        recipient.changeStatus(AccountStatus.ENABLED); users.saveAndFlush(recipient);
        assertThat(accept(token,recipientToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        recipient.changeStatus(AccountStatus.DISABLED); users.saveAndFlush(recipient);
        assertThat(activate().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void newAccountUsesUnchangedCustomerRegistrationAndLoginThenEmailBoundAcceptance() {
        String email=UUID.randomUUID()+"@example.invalid";
        long app=submit(email); review(app,"APPROVED",platformToken);
        long id=issue(app); String token=raw(id);
        assertThat(accept(token,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(users.findByEmail(email)).isEmpty();
        assertThat(http.postForEntity("/api/v1/registrations",Map.of("email",email,"password","weak"),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/v1/registrations",Map.of("email",email.toUpperCase(),"password",password,"role","SUPER_ADMIN"),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        User account=users.findByEmail(email).orElseThrow();
        assertThat(account.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(accept(token,login(account)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(users.findById(account.getId()).orElseThrow().getPasswordHash()).isNotEqualTo(password);
        assertThat(http.postForEntity("/api/v1/registrations",Map.of("email",email,"password",password),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void persistentRateLimitCountsRejectedAttemptsAndRecoversAfterWindow() {
        long id=issue(application);
        for(int i=0;i<10;i++) assertThat(accept("x".repeat(43),recipientToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(accept(raw(id),recipientToken).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM brand_invitation_accept_limits WHERE user_id=?",Integer.class,recipient.getId())).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_events WHERE target_user_id=? AND reason='RATE_LIMITED'",Long.class,recipient.getId())).isEqualTo(1);
        jdbc.update("UPDATE brand_invitation_accept_limits SET window_started_at=? WHERE user_id=?",LocalDateTime.now(Clock.systemUTC()).minusMinutes(2),recipient.getId());
        assertThat(accept(raw(id),recipientToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void deliveryFailureRollsBackIssueAndReissueWithoutDestroyingPreviousToken() {
        delivery.unavailable=true;
        try { assertThat(create(application).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE); }
        finally { delivery.unavailable=false; }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_invitations WHERE application_id=?",Long.class,application)).isZero();
        long id=issue(application); String token=raw(id);
        delivery.unavailable=true;
        try { assertThat(reissue(id).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE); }
        finally { delivery.unavailable=false; }
        assertThat(accept(token,recipientToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_events WHERE target_application_id=? AND reason='DELIVERY_UNAVAILABLE'",Long.class,application)).isEqualTo(2);
    }

    @Test void tokensAreHashedAndAuditContainsOnlySafeIdsAndClosedEnums() throws Exception {
        long id=issue(application); String token=raw(id);
        String digest=jdbc.queryForObject("SELECT token_hash FROM brand_admin_invitations WHERE id=?",String.class,id);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(digest).isEqualTo(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        assertThat(accept(token,login(other)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        revoke(id); long fresh=issue(application); accept(raw(fresh),recipientToken); activate();
        String events=jdbc.queryForList("SELECT * FROM security_audit_events WHERE target_application_id=? ORDER BY id",application).toString();
        assertThat(events).contains("APPLICATION_REVIEWED","INVITATION_CREATED","INVITATION_REVOKED","INVITATION_ACCEPTED","BRAND_MEMBERSHIP_GRANTED","BRAND_ACTIVATED","REJECTED","user:"+recipient.getId());
        assertThat(events).doesNotContain(token,raw(fresh),digest,password,recipient.getEmail(),recipient.getPasswordHash(),recipientToken,platformToken);
    }

    @Test void failedAcceptanceAuditRollsBackMembershipAndTokenConsumption() {
        long id=issue(application); String token=raw(id);
        SecurityAudit failing=mock(SecurityAudit.class);
        doThrow(new IllegalStateException("Synthetic audit failure")).when(failing).onboarding(
                eq(Operation.INVITATION_ACCEPTED),any(),any(),any(),any(),any(),any());
        var service=new BrandInvitationService(jdbc,access,policy,invariant,failing,delivery,limiter,transactions,Duration.ofHours(24));
        Jwt jwt=decoder.decode(recipientToken);
        try {
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
            assertThatThrownBy(()->service.accept(token)).isInstanceOf(IllegalStateException.class);
        } finally { SecurityContextHolder.clearContext(); }
        assertThat(countMemberships()).isZero();
        assertThat(jdbc.queryForObject("SELECT accepted_at FROM brand_admin_invitations WHERE id=?",Object.class,id)).isNull();
        assertThat(accept(token,recipientToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private User user(UserRole role) { return users.saveAndFlush(new User(UUID.randomUUID()+"@example.invalid",passwords.encode(password),role,AccountStatus.ENABLED)); }
    private String login(User user) {
        var response=http.postForEntity("/api/v1/auth/login",Map.of("email",user.getEmail(),"password",password),JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); return response.getBody().path("accessToken").asText();
    }
    private long submit(String email) {
        var response=call(HttpMethod.POST,"/api/v1/brand-applications",null,Map.of("brandName","Synthetic "+UUID.randomUUID(),"contactName","Synthetic contact","contactEmail",email));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); return response.getBody().path("id").asLong();
    }
    private ResponseEntity<JsonNode> review(long id,String decision,String token) { return call(HttpMethod.PATCH,"/api/v1/admin/brand-applications/"+id+"/review",token,Map.of("decision",decision,"reviewNote","Synthetic review")); }
    private String base(long app) { return "/api/v1/super-admin/brand-applications/"+app; }
    private ResponseEntity<JsonNode> create(long app) { return call(HttpMethod.POST,base(app)+"/invitations",platformToken,null); }
    private long issue(long app) { var r=create(app); assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED); return r.getBody().path("id").asLong(); }
    private String raw(long id) { return delivery.messages.get(id).token(); }
    private ResponseEntity<JsonNode> reissue(long id) { return call(HttpMethod.POST,"/api/v1/super-admin/brand-invitations/"+id+"/reissue",platformToken,null); }
    private ResponseEntity<JsonNode> revoke(long id) { return call(HttpMethod.POST,"/api/v1/super-admin/brand-invitations/"+id+"/revoke",platformToken,null); }
    private ResponseEntity<JsonNode> accept(String raw,String jwt) { return call(HttpMethod.POST,"/api/v1/brand-invitations/accept",jwt,Map.of("token",raw)); }
    private ResponseEntity<JsonNode> activate() { return call(HttpMethod.POST,base(application)+"/activate",platformToken,null); }
    private JsonNode state() { return call(HttpMethod.GET,base(application)+"/onboarding",platformToken,null).getBody(); }
    private String merchant(long id) { return "/api/v1/merchant/brands/"+id+"/products"; }
    private long countMemberships() { return jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships WHERE user_id=? AND brand_id=?",Long.class,recipient.getId(),brand); }
    private ResponseEntity<JsonNode> call(HttpMethod method,String path,String token,Object body) { var headers=new HttpHeaders(); if(token!=null) headers.setBearerAuth(token); return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class); }
    private <T> T withPlatform(Jwt jwt,Supplier<T> action) {
        try { SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))); return action.get(); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
