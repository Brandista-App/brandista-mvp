package com.brandista.brandista.merchant;

import com.brandista.brandista.privilege.SecurityAudit;
import com.brandista.brandista.privilege.SecurityInvariant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import static com.brandista.brandista.privilege.SecurityAudit.*;

/** Secured membership service. PR6B exposes only identity-verified revocation; grants remain internal. */
@Service
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class BrandMembershipService {
    public record Result(boolean changed, Reason reason) { }
    private final JdbcTemplate jdbc;
    private final BrandAccess access;
    private final SecurityInvariant invariant;
    private final SecurityAudit audit;
    public BrandMembershipService(JdbcTemplate jdbc, BrandAccess access, SecurityInvariant invariant, SecurityAudit audit) {
        this.jdbc=jdbc; this.access=access; this.invariant=invariant; this.audit=audit;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Result grant(long userId, long brandId) { return change(userId, brandId, true); }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Result revoke(long userId, long brandId) { return change(userId, brandId, false); }

    private Result change(long user, long brand, boolean granting) {
        Operation operation = granting ? Operation.BRAND_MEMBERSHIP_GRANTED : Operation.BRAND_MEMBERSHIP_REVOKED;
        invariant.lock(); // Same lock as platform-grant revocation; recheck actor after acquiring it.
        try { access.requireSuperAdmin(); }
        catch (AccessDeniedException denied) {
            audit.rejectedMembership(operation, user, brand, Reason.ACCESS_DENIED);
            throw denied;
        }
        if (granting) {
            // Brand rows are approval-controlled by existing global administration; ACTIVE is required.
            if (jdbc.queryForList("SELECT id FROM brands WHERE id=? AND status='ACTIVE' FOR UPDATE", brand).isEmpty())
                return reject(operation, user, brand, Reason.INVALID_INPUT);
            if (jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ENABLED' FOR UPDATE", user).isEmpty())
                return reject(operation, user, brand, Reason.ACCOUNT_DISABLED);
        }
        var rows = jdbc.queryForList("SELECT revoked_at FROM brand_admin_memberships WHERE user_id=? AND brand_id=? FOR UPDATE", user, brand);
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        if (granting) {
            if (rows.isEmpty()) jdbc.update("INSERT INTO brand_admin_memberships(user_id,brand_id,created_at) VALUES (?,?,?)", user, brand, now);
            else if (rows.getFirst().get("revoked_at") == null) return reject(operation, user, brand, Reason.ALREADY_GRANTED);
            else jdbc.update("UPDATE brand_admin_memberships SET revoked_at=NULL,version=version+1 WHERE user_id=? AND brand_id=?", user, brand);
        } else {
            if (rows.isEmpty() || rows.getFirst().get("revoked_at") != null) return reject(operation, user, brand, Reason.NOT_GRANTED);
            jdbc.update("UPDATE brand_admin_memberships SET revoked_at=?,version=version+1 WHERE user_id=? AND brand_id=?", now, user, brand);
        }
        audit.append(operation, user, brand, Outcome.SUCCESS, Reason.COMPLETED);
        return new Result(true, Reason.COMPLETED);
    }

    private Result reject(Operation operation, long user, long brand, Reason reason) {
        audit.append(operation, user, brand, Outcome.REJECTED, reason);
        return new Result(false, reason);
    }
}
