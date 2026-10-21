package com.brandista.brandista.privilege;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Insert-only API. Closed enums deliberately prohibit arbitrary metadata or secrets. */
@Component
public class SecurityAudit {
    public enum Operation {
        INITIAL_CREATE, INITIAL_GRANT, RECOVER_CREATE, RECOVER_GRANT, RESET_PASSWORD,
        REVOKE, DISABLE, GRANT_CREATED, GRANT_CHANGED, GRANT_REMOVED,
        ACCOUNT_SECURITY_CHANGED, ACCOUNT_ROLE_CHANGED, ACCOUNT_STATUS_CHANGED,
        ACCOUNT_PASSWORD_CHANGED, ACCOUNT_REMOVED, PRIVILEGED_ACCESS,
        BRAND_MEMBERSHIP_GRANTED, BRAND_MEMBERSHIP_REVOKED, APPLICATION_REVIEWED,
        INVITATION_CREATED, INVITATION_REVOKED, INVITATION_REISSUED, INVITATION_ACCEPTED,
        INVITATION_ACCEPT_ATTEMPT, BRAND_ACTIVATED, ORDINARY_ACCOUNT_DISABLED, ORDINARY_ACCOUNT_RESTORED
    }
    public enum Outcome { SUCCESS, REJECTED, FAILED }
    public enum Reason {
        COMPLETED, DISABLED, RECOVERY_DISABLED, ALREADY_INITIALIZED, IDENTITY_EXISTS,
        IDENTITY_MISMATCH, ACCOUNT_DISABLED, INVALID_INPUT, LAST_SUPER_ADMIN,
        NOT_FOUND, ALREADY_GRANTED, NOT_GRANTED, STORAGE_FAILURE, ACCESS_DENIED,
        TOKEN_REJECTED, RATE_LIMITED, ONBOARDING_INCOMPLETE, DELIVERY_UNAVAILABLE,
        PROTECTED_ACCOUNT, STATE_CONFLICT
    }

    private final JdbcTemplate jdbc;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    public SecurityAudit(JdbcTemplate jdbc, ObjectProvider<PlatformTransactionManager> transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public void append(Operation operation, Long target, Outcome outcome, Reason reason) {
        append(operation, target, null, outcome, reason);
    }

    public void append(Operation operation, Long target, Long brand, Outcome outcome, Reason reason) {
        jdbc.update("INSERT INTO security_audit_events(actor,operation,target_user_id,target_brand_id,occurred_at,outcome,reason) "
                        + "VALUES (?,?,?,?,?,?,?)",
                actor(), operation.name(), target, brand, java.time.LocalDateTime.now(java.time.Clock.systemUTC()),
                outcome.name(), reason.name());
    }

    public void rejectedMembership(Operation operation, Long target, Long brand, Reason reason) {
        var independent = new TransactionTemplate(transactions.getObject());
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.executeWithoutResult(status -> append(operation, target, brand, Outcome.REJECTED, reason));
    }

    public void onboarding(Operation operation, Long user, Long brand, Long invitation, Long application,
                           Outcome outcome, Reason reason) {
        jdbc.update("INSERT INTO security_audit_events(actor,operation,target_user_id,target_brand_id,target_invitation_id,"
                        + "target_application_id,occurred_at,outcome,reason) VALUES(?,?,?,?,?,?,?,?,?)",
                actor(),operation.name(),user,brand,invitation,application,
                java.time.LocalDateTime.now(java.time.Clock.systemUTC()),outcome.name(),reason.name());
    }

    public void rejectedOnboarding(Operation operation, Long user, Long brand, Long invitation, Long application, Reason reason) {
        var independent=new TransactionTemplate(transactions.getObject());
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.executeWithoutResult(status -> onboarding(operation,user,brand,invitation,application,Outcome.REJECTED,reason));
    }

    public void rejectedIndependently(Operation operation, Long target, Reason reason) {
        appendIndependently(operation, target, Outcome.REJECTED, reason);
    }

    public void failedIndependently(Operation operation, Long target) {
        appendIndependently(operation, target, Outcome.FAILED, Reason.STORAGE_FAILURE);
    }

    private void appendIndependently(Operation operation, Long target, Outcome outcome, Reason reason) {
        var independent = new TransactionTemplate(transactions.getObject());
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.executeWithoutResult(status -> append(operation, target, outcome, reason));
    }

    private String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()
                && token.getToken().getSubject() != null && token.getToken().getSubject().matches("[1-9][0-9]{0,18}")) {
            return "user:" + token.getToken().getSubject();
        }
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() != null) return "anonymous";
        // OS identity is supplied by the trusted operator environment, never HTTP input.
        String osUser = System.getProperty("user.name", "unknown").replaceAll("[^a-zA-Z0-9_.@-]", "_");
        return "process:" + osUser.substring(0, Math.min(140, osUser.length()));
    }
}
