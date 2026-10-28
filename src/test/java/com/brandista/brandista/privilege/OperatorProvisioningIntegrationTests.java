package com.brandista.brandista.privilege;

import com.brandista.brandista.user.*;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static com.brandista.brandista.privilege.OperatorProvisioning.Mode.*;
import static com.brandista.brandista.privilege.SecurityAudit.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:operator-test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.properties.hibernate.jdbc.batch_size=10"})
class OperatorProvisioningIntegrationTests {
    @DynamicPropertySource
    static void isolatedMysqlOverride(DynamicPropertyRegistry properties) {
        String url = System.getenv("BRANDISTA_PR2_TEST_MYSQL_URL");
        if (url == null) return;
        // This suite deletes fixtures. Never allow it to target a live/demo schema.
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/brandista_pr2_verify\\?.*"))
            throw new IllegalArgumentException("Only the disposable local PR2 verification schema is allowed");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> "root");
        properties.add("spring.datasource.password", () -> System.getenv("BRANDISTA_PR2_TEST_MYSQL_PASSWORD"));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired SecurityInvariant invariant;
    @Autowired SecurityAudit audit;
    @Autowired PasswordEncoder passwords;
    @Autowired Validator validator;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OperatorProvisioning defaultDisabled;
    @Autowired UserRepository users;
    @Autowired PlatformGrantRepository grants;
    @Autowired TestRestTemplate http;
    OperatorProvisioning enabled;

    @BeforeEach void resetIsolatedFixture() {
        jdbc.update("DELETE FROM security_audit_events");
        jdbc.update("DELETE FROM user_platform_grants");
        jdbc.update("DELETE FROM users");
        jdbc.update("UPDATE security_provisioning_state SET initialized_at=NULL WHERE id=1");
        enabled = service(true, true, audit);
    }

