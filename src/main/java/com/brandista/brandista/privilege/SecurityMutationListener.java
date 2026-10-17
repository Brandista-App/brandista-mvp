package com.brandista.brandista.privilege;

import com.brandista.brandista.user.AccountStatus;
import com.brandista.brandista.user.User;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.brandista.brandista.privilege.SecurityAudit.*;

/** Guards ordinary JPA entity writes as well as the controlled operator service.
 * Bulk JPQL/native SQL is not an authorized account/grant mutation interface. */
@Component
public class SecurityMutationListener {
    private final JdbcTemplate jdbc;
    private final SecurityInvariant invariant;
    private final SecurityAudit audit;

    public SecurityMutationListener(JdbcTemplate jdbc, SecurityInvariant invariant, SecurityAudit audit) {
        this.jdbc = jdbc;
        this.invariant = invariant;
        this.audit = audit;
    }

    @PrePersist
    public void created(Object entity) {
        if (entity instanceof PlatformGrant grant) {
            invariant.lock();
            audit.append(Operation.GRANT_CREATED, grant.subjectId(), Outcome.SUCCESS, Reason.COMPLETED);
        }
    }

    @PreUpdate
    public void updated(Object entity) { check(entity, false); }

    @PreRemove
    public void removed(Object entity) { check(entity, true); }

    private void check(Object entity, boolean removing) {
        if (entity instanceof PlatformGrant grant) {
            invariant.lock();
            protect(grant.subjectId(), removing || grant.getRevokedAt() != null,
                    removing ? Operation.GRANT_REMOVED : Operation.GRANT_CHANGED);
            audit.append(removing ? Operation.GRANT_REMOVED : Operation.GRANT_CHANGED,
                    grant.subjectId(), Outcome.SUCCESS, Reason.COMPLETED);
        } else if (entity instanceof User user) {
            // Skip ordinary customer profile/address updates without serializing commerce.
            var rows = jdbc.queryForList("SELECT role,status,password_hash FROM users WHERE id=?", user.getId());
            if (rows.isEmpty()) return;
            var old = rows.getFirst();
            boolean roleChanged = !user.getRole().name().equals(old.get("role"));
            boolean statusChanged = !user.getStatus().name().equals(old.get("status"));
            boolean passwordChanged = !user.getPasswordHash().equals(old.get("password_hash"));
            boolean securityChanged = roleChanged || statusChanged || passwordChanged;
            if (!removing && !securityChanged) return;
            invariant.lock();
            protect(user.getId(), removing || user.getStatus() != AccountStatus.ENABLED,
                    removing ? Operation.ACCOUNT_REMOVED : Operation.ACCOUNT_SECURITY_CHANGED);
            if (removing) audit.append(Operation.ACCOUNT_REMOVED, user.getId(), Outcome.SUCCESS, Reason.COMPLETED);
            else {
                if (roleChanged) audit.append(Operation.ACCOUNT_ROLE_CHANGED, user.getId(), Outcome.SUCCESS, Reason.COMPLETED);
                if (statusChanged) audit.append(Operation.ACCOUNT_STATUS_CHANGED, user.getId(), Outcome.SUCCESS, Reason.COMPLETED);
                if (passwordChanged) audit.append(Operation.ACCOUNT_PASSWORD_CHANGED, user.getId(), Outcome.SUCCESS, Reason.COMPLETED);
            }
        }
    }

    private void protect(long target, boolean removesAccess, Operation operation) {
        if (removesAccess && invariant.wouldRemoveLast(target)) {
            audit.rejectedIndependently(operation, target, Reason.LAST_SUPER_ADMIN);
            throw new IllegalStateException("Cannot remove or disable the last active super-admin.");
        }
        if (removesAccess) invariant.removalScheduled(target);
    }
}
