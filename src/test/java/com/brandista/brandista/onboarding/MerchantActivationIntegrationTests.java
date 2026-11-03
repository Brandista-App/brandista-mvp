package com.brandista.brandista.onboarding;

import com.brandista.brandista.user.*;
import com.brandista.brandista.privilege.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:merchant-activation;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.username=sa","spring.datasource.password=",
    "brandista.invitations.email-enabled=true","brandista.invitations.auto-on-approval=true",
    "brandista.invitations.token-key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "brandista.security.cors.allowed-origins=http://127.0.0.1:5190",
    "brandista.invitations.initial-delay-ms=86400000"})
@Import(MerchantActivationIntegrationTests.ProviderFixture.class)
class MerchantActivationIntegrationTests {
    @org.springframework.test.context.DynamicPropertySource
    static void mysql(org.springframework.test.context.DynamicPropertyRegistry properties){
        String url=System.getenv("MERCHANT_API_TEST_MYSQL_URL");
        if(url==null)return;
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/brandista_merchant_api_verify\\?.*"))throw new IllegalArgumentException("Only a disposable local merchant API test schema is allowed.");
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.username",()->"root");
        properties.add("spring.datasource.password",()->System.getenv("MERCHANT_TEST_MYSQL_PASSWORD"));
    }
    @TestConfiguration static class ProviderFixture {
        @Bean @Primary MerchantEmailProvider fixtureProvider(){return mock(MerchantEmailProvider.class);}
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PlatformGrantRepository grants;
    @Autowired PasswordEncoder passwords;
    @Autowired InvitationTokens tokens;
    @Autowired MerchantInvitationDelivery delivery;
    @Autowired MerchantEmailProvider provider;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    String admin,email,raw;
    long application,brand,invitation;
    final String password="Synthetic-Merchant-Password-2026!";
    @BeforeEach void fixture(){
        reset(provider);
        jdbc.update("DELETE FROM merchant_registration_limits");
        User operator=users.saveAndFlush(new User(UUID.randomUUID()+"@example.invalid",passwords.encode(password),UserRole.CUSTOMER,AccountStatus.ENABLED));
        grants.saveAndFlush(new PlatformGrant(operator,PlatformGrantType.SUPER_ADMIN));
        admin=login(operator.getEmail(),password);
        email=UUID.randomUUID()+"@example.invalid";
        var submitted=call(HttpMethod.POST,"/api/v1/brand-applications",null,Map.of("brandName","TSC synthetic "+UUID.randomUUID(),"contactName","Synthetic merchant","contactEmail",email));
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        application=submitted.getBody().path("id").asLong();
        var approved=call(HttpMethod.PATCH,"/api/v1/admin/brand-applications/"+application+"/review",admin,Map.of("decision","APPROVED"));
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        brand=approved.getBody().path("brandId").asLong();
        invitation=jdbc.queryForObject("SELECT id FROM brand_admin_invitations WHERE application_id=?",Long.class,application);
        raw=tokens.token(jdbc.queryForObject("SELECT delivery_nonce FROM brand_admin_invitations WHERE id=?",String.class,invitation));
    }
    @Test void endToEndApprovalDeliveryRegistrationLoginAndBrandIsolation(){
        var row=jdbc.queryForMap("SELECT * FROM brand_admin_invitations WHERE id=?",invitation);
        assertThat(Duration.between(((java.sql.Timestamp)row.get("created_at")).toLocalDateTime(),((java.sql.Timestamp)row.get("expires_at")).toLocalDateTime())).isEqualTo(Duration.ofHours(48));
        assertThat(row.get("token_hash")).isEqualTo(InvitationTokens.hash(raw));
        assertThat(jdbc.queryForList("SELECT * FROM merchant_invitation_delivery WHERE invitation_id=?",invitation).toString()).doesNotContain(raw);
        assertThat(state()).isEqualTo("QUEUED");
        verifyNoInteractions(provider);
        when(provider.send(anyString(),eq(email),anyString(),eq(raw),anyString())).thenReturn("synthetic-provider-id");
        delivery.process();
        assertThat(state()).isEqualTo("PROVIDER_ACCEPTED");
        delivery.process();
        verify(provider,times(1)).send(anyString(),eq(email),anyString(),eq(raw),anyString());
        when(provider.status("synthetic-provider-id")).thenReturn("DELIVERED");
        jdbc.update("UPDATE merchant_invitation_delivery SET next_attempt_at=? WHERE invitation_id=?",LocalDateTime.now(Clock.systemUTC()).minusMinutes(1),invitation);
        delivery.poll();assertThat(state()).isEqualTo("DELIVERED");
        var preview=call(HttpMethod.POST,"/api/v1/merchant-registration/verify",null,Map.of("token",raw));
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preview.getHeaders().getCacheControl()).contains("no-store");
        assertThat(preview.getBody().path("recipientEmail").asText()).isEqualTo(email);
        var completed=complete(raw,email,password,true);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String merchant=login(email,password);
        var assigned=call(HttpMethod.GET,"/api/v1/merchant/brands",merchant,null);
        assertThat(assigned.getBody()).hasSize(1);
        assertThat(assigned.getBody().get(0).path("id").asLong()).isEqualTo(brand);
        assertThat(call(HttpMethod.GET,"/api/v1/merchant/brands/"+brand+"/products",merchant,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,"/api/v1/merchant/brands/"+(brand+100000)+"/products",merchant,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,"/api/v1/super-admin/identity",merchant,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(complete(raw,email,password,true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Long.class,email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT terms_version FROM brand_admin_invitations WHERE id=?",String.class,invitation)).isEqualTo(BrandInvitationService.TERMS_VERSION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_platform_grants g JOIN users u ON u.id=g.user_id WHERE u.email=?",Long.class,email)).isZero();
    }
    @Test void invalidConsentRecipientAndExpiredTokenNeverCreateAccounts(){
        assertThat(complete(raw,email,password,false).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(complete(raw,"other@example.invalid",password,true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        jdbc.update("UPDATE brand_admin_invitations SET expires_at=? WHERE id=?",LocalDateTime.now(Clock.systemUTC()).minusSeconds(1),invitation);
        assertThat(complete(raw,email,password,true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Long.class,email)).isZero();
        delivery.process();assertThat(state()).isEqualTo("CANCELLED");verifyNoInteractions(provider);
    }
    @Test void existingAccountRequiresItsPasswordAndNeverResetsIt(){
        User existing=users.saveAndFlush(new User(email,passwords.encode(password),UserRole.CUSTOMER,AccountStatus.ENABLED));
        String original=existing.getPasswordHash();
        assertThat(complete(raw,email,"Wrong-Synthetic-Password-2026",true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(complete(raw,email,password,true).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(users.findByEmail(email).orElseThrow().getPasswordHash()).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Long.class,email)).isEqualTo(1);
    }
    @Test void resendRotatesRevokesAndDuplicateActionsDoNotSend(){
        assertThat(call(HttpMethod.POST,"/api/v1/super-admin/brand-applications/"+application+"/invitations",admin,null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST,"/api/v1/super-admin/brand-invitations/"+invitation+"/reissue",admin,null).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        jdbc.update("UPDATE brand_admin_invitations SET created_at=? WHERE id=?",LocalDateTime.now(Clock.systemUTC()).minusMinutes(2),invitation);
        var resent=call(HttpMethod.POST,"/api/v1/super-admin/brand-invitations/"+invitation+"/reissue",admin,null);
        assertThat(resent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(complete(raw,email,password,true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        long next=resent.getBody().path("id").asLong();
        assertThat(call(HttpMethod.POST,"/api/v1/super-admin/brand-invitations/"+next+"/revoke",admin,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        delivery.process();delivery.process();verifyNoInteractions(provider);
    }
    @Test void concurrentAcceptanceCommitsOneAccountAndOneMembership() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)){
            var tasks=List.of(executor.submit(()->complete(raw,email,password,true).getStatusCode()),executor.submit(()->complete(raw,email,password,true).getStatusCode()));
            var statuses=List.of(tasks.get(0).get(30,TimeUnit.SECONDS),tasks.get(1).get(30,TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.BAD_REQUEST);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Long.class,email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships WHERE brand_id=?",Long.class,brand)).isEqualTo(1);
    }
    @Test void providerFailureRetriesSameKeyAndAmbiguousOldSendsNeverRetry(){
        when(provider.send(anyString(),eq(email),anyString(),eq(raw),anyString())).thenThrow(new IllegalStateException("Synthetic provider failure"));
        delivery.process();assertThat(state()).isEqualTo("RETRY");
        jdbc.update("UPDATE merchant_invitation_delivery SET next_attempt_at=?,first_attempt_at=? WHERE invitation_id=?",LocalDateTime.now(Clock.systemUTC()).minusMinutes(1),LocalDateTime.now(Clock.systemUTC()).minusHours(24),invitation);
        delivery.process();assertThat(state()).isEqualTo("FAILED");
        verify(provider,times(1)).send(anyString(),eq(email),anyString(),eq(raw),anyString());
    }
    @Test void exhaustedCrashRecoveryNeverSendsAgain(){
        jdbc.update("UPDATE merchant_invitation_delivery SET state='SENDING',attempts=6,lease_until=?,next_attempt_at=? WHERE invitation_id=?",LocalDateTime.now(Clock.systemUTC()).minusMinutes(3),LocalDateTime.now(Clock.systemUTC()).minusMinutes(3),invitation);
        delivery.process();assertThat(state()).isEqualTo("FAILED");verifyNoInteractions(provider);
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="MERCHANT_BROWSER_ADMIN_ROOT",matches=".+")
    void realBrowserAcceptanceLoginWorkspaceAndReplay() throws Exception {
        var builder=new ProcessBuilder("node","scripts/test-merchant-browser.mjs");
        builder.environment().put("MERCHANT_BROWSER_API","http://127.0.0.1:"+port+"/api/v1");
        builder.environment().put("MERCHANT_BROWSER_TOKEN",raw);
        builder.environment().put("MERCHANT_BROWSER_EMAIL",email);
        builder.environment().put("MERCHANT_BROWSER_PASSWORD",password);
        builder.environment().put("MERCHANT_BROWSER_BRAND",jdbc.queryForObject("SELECT name FROM brands WHERE id=?",String.class,brand));
        builder.redirectErrorStream(true).redirectOutput(new java.io.File("target/merchant-browser.log"));
        Process process=builder.start();
        if(!process.waitFor(180,TimeUnit.SECONDS)){process.destroyForcibly();fail("Browser test timed out.");}
        assertThat(process.exitValue()).as("Read sanitized target/merchant-browser.log").isZero();
        verifyNoInteractions(provider);
    }
    private String state(){return jdbc.queryForObject("SELECT state FROM merchant_invitation_delivery WHERE invitation_id=?",String.class,invitation);}
    private ResponseEntity<JsonNode> complete(String token,String recipient,String secret,boolean terms){return call(HttpMethod.POST,"/api/v1/merchant-registration/complete",null,Map.of("token",token,"email",recipient,"password",secret,"termsAccepted",terms,"termsVersion",BrandInvitationService.TERMS_VERSION));}
    private String login(String recipient,String secret){var response=call(HttpMethod.POST,"/api/v1/auth/login",null,Map.of("email",recipient,"password",secret));assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);return response.getBody().path("accessToken").asText();}
    private ResponseEntity<JsonNode> call(HttpMethod method,String path,String token,Object body){var headers=new HttpHeaders();if(token!=null)headers.setBearerAuth(token);return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);}
}
