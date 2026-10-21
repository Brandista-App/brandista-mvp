package com.brandista.brandista.privilege;

import com.brandista.brandista.brand.*;
import com.brandista.brandista.merchant.*;
import com.brandista.brandista.user.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:lifecycle-test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password="})
class AccountLifecycleIntegrationTests {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry properties) {
        String url=System.getenv("BRANDISTA_PR6B_TEST_MYSQL_URL");
        if (url==null) return;
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/brandista_pr6b_verify\\?.*"))
            throw new IllegalArgumentException("Only the disposable local PR6B schema is allowed");
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.username",()->"root");
        properties.add("spring.datasource.password",()->System.getenv("BRANDISTA_PR6B_TEST_MYSQL_PASSWORD"));
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired BrandRepository brands;
    @Autowired PlatformGrantRepository grants;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtDecoder decoder;
    @Autowired BrandMembershipService memberships;
    @Autowired AccountLifecycleService service;
    @Autowired BrandAccess access;
    @Autowired PlatformTransactionManager transactions;
    User operator,merchant,customer,legacy;
    Brand a,b;
    String operatorToken,merchantToken;
    String password=UUID.randomUUID()+"-Synthetic!";

    @BeforeEach void fixture() {
        operator=user(UserRole.CUSTOMER); merchant=user(UserRole.CUSTOMER);
        customer=user(UserRole.CUSTOMER); legacy=user(UserRole.ADMIN);
        grants.saveAndFlush(new PlatformGrant(operator,PlatformGrantType.SUPER_ADMIN));
        operatorToken=token(operator); merchantToken=token(merchant);
        a=brands.saveAndFlush(new Brand("Synthetic A","a-"+UUID.randomUUID(),null,null));
        b=brands.saveAndFlush(new Brand("Synthetic B","b-"+UUID.randomUUID(),null,null));
        asOperator(()->memberships.grant(merchant.getId(),a.getId()));
        asOperator(()->memberships.grant(merchant.getId(),b.getId()));
    }