    @Test void createsExplicitGrantWithoutChangingCustomerRoleAndErasesPasswordBuffer() {
        char[] secret = secret(); String expected = new String(secret);
        var result = enabled.execute(INITIAL_CREATE, "  Owner@Example.invalid ", null, secret);
        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(secret).containsOnly('\0');
        User owner = users.findById(result.targetUserId()).orElseThrow();
        assertThat(owner.getEmail()).isEqualTo("owner@example.invalid");
        assertThat(owner.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(passwords.matches(expected, owner.getPasswordHash())).isTrue();
        assertThat(grants.existsByUserIdAndGrantTypeAndRevokedAtIsNull(owner.getId(), PlatformGrantType.SUPER_ADMIN)).isTrue();
        assertThat(jdbc.queryForObject("SELECT initialized_at IS NOT NULL FROM security_provisioning_state WHERE id=1", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT operation FROM security_audit_events", String.class)).isEqualTo("INITIAL_CREATE");
    }

    @Test void bootstrapIsDisabledByDefaultAndRejectionsAreDurable() {
        char[] password = secret();
        assertThat(defaultDisabled.execute(INITIAL_CREATE, email(), null, password).reason()).isEqualTo(Reason.DISABLED);
        assertThat(password).containsOnly('\0');
        assertThat(users.count()).isZero(); assertThat(grants.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT outcome FROM security_audit_events", String.class)).isEqualTo("REJECTED");
    }

    @Test void repeatAndHistoricalGrantBootstrapAreRejected() {
        assertThat(create().outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(create().reason()).isEqualTo(Reason.ALREADY_INITIALIZED);
        // Even an operator accidentally clearing the marker cannot reuse initial bootstrap.
        jdbc.update("UPDATE security_provisioning_state SET initialized_at=NULL WHERE id=1");
        jdbc.update("UPDATE user_platform_grants SET revoked_at=CURRENT_TIMESTAMP");
        assertThat(create().reason()).isEqualTo(Reason.ALREADY_INITIALIZED);
        assertThat(users.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_events WHERE outcome='REJECTED'", Long.class)).isEqualTo(2);
    }

    @Test void concurrentInitialBootstrapHasExactlyOneWinnerAndAuditsBoth() throws Exception {
        var outcomes = concurrent(() -> create().outcome(), () -> create().outcome());
        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.SUCCESS, Outcome.REJECTED);
        assertThat(users.count()).isEqualTo(1); assertThat(grants.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_events", Long.class)).isEqualTo(2);
    }

    @Test void createNeverSilentlyPromotesExistingIdentityAndGrantRequiresVerifiedId() {
        User existing = user(AccountStatus.ENABLED);
        String hash = existing.getPasswordHash();
        assertThat(enabled.execute(INITIAL_CREATE, existing.getEmail(), null, secret()).reason()).isEqualTo(Reason.IDENTITY_EXISTS);
        assertThat(enabled.execute(INITIAL_GRANT, existing.getEmail(), existing.getId()+1, null).reason()).isEqualTo(Reason.IDENTITY_MISMATCH);
        assertThat(enabled.execute(INITIAL_GRANT, existing.getEmail(), null, null).reason()).isEqualTo(Reason.IDENTITY_MISMATCH);
        assertThat(grants.count()).isZero();
        assertThat(enabled.execute(INITIAL_GRANT, existing.getEmail(), existing.getId(), null).outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(users.findById(existing.getId()).orElseThrow().getPasswordHash()).isEqualTo(hash);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test void disabledIdentityIsNeverEnabledOrPromotedAndMissingIdentityIsNotCreated() {
        User disabled = user(AccountStatus.DISABLED);
        assertThat(enabled.execute(INITIAL_GRANT, disabled.getEmail(), disabled.getId(), null).reason()).isEqualTo(Reason.ACCOUNT_DISABLED);
        assertThat(enabled.execute(INITIAL_GRANT, email(), disabled.getId(), null).reason()).isEqualTo(Reason.NOT_FOUND);
        assertThat(grants.count()).isZero();
    }

    @Test void validatesEmailAndPasswordWithNoDefaultOrHardcodedCredentialFallback() throws Exception {
        for (char[] password : new char[][] {null, new char[0], "too-short".toCharArray(), "x".repeat(73).toCharArray()})
            assertThat(enabled.execute(INITIAL_CREATE, email(), null, password).reason()).isEqualTo(Reason.INVALID_INPUT);
        assertThat(enabled.execute(INITIAL_CREATE, "not-email", null, secret()).reason()).isEqualTo(Reason.INVALID_INPUT);
        assertThat(users.count()).isZero();
        String source = Files.readString(Path.of("src/main/java/com/brandista/operator/BootstrapMain.java"));
        assertThat(source).contains("System.console()", "readPassword(", "args.length != 0");
        assertThat(source).doesNotContain("System.getenv(\"PASSWORD", "readLine(\"New password", "defaultPassword");
    }

    @Test void lastSuperAdminCannotBeRevokedDisabledOrDeletedThroughOperatorOrJpa() {
        long id = create().targetUserId(); User owner = users.findById(id).orElseThrow();
        assertThat(enabled.execute(REVOKE, owner.getEmail(), id, null).reason()).isEqualTo(Reason.LAST_SUPER_ADMIN);
        assertThat(enabled.execute(DISABLE, owner.getEmail(), id, null).reason()).isEqualTo(Reason.LAST_SUPER_ADMIN);
        PlatformGrant grant = grants.findAll().getFirst(); grant.revoke();
        assertThatThrownBy(() -> grants.saveAndFlush(grant)).hasStackTraceContaining("last active super-admin");
        owner.changeStatus(AccountStatus.DISABLED);
        assertThatThrownBy(() -> users.saveAndFlush(owner)).hasStackTraceContaining("last active super-admin");
        assertThatThrownBy(() -> grants.deleteById(grant.getId())).hasStackTraceContaining("last active super-admin");
        assertThatThrownBy(() -> users.deleteById(id)).hasStackTraceContaining("last active super-admin");
        assertThat(users.findById(id).orElseThrow().getStatus()).isEqualTo(AccountStatus.ENABLED);
        assertThat(grants.findById(grant.getId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_events WHERE reason='LAST_SUPER_ADMIN'", Long.class)).isEqualTo(6);
        assertThatThrownBy(() -> users.deleteAllInBatch()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> grants.deleteAllByIdInBatch(List.of(grant.getId()))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void concurrentRemovalCannotRemoveBothCustodians() throws Exception {
        User first = users.findById(create().targetUserId()).orElseThrow();
        User second = users.findById(enabled.execute(RECOVER_CREATE, email(), null, secret()).targetUserId()).orElseThrow();
        assertThat(concurrent(() -> enabled.execute(REVOKE, first.getEmail(), first.getId(), null).reason(),
                () -> enabled.execute(DISABLE, second.getEmail(), second.getId(), null).reason()))
                .containsExactlyInAnyOrder(Reason.COMPLETED, Reason.LAST_SUPER_ADMIN);
        List<Long> active = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s -> invariant.activeSuperAdmins());
        assertThat(active).hasSize(1);
    }

    @Test void deferredJpaDeletesAndBatchedDisablesCannotRemoveAllCustodiansInOneTransaction() {
        create(); enabled.execute(RECOVER_CREATE, email(), null, secret());
        assertThatThrownBy(() -> grants.deleteAll()).hasStackTraceContaining("last active super-admin");
        assertThat(grants.count()).isEqualTo(2);
        var owners = users.findAll(); owners.forEach(owner -> owner.changeStatus(AccountStatus.DISABLED));
        assertThatThrownBy(() -> users.saveAllAndFlush(owners)).hasStackTraceContaining("last active super-admin");
        assertThat(users.findAll()).allMatch(owner -> owner.getStatus() == AccountStatus.ENABLED);
        assertThat(jdbc.queryForList("SELECT operation FROM security_audit_events WHERE outcome='SUCCESS' AND "
                + "operation IN ('GRANT_REMOVED','ACCOUNT_STATUS_CHANGED')")).isEmpty();
    }

    @Test void ordinaryJpaSecurityMutationsAreAuditedWithoutCredentialMaterial() {
        create(); // A separate custodian permits revocation/disablement of this fixture.
        User target = user(AccountStatus.ENABLED);
        PlatformGrant grant = grants.saveAndFlush(new PlatformGrant(target, PlatformGrantType.SUPER_ADMIN));
        char[] password = secret(); String raw = new String(password); String hash = passwords.encode(raw);
        target.changeRole(UserRole.ADMIN); target.changePasswordHash(hash);
        users.saveAndFlush(target);
        target.changeStatus(AccountStatus.DISABLED); users.saveAndFlush(target);
        grant.revoke(); grants.saveAndFlush(grant);
        String events = jdbc.queryForList("SELECT * FROM security_audit_events WHERE target_user_id=?", target.getId()).toString();
        assertThat(events).contains("GRANT_CREATED", "GRANT_CHANGED", "ACCOUNT_ROLE_CHANGED",
                "ACCOUNT_PASSWORD_CHANGED", "ACCOUNT_STATUS_CHANGED");
        assertThat(events).doesNotContain(raw, hash, target.getEmail());
    }

    @Test void recoveryRequiresSeparateGateAndSupportsSafeCustodianReplacementAndPasswordReset() {
        var first = create(); User owner = users.findById(first.targetUserId()).orElseThrow();
        assertThat(service(true, false, audit).execute(RECOVER_CREATE, email(), null, secret()).reason()).isEqualTo(Reason.RECOVERY_DISABLED);
        User replacement = user(AccountStatus.ENABLED);
        assertThat(enabled.execute(RECOVER_GRANT, replacement.getEmail(), replacement.getId(), null).outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(enabled.execute(REVOKE, owner.getEmail(), owner.getId(), null).outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(enabled.execute(RESET_PASSWORD, owner.getEmail(), owner.getId(), secret()).reason()).isEqualTo(Reason.NOT_GRANTED);
        assertThat(enabled.execute(RECOVER_GRANT, owner.getEmail(), owner.getId(), null).outcome()).isEqualTo(Outcome.SUCCESS);
        String oldHash = owner.getPasswordHash(); char[] password = secret(); String expected = new String(password);
        assertThat(enabled.execute(RESET_PASSWORD, owner.getEmail(), owner.getId(), password).outcome()).isEqualTo(Outcome.SUCCESS);
        String hash = users.findById(owner.getId()).orElseThrow().getPasswordHash();
        assertThat(hash).isNotEqualTo(oldHash); assertThat(passwords.matches(expected, hash)).isTrue();
        assertThat(create().reason()).isEqualTo(Reason.ALREADY_INITIALIZED);
    }

    @Test void auditContainsOnlyClosedMetadataAndNoCredentialEmailTokenOrHash() {
        char[] secret = secret(); String rawSecret = new String(secret);
        long id = enabled.execute(INITIAL_CREATE, "sensitive-email@example.invalid", null, secret).targetUserId();
        User owner = users.findById(id).orElseThrow();
        String token = login(owner, rawSecret);
        assertThat(getIdentity(token).getStatusCode()).isEqualTo(HttpStatus.OK);
        enabled.execute(DISABLE, owner.getEmail(), id, null);
        String events = jdbc.queryForList("SELECT * FROM security_audit_events ORDER BY id").toString();
        assertThat(events).contains("INITIAL_CREATE", "PRIVILEGED_ACCESS", "LAST_SUPER_ADMIN", "SUCCESS", "REJECTED", "user:"+id);
        assertThat(events).doesNotContain(rawSecret, owner.getPasswordHash(), token, owner.getEmail());
        assertThat(jdbc.queryForList("SELECT occurred_at FROM security_audit_events WHERE occurred_at IS NULL")).isEmpty();
    }

    @Test void auditFailureRollsBackAccountGrantAndBootstrapMarker() {
        SecurityAudit broken = mock(SecurityAudit.class);
        doThrow(new IllegalStateException("synthetic unavailable audit")).when(broken).append(any(), any(), any(), any());
        var result = service(true, true, broken).execute(INITIAL_CREATE, email(), null, secret());
        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(users.count()).isZero(); assertThat(grants.count()).isZero();
        assertThat(jdbc.queryForList("SELECT initialized_at FROM security_provisioning_state").getFirst().get("initialized_at")).isNull();
        verify(broken).failedIndependently(Operation.INITIAL_CREATE, null);
    }

    @Test void operatorRevocationInvalidatesPreviouslyIssuedJwtAndDoesNotPromoteLegacyAdmin() {
        char[] password = secret(); String raw = new String(password);
        long id = enabled.execute(INITIAL_CREATE, email(), null, password).targetUserId();
        User owner = users.findById(id).orElseThrow(); String token = login(owner, raw);
        assertThat(getIdentity(token).getStatusCode()).isEqualTo(HttpStatus.OK);
        enabled.execute(RECOVER_CREATE, email(), null, secret());
        assertThat(enabled.execute(REVOKE, owner.getEmail(), id, null).outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(getIdentity(token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        owner.changeRole(UserRole.ADMIN); users.saveAndFlush(owner);
        assertThat(getIdentity(login(owner, raw)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_events WHERE reason='ACCESS_DENIED'", Long.class)).isEqualTo(2);
        var headers = new HttpHeaders(); headers.setBearerAuth(token);
        assertThat(http.exchange("/api/v1/super-admin/bootstrap", HttpMethod.POST,
                new HttpEntity<>(Map.of("password", raw), headers), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // A revoked legacy ADMIN can still be disabled to remove its independent base-role access.
        assertThat(enabled.execute(DISABLE, owner.getEmail(), id, null).outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(getIdentity(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test void evenSuperAdminHasNoAuditMutationEndpoint() {
        char[] password = secret(); String raw = new String(password);
        long id = enabled.execute(INITIAL_CREATE, email(), null, password).targetUserId();
        String token = login(users.findById(id).orElseThrow(), raw);
        var before = jdbc.queryForList("SELECT * FROM security_audit_events ORDER BY id");
        var headers = new HttpHeaders(); headers.setBearerAuth(token);
        for (String path : List.of("/api/v1/super-admin/audit", "/api/v1/security-audit-events")) {
            assertThat(http.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), String.class)
                    .getStatusCode().is4xxClientError()).isTrue();
            assertThat(http.exchange(path, HttpMethod.PUT, new HttpEntity<>(Map.of("outcome", "SUCCESS"), headers), String.class)
                    .getStatusCode().is4xxClientError()).isTrue();
        }
        assertThat(jdbc.queryForList("SELECT * FROM security_audit_events ORDER BY id")).containsAll(before);
    }

    @Test void assistedRecoveryChangesLoginPasswordAndPreservesOwnerIdentityAndGrant() {
        String oldPassword = new String(secret());
        var created = enabled.execute(INITIAL_CREATE, com.brandista.operator.OwnerAccountInspection.EMAIL,
                null, oldPassword.toCharArray());
        User owner = users.findById(created.targetUserId()).orElseThrow();
        String token = login(owner, oldPassword);
        assertThat(getIdentity(token).getStatusCode()).isEqualTo(HttpStatus.OK);
        String newPassword = new String(secret());
        assertThat(enabled.execute(RESET_PASSWORD, owner.getEmail(), owner.getId(), newPassword.toCharArray()).outcome())
                .isEqualTo(Outcome.SUCCESS);
        assertThat(http.postForEntity("/api/v1/auth/login", Map.of("email", owner.getEmail(),
                "password", oldPassword), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getIdentity(login(owner, newPassword)).getStatusCode()).isEqualTo(HttpStatus.OK);
        var state = com.brandista.operator.OwnerAccountInspection.inspect(jdbc);
        assertThat(state.ready()).isTrue();
        assertThat(state.id()).isEqualTo(owner.getId());
        assertThat(state.baseRole()).isEqualTo("CUSTOMER");
        assertThat(state.toString()).doesNotContain(oldPassword, newPassword, owner.getPasswordHash(), token);
        // Existing JWT behavior is explicitly documented; a password reset alone does not revoke sessions.
        assertThat(getIdentity(token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void persistedInspectionRejectsMissingDisabledRevokedAndNonBcryptOwner() {
        assertThat(com.brandista.operator.OwnerAccountInspection.inspect(jdbc)).isNull();
        long id = enabled.execute(INITIAL_CREATE, com.brandista.operator.OwnerAccountInspection.EMAIL,
                null, secret()).targetUserId();
        assertThat(com.brandista.operator.OwnerAccountInspection.inspect(jdbc).ready()).isTrue();
        jdbc.update("UPDATE users SET password_hash='invalid-synthetic-hash' WHERE id=?", id);
        assertThat(com.brandista.operator.OwnerAccountInspection.inspect(jdbc).ready()).isFalse();
        jdbc.update("UPDATE users SET password_hash=?, status='DISABLED' WHERE id=?", passwords.encode(new String(secret())), id);
        assertThat(com.brandista.operator.OwnerAccountInspection.inspect(jdbc).ready()).isFalse();
        jdbc.update("UPDATE users SET status='ENABLED' WHERE id=?", id);
        jdbc.update("UPDATE user_platform_grants SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=?", id);
        assertThat(com.brandista.operator.OwnerAccountInspection.inspect(jdbc).ready()).isFalse();
    }

    private OperatorProvisioning service(boolean on, boolean recovery, SecurityAudit writer) {
        return new OperatorProvisioning(jdbc, invariant, writer, passwords, validator, transactions, on, recovery);
    }
    private OperatorProvisioning.Result create() { return enabled.execute(INITIAL_CREATE, email(), null, secret()); }
    private String email() { return UUID.randomUUID()+"@example.invalid"; }
    private char[] secret() { return (UUID.randomUUID()+"-Synthetic!").toCharArray(); }
    private User user(AccountStatus status) {
        return users.saveAndFlush(new User(email(), passwords.encode(new String(secret())), UserRole.CUSTOMER, status));
    }
    private String login(User user, String password) {
        var response = http.postForEntity("/api/v1/auth/login", Map.of("email", user.getEmail(), "password", password), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().path("accessToken").asText();
    }
    private ResponseEntity<String> getIdentity(String token) {
        var headers = new HttpHeaders(); headers.setBearerAuth(token);
        return http.exchange("/api/v1/super-admin/identity", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
    private <T> List<T> concurrent(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<T> a = () -> { ready.countDown(); start.await(); return first.call(); };
            Callable<T> b = () -> { ready.countDown(); start.await(); return second.call(); };
            var fa = pool.submit(a); var fb = pool.submit(b);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
        }
    }
}
