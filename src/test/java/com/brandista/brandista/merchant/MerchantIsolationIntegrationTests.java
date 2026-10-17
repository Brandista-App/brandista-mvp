package com.brandista.brandista.merchant;

import com.brandista.brandista.brand.*;
import com.brandista.brandista.category.*;
import com.brandista.brandista.product.*;
import com.brandista.brandista.product.api.*;
import com.brandista.brandista.privilege.*;
import com.brandista.brandista.user.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:merchant-test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password="})
class MerchantIsolationIntegrationTests {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry properties) {
        String url = System.getenv("BRANDISTA_PR3_TEST_MYSQL_URL");
        if (url == null) return;
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/brandista_pr3_verify\\?.*"))
            throw new IllegalArgumentException("Only the disposable local PR3 schema is allowed");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> "root");
        properties.add("spring.datasource.password", () -> System.getenv("BRANDISTA_PR3_TEST_MYSQL_PASSWORD"));
    }
    @Autowired TestRestTemplate http;
    @Autowired UserRepository users;
    @Autowired BrandRepository brands;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired PlatformGrantRepository grants;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtDecoder decoder;
    @Autowired JwtEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired BrandMembershipService memberships;
    @Autowired MerchantCatalogueService merchant;
    @Autowired BrandAccess access;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    User owner, customer, legacy, platform;
    Brand a,b,c;
    Category category;
    Product pa,pb;
    Jwt platformJwt;
    PlatformGrant platformGrant;
    String password = UUID.randomUUID()+"-Synthetic!";

    @BeforeEach void fixture() {
        owner=user(UserRole.CUSTOMER); customer=user(UserRole.CUSTOMER);
        legacy=user(UserRole.ADMIN); platform=user(UserRole.CUSTOMER);
        platformGrant=grants.saveAndFlush(new PlatformGrant(platform,PlatformGrantType.SUPER_ADMIN));
        platformJwt=decoder.decode(token(platform));
        a=brand(); b=brand(); c=brand();
        category=categories.saveAndFlush(new Category("Synthetic category", "cat-"+UUID.randomUUID(),null));
        pa=product(a); pb=product(b);
        grant(owner,a);
    }

    @Test void unauthenticatedAndUnassignedCustomersCannotUseMerchantApis() {
        assertThat(call(HttpMethod.GET,base(a),null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String token=token(customer);
        for(String path:List.of("/api/v1/merchant/brands",base(a),path(a,pa),path(a,pa)+"/stock"))
            assertThat(call(HttpMethod.GET,path,token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,base(a),token,body(a)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void publicRegistrationCannotSelfAssignMembershipAndRetainsCustomerAccess() {
        String email=UUID.randomUUID()+"@example.invalid";
        var registered=http.postForEntity("/api/v1/registrations",Map.of("email",email,"password",password,
                "role","BRAND_ADMIN","brandIds",List.of(a.getId()),"memberships",List.of(Map.of("brandId",a.getId(),"active",true))),JsonNode.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        User created=users.findByEmail(email).orElseThrow();
        assertThat(created.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships WHERE user_id=?",Long.class,created.getId())).isZero();
        String token=token(created);
        assertThat(call(HttpMethod.GET,base(a),token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for(String path:List.of("cart","addresses","orders"))
            assertThat(call(HttpMethod.GET,"/api/v1/customers/me/"+path,token,null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void brandAHasNoCrossBrandReadWritePublicationOrInventoryAccess() {
        String token=token(owner);
        assertThat(call(HttpMethod.GET,base(b),token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for(String suffix:List.of("","/stock")) {
            assertThat(call(HttpMethod.GET,path(b,pb)+suffix,token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(HttpMethod.GET,path(a,pb)+suffix,token,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(call(HttpMethod.POST,base(b),token,body(b)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT,path(a,pb),token,body(a)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT,path(a,pb)+"/stock",token,Map.of("stockQuantity",999)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT,path(b,pb)+"/stock",token,Map.of("stockQuantity",999)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for(String action:List.of("publish","unpublish","deactivate"))
            assertThat(call(HttpMethod.PATCH,path(a,pb)+"/"+action,token,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Product unchanged=products.findById(pb.getId()).orElseThrow();
        assertThat(unchanged.getStockQuantity()).isEqualTo(12);
        assertThat(unchanged.getPublicationStatus()).isEqualTo(ProductPublicationStatus.DRAFT);
    }

    @Test void explicitMultipleMembershipsAndMultipleAdministratorsAreSupported() {
        grant(owner,b); grant(customer,a);
        String token=token(owner);
        for(Brand brand:List.of(a,b)) assertThat(call(HttpMethod.GET,base(brand),token,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,base(c),token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode assigned=call(HttpMethod.GET,"/api/v1/merchant/brands",token,null).getBody();
        List<Long> ids=new ArrayList<>(); assigned.forEach(row -> ids.add(row.path("id").asLong()));
        assertThat(ids).containsExactlyInAnyOrder(a.getId(),b.getId());
        assertThat(call(HttpMethod.GET,path(a,pa),token(customer),null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void assignedProductListsContainOnlyTheirTenantAndSupportPagination() {
        String token=token(owner);
        var response=call(HttpMethod.GET,base(a)+"?page=0&size=1&brandId="+b.getId()+"&userId="+platform.getId(),token,null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("totalElements").asLong()).isEqualTo(1);
        assertThat(response.getBody().path("content").get(0).path("id").asLong()).isEqualTo(pa.getId());
        assertThat(call(HttpMethod.GET,base(a)+"?size=101",token,null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void ownCatalogueUsesExistingCreationValidationPublicationAndStockRules() {
        String token=token(owner);
        var created=call(HttpMethod.POST,base(a),token,body(a));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id=created.getBody().path("id").asLong(); String path=base(a)+"/"+id;
        assertThat(created.getBody().path("publicationStatus").asText()).isEqualTo("DRAFT");
        assertThat(call(HttpMethod.PUT,path,token,body(a)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,path+"/stock",token,Map.of("stockQuantity",27)).getBody().path("stockQuantity").asInt()).isEqualTo(27);
        assertThat(call(HttpMethod.GET,path+"/stock",token,null).getBody().path("stockQuantity").asInt()).isEqualTo(27);
        assertThat(call(HttpMethod.PUT,path+"/stock",token,Map.of("stockQuantity",-1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for(String action:List.of("publish","unpublish","deactivate"))
            assertThat(call(HttpMethod.PATCH,path+"/"+action,token,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,path+"/stock",token,Map.of("stockQuantity",3)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.PATCH,path+"/publish",token,null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void brandReassignmentAndForgedActorAndTenantClaimsDoNotEscalate() {
        grant(owner,b); String token=token(owner);
        assertThat(call(HttpMethod.PUT,path(a,pa),token,body(b)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,base(a),token,body(b)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String,Object> forged=body(a); forged.put("userId",platform.getId()); forged.put("id",pb.getId());
        forged.put("platformGrants",List.of("SUPER_ADMIN"));
        var created=call(HttpMethod.POST,base(a),token,forged);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().path("id").asLong()).isNotEqualTo(pb.getId());
        assertThat(created.getBody().path("brandId").asLong()).isEqualTo(a.getId());
        assertThat(call(HttpMethod.GET,base(c),signedClaims(customer,c),null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(products.findById(pa.getId()).orElseThrow().getBrand().getId()).isEqualTo(a.getId());
    }

    @Test void revocationDeniesTheSameJwtImmediatelyAndRegrantIsExplicit() {
        String token=token(owner);
        assertThat(call(HttpMethod.GET,base(a),token,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asPlatform(() -> memberships.revoke(owner.getId(),a.getId())).changed()).isTrue();
        assertThat(decoder.decode(token).getExpiresAt()).isAfter(java.time.Instant.now());
        assertThat(call(HttpMethod.GET,base(a),token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,"/api/v1/merchant/brands",token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,"/api/v1/customers/me/addresses",token,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        grant(owner,a);
        assertThat(call(HttpMethod.GET,base(a),token,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships WHERE user_id=? AND brand_id=?",Long.class,owner.getId(),a.getId())).isEqualTo(1);
    }

    @Test void disabledAccountsAndInactiveBrandsFailClosedWithPreviouslyIssuedTokens() {
        String token=token(owner);
        a.deactivate(); brands.saveAndFlush(a);
        for(String path:List.of(base(a),path(a,pa),path(a,pa)+"/stock"))
            assertThat(call(HttpMethod.GET,path,token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,base(a),token,body(a)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PATCH,path(a,pa)+"/publish",token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asPlatform(() -> memberships.grant(customer.getId(),a.getId())).changed()).isFalse();
        owner.changeStatus(AccountStatus.DISABLED); users.saveAndFlush(owner);
        assertThat(call(HttpMethod.GET,"/api/v1/merchant/brands",token,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        asJwt(decoder.decode(token),List.of("ROLE_CUSTOMER"),() -> {
            assertThatThrownBy(() -> merchant.list(a.getId(),0,10)).isInstanceOf(AccessDeniedException.class); return null;
        });
        assertThat(asPlatform(() -> memberships.grant(owner.getId(),b.getId())).changed()).isFalse();
    }

    @Test void merchantMembershipDoesNotGrantPlatformOrGlobalAdminPrivileges() {
        String token=token(owner);
        for(String path:List.of("/api/v1/admin/brands","/api/v1/admin/categories","/api/v1/admin/products",
                "/api/v1/admin/brand-applications","/api/v1/super-admin/identity"))
            assertThat(call(HttpMethod.GET,path,token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for(String path:List.of("/api/v1/merchant/memberships","/api/v1/super-admin/memberships"))
            assertThat(call(HttpMethod.POST,path,token,Map.of("userId",owner.getId(),"brandId",b.getId())).getStatusCode().is4xxClientError()).isTrue();
    }

    @Test void platformAndLegacyGlobalAccessIsPreservedWithoutImplicitMerchantMembership() {
        for(User user:List.of(platform,legacy)) {
            String token=token(user);
            assertThat(call(HttpMethod.GET,"/api/v1/admin/products",token,null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(call(HttpMethod.GET,base(a),token,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(call(HttpMethod.GET,"/api/v1/super-admin/identity",token(platform),null).getStatusCode()).isEqualTo(HttpStatus.OK);
        grant(legacy,a);
        assertThat(call(HttpMethod.GET,base(a),token(legacy),null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,base(b),token(legacy),null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void serviceLayerRejectsCrossTenantAndUnassignedDirectCalls() {
        asJwt(decoder.decode(token(owner)),List.of("ROLE_CUSTOMER"),() -> {
            assertThatThrownBy(() -> merchant.list(b.getId(),0,10)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> merchant.get(a.getId(),pb.getId())).isInstanceOf(com.brandista.brandista.product.service.ProductNotFoundException.class);
            assertThatThrownBy(() -> merchant.update(a.getId(),pa.getId(),update(b))).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> memberships.grant(owner.getId(),b.getId())).isInstanceOf(AccessDeniedException.class);
            return null;
        });
    }

    @Test void membershipEventsIdentifyActorUserAndBrandWithoutSecretsAndRejectDuplicateLifecycleChanges() {
        assertThat(asPlatform(() -> memberships.grant(owner.getId(),a.getId())).changed()).isFalse();
        assertThat(asPlatform(() -> memberships.revoke(owner.getId(),a.getId())).changed()).isTrue();
        assertThat(asPlatform(() -> memberships.revoke(owner.getId(),a.getId())).changed()).isFalse();
        var events=jdbc.queryForList("SELECT * FROM security_audit_events WHERE target_user_id=? AND target_brand_id=? ORDER BY id",owner.getId(),a.getId());
        assertThat(events).hasSize(4);
        String serialized=events.toString();
        assertThat(serialized).contains("user:"+platform.getId(),"BRAND_MEMBERSHIP_GRANTED","BRAND_MEMBERSHIP_REVOKED","SUCCESS","REJECTED");
        assertThat(serialized).doesNotContain(password,owner.getEmail(),owner.getPasswordHash(),platformJwt.getTokenValue());
    }

    @Test void staleSuperAdminContextCannotGrantAfterPlatformRevocation() {
        // Other custodians from these fixtures preserve PR2's last-admin invariant.
        grants.saveAndFlush(new PlatformGrant(user(UserRole.CUSTOMER),PlatformGrantType.SUPER_ADMIN));
        platformGrant.revoke(); grants.saveAndFlush(platformGrant);
        asPlatform(() -> {
            assertThatThrownBy(() -> memberships.grant(customer.getId(),b.getId())).isInstanceOf(AccessDeniedException.class);
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships WHERE user_id=? AND brand_id=?",Long.class,customer.getId(),b.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_events WHERE target_user_id=? AND target_brand_id=? AND reason='ACCESS_DENIED'",Long.class,customer.getId(),b.getId())).isEqualTo(1);
    }

    @Test void concurrentExplicitGrantsCreateOneMembershipAndAuditBothAttempts() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt=() -> { start.await(); return asPlatform(() -> memberships.grant(customer.getId(),b.getId()).changed()); };
            var first=pool.submit(attempt); var second=pool.submit(attempt); start.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships WHERE user_id=? AND brand_id=?",Long.class,customer.getId(),b.getId())).isEqualTo(1);
    }

    @Test void membershipMutationRollsBackIfItsAuditInsertFails() {
        SecurityAudit unavailable=mock(SecurityAudit.class);
        doThrow(new IllegalStateException("synthetic audit outage")).when(unavailable)
                .append(any(),any(),any(),any(),any());
        var service=new BrandMembershipService(jdbc,access,new SecurityInvariant(jdbc),unavailable);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        asPlatform(() -> {
            assertThatThrownBy(() -> transaction.execute(status -> service.grant(customer.getId(),b.getId())))
                    .isInstanceOf(IllegalStateException.class);
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships WHERE user_id=? AND brand_id=?",Long.class,customer.getId(),b.getId())).isZero();
    }

    private User user(UserRole role) { return users.saveAndFlush(new User(UUID.randomUUID()+"@example.invalid",passwords.encode(password),role,AccountStatus.ENABLED)); }
    private Brand brand() { return brands.saveAndFlush(new Brand("Synthetic brand", "brand-"+UUID.randomUUID(),null,null)); }
    private Product product(Brand brand) { Product p=new Product("Synthetic product","product-"+UUID.randomUUID(),null,brand,category,new BigDecimal("150.00"),ProductAvailability.AVAILABLE); p.setStockQuantity(12); return products.saveAndFlush(p); }
    private String token(User user) { var r=http.postForEntity("/api/v1/auth/login",Map.of("email",user.getEmail(),"password",password),JsonNode.class); assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK); return r.getBody().path("accessToken").asText(); }
    private void grant(User user,Brand brand) { assertThat(asPlatform(() -> memberships.grant(user.getId(),brand.getId())).changed()).isTrue(); }
    private <T> T asPlatform(Supplier<T> action) { return asJwt(platformJwt,List.of("ROLE_SUPER_ADMIN"),action); }
    private <T> T asJwt(Jwt jwt,List<String> authorities,Supplier<T> action) {
        try { SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,authorities.stream().map(SimpleGrantedAuthority::new).toList())); return action.get(); }
        finally { SecurityContextHolder.clearContext(); }
    }
    private String base(Brand brand) { return "/api/v1/merchant/brands/"+brand.getId()+"/products"; }
    private String path(Brand brand,Product p) { return base(brand)+"/"+p.getId(); }
    private Map<String,Object> body(Brand brand) { return new HashMap<>(Map.of("name","Synthetic product","slug","test-"+UUID.randomUUID(),"brandId",brand.getId(),"categoryId",category.getId(),"price",150,"availability","AVAILABLE")); }
    private ProductUpdateRequest update(Brand brand) { return new ProductUpdateRequest("Synthetic","test-"+UUID.randomUUID(),null,brand.getId(),category.getId(),new BigDecimal("150.00"),ProductAvailability.AVAILABLE); }
    private ResponseEntity<JsonNode> call(HttpMethod method,String path,String token,Object body) { var headers=new HttpHeaders(); if(token!=null) headers.setBearerAuth(token); return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class); }
    private String signedClaims(User user,Brand brand) {
        var now=java.time.Instant.now(); var claims=JwtClaimsSet.builder().subject(user.getId().toString()).issuer("brandista-test").audience(List.of("brandista-test-api"))
                .issuedAt(now).expiresAt(now.plusSeconds(600)).claim("role","SUPER_ADMIN").claim("brandId",brand.getId()).claim("brandIds",List.of(brand.getId())).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
}