    @Test void revokeSpecificBrandImmediatelyDeniesOldJwtButPreservesOtherBrandAndCustomer() {
        assertStatus(get(merchantPath(a),merchantToken),HttpStatus.OK);
        assertStatus(operation(merchant,"brand-memberships/"+a.getId()+"/revoke"),HttpStatus.NO_CONTENT);
        assertThat(decoder.decode(merchantToken).getExpiresAt()).isAfter(java.time.Instant.now());
        assertStatus(get(merchantPath(a),merchantToken),HttpStatus.FORBIDDEN);
        var headers=new HttpHeaders(); headers.setBearerAuth(merchantToken);
        assertStatus(http.exchange(merchantPath(a)+"/9223372036854775807/stock",HttpMethod.PUT,
                new HttpEntity<>(Map.of("stockQuantity",10),headers),JsonNode.class),HttpStatus.FORBIDDEN);
        assertStatus(get(merchantPath(b),merchantToken),HttpStatus.OK);
        for (String resource:List.of("cart","addresses","orders"))
            assertStatus(get("/api/v1/customers/me/"+resource,merchantToken),HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT version FROM brand_admin_memberships WHERE user_id=? AND brand_id=?",
                Long.class,merchant.getId(),a.getId())).isEqualTo(1L);
        assertAudit("BRAND_MEMBERSHIP_REVOKED","SUCCESS",merchant.getId());
    }

    @Test void anonymousMerchantCustomerAndLegacyAdminCannotOperate() {
        for (String suffix:List.of("disable","restore","brand-memberships/"+a.getId()+"/revoke")) {
            assertStatus(call(path(merchant,suffix),null,identity(merchant)),HttpStatus.UNAUTHORIZED);
            for (String bearer:List.of(merchantToken,token(customer),token(legacy)))
                assertStatus(call(path(merchant,suffix),bearer,identity(merchant)),HttpStatus.FORBIDDEN);
        }
        assertThat(users.findById(merchant.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.ENABLED);
        assertStatus(get(merchantPath(a),merchantToken),HttpStatus.OK);
    }

    @Test void disableRejectsLoginAndPreviouslyIssuedJwtAcrossAuthenticatedApis() {
        assertStatus(operation(merchant,"disable"),HttpStatus.NO_CONTENT);
        assertStatus(login(merchant),HttpStatus.UNAUTHORIZED);
        for (String path:List.of(merchantPath(a),"/api/v1/merchant/brands","/api/v1/customers/me/cart"))
            assertStatus(get(path,merchantToken),HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships WHERE user_id=? AND revoked_at IS NULL",
                Long.class,merchant.getId())).isEqualTo(2L);
        assertAudit("ORDINARY_ACCOUNT_DISABLED","SUCCESS",merchant.getId());
        assertStatus(get("/api/v1/super-admin/identity",operatorToken),HttpStatus.OK);
    }

    @Test void restorePreservesRevokedMembershipAndOrdinaryRoleWithoutCreatingGrants() {
        assertStatus(operation(merchant,"brand-memberships/"+a.getId()+"/revoke"),HttpStatus.NO_CONTENT);
        assertStatus(operation(merchant,"disable"),HttpStatus.NO_CONTENT);
        assertStatus(operation(merchant,"restore"),HttpStatus.NO_CONTENT);
        assertStatus(login(merchant),HttpStatus.OK);
        assertStatus(get(merchantPath(a),merchantToken),HttpStatus.FORBIDDEN);
        assertStatus(get(merchantPath(b),merchantToken),HttpStatus.OK);
        assertStatus(get("/api/v1/super-admin/identity",merchantToken),HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_platform_grants WHERE user_id=?",Long.class,merchant.getId())).isZero();
        assertThat(users.findById(merchant.getId()).orElseThrow().getRole()).isEqualTo(UserRole.CUSTOMER);
        assertAudit("ORDINARY_ACCOUNT_RESTORED","SUCCESS",merchant.getId());
    }

    @Test void ordinaryCustomerDisableRestoreWorksWithoutAnyMembership() {
        assertStatus(operation(customer,"disable"),HttpStatus.NO_CONTENT);
        assertStatus(operation(customer,"restore"),HttpStatus.NO_CONTENT);
        assertStatus(get("/api/v1/customers/me/cart",token(customer)),HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships WHERE user_id=?",Long.class,customer.getId())).isZero();
    }

    @Test void protectsSelfAllCustodiansHistoricalGrantsAndLegacyAdmins() {
        User other=user(UserRole.CUSTOMER);
        var grant=grants.saveAndFlush(new PlatformGrant(other,PlatformGrantType.SUPER_ADMIN));
        for (User protectedUser:List.of(operator,other,legacy))
            for (String action:List.of("disable","restore")) assertStatus(operation(protectedUser,action),HttpStatus.CONFLICT);
        grant.revoke(); grants.saveAndFlush(grant);
        assertStatus(operation(other,"disable"),HttpStatus.CONFLICT);
        other.changeStatus(AccountStatus.DISABLED); users.saveAndFlush(other);
        assertStatus(operation(other,"restore"),HttpStatus.CONFLICT);
        assertThat(users.findById(other.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertStatus(get("/api/v1/super-admin/identity",operatorToken),HttpStatus.OK);
    }

    @Test void explicitIdentityMissingTargetsValidationAndRepeatedOperationsFailClosed() {
        for (String action:List.of("disable","restore","brand-memberships/"+a.getId()+"/revoke")) {
            assertStatus(call(path(merchant,action),operatorToken,identity(customer)),HttpStatus.CONFLICT);
            assertStatus(call(path(merchant,action),operatorToken,Map.of("expectedEmail","bad")),HttpStatus.BAD_REQUEST);
            assertStatus(call("/api/v1/super-admin/users/9223372036854775807/"+action,operatorToken,identity(merchant)),HttpStatus.NOT_FOUND);
        }
        assertStatus(operation(merchant,"restore"),HttpStatus.CONFLICT);
        assertStatus(operation(merchant,"disable"),HttpStatus.NO_CONTENT);
        assertStatus(operation(merchant,"disable"),HttpStatus.CONFLICT);
        assertStatus(operation(merchant,"restore"),HttpStatus.NO_CONTENT);
        assertStatus(operation(merchant,"restore"),HttpStatus.CONFLICT);
        assertStatus(operation(merchant,"brand-memberships/"+a.getId()+"/revoke"),HttpStatus.NO_CONTENT);
        assertStatus(operation(merchant,"brand-memberships/"+a.getId()+"/revoke"),HttpStatus.CONFLICT);
        assertStatus(operation(customer,"brand-memberships/"+b.getId()+"/revoke"),HttpStatus.CONFLICT);
        assertStatus(operation(merchant,"brand-memberships/9223372036854775807/revoke"),HttpStatus.NOT_FOUND);
        assertAudit("ORDINARY_ACCOUNT_DISABLED","REJECTED",merchant.getId());
    }

    @Test void soleActiveCustodianCannotDisableOrRestoreItself() {
        // Disposable synthetic fixture only: arrange exactly one active custodian.
        jdbc.update("UPDATE user_platform_grants SET revoked_at=CURRENT_TIMESTAMP WHERE user_id<>? AND revoked_at IS NULL",operator.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_platform_grants g JOIN users u ON u.id=g.user_id "
                +"WHERE g.revoked_at IS NULL AND u.status='ENABLED'",Long.class)).isEqualTo(1L);
        assertStatus(operation(operator,"disable"),HttpStatus.CONFLICT);
        assertStatus(operation(operator,"restore"),HttpStatus.CONFLICT);
        assertStatus(get("/api/v1/super-admin/identity",operatorToken),HttpStatus.OK);
    }

    @Test void normalizedEmailAndInactiveBrandDisabledTargetRevocationAreSupported() {
        assertStatus(call(path(merchant,"disable"),operatorToken,Map.of("expectedEmail","  "+merchant.getEmail().toUpperCase(Locale.ROOT)+"  ")),HttpStatus.NO_CONTENT);
        a.deactivate(); brands.saveAndFlush(a);
        assertStatus(operation(merchant,"brand-memberships/"+a.getId()+"/revoke"),HttpStatus.NO_CONTENT);
        assertStatus(operation(merchant,"restore"),HttpStatus.NO_CONTENT);
        assertStatus(get(merchantPath(a),merchantToken),HttpStatus.FORBIDDEN);
    }

    @Test void oldSuperAdminJwtCannotOperateAfterGrantRevocation() {
        var grant=grants.findAll().stream().filter(g->g.subjectId().equals(operator.getId())).findFirst().orElseThrow();
        grants.saveAndFlush(new PlatformGrant(user(UserRole.CUSTOMER),PlatformGrantType.SUPER_ADMIN));
        grant.revoke(); grants.saveAndFlush(grant);
        assertStatus(operation(merchant,"disable"),HttpStatus.FORBIDDEN);
        asOperator(()-> {
            assertThatThrownBy(()->service.disable(merchant.getId(),merchant.getEmail())).isInstanceOf(AccessDeniedException.class);
            return null;
        });
        assertThat(users.findById(merchant.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.ENABLED);
        assertAudit("ORDINARY_ACCOUNT_DISABLED","REJECTED",merchant.getId());
    }

    @Test void concurrentDisableRequestsMakeOneTransitionAndAuditBoth() throws Exception {
        concurrent("disable");
        assertThat(users.findById(merchant.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertAudit("ORDINARY_ACCOUNT_DISABLED","SUCCESS",merchant.getId());
        assertAudit("ORDINARY_ACCOUNT_DISABLED","REJECTED",merchant.getId());
    }

    @Test void concurrentRestoresDoNotResurrectRevokedMembership() throws Exception {
        operation(merchant,"brand-memberships/"+a.getId()+"/revoke"); operation(merchant,"disable");
        concurrent("restore");
        assertStatus(get(merchantPath(a),merchantToken),HttpStatus.FORBIDDEN);
        assertStatus(get(merchantPath(b),merchantToken),HttpStatus.OK);
    }

    @Test void concurrentRevokesIncrementVersionOnlyOnce() throws Exception {
        concurrent("brand-memberships/"+a.getId()+"/revoke");
        assertThat(jdbc.queryForObject("SELECT version FROM brand_admin_memberships WHERE user_id=? AND brand_id=?",
                Long.class,merchant.getId(),a.getId())).isEqualTo(1L);
    }

    @Test void concurrentRestoreAndRevocationNeverResurrectMembership() throws Exception {
        assertStatus(operation(merchant,"disable"),HttpStatus.NO_CONTENT);
        var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var restore=pool.submit(()-> { start.await(); return operation(merchant,"restore").getStatusCode(); });
            var revoke=pool.submit(()-> { start.await(); return operation(merchant,"brand-memberships/"+a.getId()+"/revoke").getStatusCode(); });
            start.countDown();
            assertThat(restore.get(30,TimeUnit.SECONDS)).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(revoke.get(30,TimeUnit.SECONDS)).isEqualTo(HttpStatus.NO_CONTENT);
        }
        assertStatus(get(merchantPath(a),merchantToken),HttpStatus.FORBIDDEN);
        assertStatus(get(merchantPath(b),merchantToken),HttpStatus.OK);
    }

    @Test void auditFailureRollsBackAccountStatusChange() {
        SecurityAudit unavailable=mock(SecurityAudit.class);
        doThrow(new IllegalStateException("synthetic outage")).when(unavailable).append(any(),any(),any(),any());
        var isolated=new AccountLifecycleService(jdbc,access,new SecurityInvariant(jdbc),unavailable,memberships);
        asOperator(()-> {
            assertThatThrownBy(()->new TransactionTemplate(transactions).execute(s->isolated.disable(merchant.getId(),merchant.getEmail())))
                    .isInstanceOf(IllegalStateException.class);
            return null;
        });
        assertThat(users.findById(merchant.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.ENABLED);
    }

    @Test void auditFailureRollsBackRestoreAndMembershipRevocation() {
        SecurityAudit unavailable=mock(SecurityAudit.class);
        doThrow(new IllegalStateException("synthetic outage")).when(unavailable).append(any(),any(),any(),any());
        doThrow(new IllegalStateException("synthetic outage")).when(unavailable).append(any(),any(),any(),any(),any());
        var invariant=new SecurityInvariant(jdbc);
        var isolated=new AccountLifecycleService(jdbc,access,invariant,unavailable,
                new BrandMembershipService(jdbc,access,invariant,unavailable));
        assertStatus(operation(merchant,"disable"),HttpStatus.NO_CONTENT);
        asOperator(()-> {
            var transaction=new TransactionTemplate(transactions);
            assertThatThrownBy(()->transaction.execute(s->isolated.restore(merchant.getId(),merchant.getEmail())))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->transaction.execute(s->isolated.revoke(merchant.getId(),merchant.getEmail(),a.getId())))
                    .isInstanceOf(IllegalStateException.class);
            return null;
        });
        assertThat(users.findById(merchant.getId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertThat(jdbc.queryForObject("SELECT version FROM brand_admin_memberships WHERE user_id=? AND brand_id=?",
                Long.class,merchant.getId(),a.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships WHERE user_id=? AND revoked_at IS NULL",
                Long.class,merchant.getId())).isEqualTo(2L);
    }

    @Test void auditsContainOnlySafeMetadataAndNoGrantRouteIsExposed() {
        operation(merchant,"disable"); operation(merchant,"restore");
        var events=jdbc.queryForList("SELECT * FROM security_audit_events WHERE target_user_id=?",merchant.getId());
        assertThat(events.toString()).contains("user:"+operator.getId(),"ORDINARY_ACCOUNT_DISABLED","ORDINARY_ACCOUNT_RESTORED")
                .doesNotContain(password,merchant.getEmail(),merchant.getPasswordHash(),operatorToken,merchantToken);
        assertStatus(operation(merchant,"brand-memberships/"+a.getId()+"/grant"),HttpStatus.NOT_FOUND);
    }

    private void concurrent(String action) throws Exception {
        var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            Callable<HttpStatusCode> attempt=()-> { start.await(); return operation(merchant,action).getStatusCode(); };
            var first=pool.submit(attempt); var second=pool.submit(attempt); start.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(HttpStatus.NO_CONTENT,HttpStatus.CONFLICT);
        }
    }
    private void assertAudit(String operation,String outcome,long target) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_events WHERE operation=? AND outcome=? AND target_user_id=? AND actor=?",
                Long.class,operation,outcome,target,"user:"+operator.getId())).isGreaterThan(0L);
    }
    private User user(UserRole role) { return users.saveAndFlush(new User(UUID.randomUUID()+"@example.invalid",passwords.encode(password),role,AccountStatus.ENABLED)); }
    private ResponseEntity<JsonNode> login(User user) { return http.postForEntity("/api/v1/auth/login",Map.of("email",user.getEmail(),"password",password),JsonNode.class); }
    private String token(User user) { var r=login(user); assertStatus(r,HttpStatus.OK); return r.getBody().path("accessToken").asText(); }
    private String path(User user,String action) { return "/api/v1/super-admin/users/"+user.getId()+"/"+action; }
    private String merchantPath(Brand brand) { return "/api/v1/merchant/brands/"+brand.getId()+"/products"; }
    private Map<String,String> identity(User user) { return Map.of("expectedEmail",user.getEmail()); }
    private ResponseEntity<JsonNode> operation(User user,String action) { return call(path(user,action),operatorToken,identity(user)); }
    private ResponseEntity<JsonNode> call(String path,String token,Object body) { var headers=new HttpHeaders(); if(token!=null) headers.setBearerAuth(token); return http.exchange(path,HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class); }
    private ResponseEntity<JsonNode> get(String path,String token) { var headers=new HttpHeaders(); headers.setBearerAuth(token); return http.exchange(path,HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class); }
    private void assertStatus(ResponseEntity<?> result,HttpStatus status) { assertThat(result.getStatusCode()).isEqualTo(status); }
    private <T> T asOperator(Supplier<T> action) {
        try { SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(operatorToken),List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))); return action.get(); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
