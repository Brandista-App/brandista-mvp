package com.brandista.brandista.privilege;

import com.brandista.brandista.merchant.BrandAccess;
import com.brandista.brandista.merchant.BrandMembershipService;
import com.brandista.brandista.user.EmailNormalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import static com.brandista.brandista.privilege.SecurityAudit.*;

/** Restricted lifecycle operations; deliberately no platform grant or membership grant API. */
@Service
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AccountLifecycleService {
    public record Result(boolean changed, Reason reason) { }
    private final JdbcTemplate jdbc;
    private final BrandAccess access;
    private final SecurityInvariant invariant;
    private final SecurityAudit audit;
    private final BrandMembershipService memberships;

    public AccountLifecycleService(JdbcTemplate jdbc, BrandAccess access, SecurityInvariant invariant,
                                   SecurityAudit audit, BrandMembershipService memberships) {
        this.jdbc=jdbc; this.access=access; this.invariant=invariant; this.audit=audit; this.memberships=memberships;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED, timeout=30)
    public Result revoke(long userId, String expectedEmail, long brandId) {
        Operation operation=Operation.BRAND_MEMBERSHIP_REVOKED;
        authorize(operation,userId,brandId);
        var failure=verifyIdentity(operation,userId,expectedEmail,brandId);
        if (failure!=null) return failure;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM brands WHERE id=?",Long.class,brandId)!=1)
            return reject(operation,userId,brandId,Reason.NOT_FOUND);
        var result=memberships.revoke(userId,brandId);
        return new Result(result.changed(),result.reason());
    }

    @Transactional(isolation=Isolation.READ_COMMITTED, timeout=30)
    public Result disable(long userId, String expectedEmail) { return status(userId,expectedEmail,false); }

    @Transactional(isolation=Isolation.READ_COMMITTED, timeout=30)
    public Result restore(long userId, String expectedEmail) { return status(userId,expectedEmail,true); }

    private Result status(long userId, String expectedEmail, boolean restoring) {
        Operation operation=restoring?Operation.ORDINARY_ACCOUNT_RESTORED:Operation.ORDINARY_ACCOUNT_DISABLED;
        authorize(operation,userId,null);
        var failure=verifyIdentity(operation,userId,expectedEmail,null);
        if (failure!=null) return failure;
        // Both current and historical custodians, legacy admins and self-targets are excluded.
        // The shared invariant lock also serializes offline grant/recovery and invitation acceptance.
        if (access.currentUser()==userId
                || !"CUSTOMER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id=?",String.class,userId))
                || !jdbc.queryForList("SELECT id FROM user_platform_grants WHERE user_id=? FOR UPDATE",userId).isEmpty())
            return reject(operation,userId,null,Reason.PROTECTED_ACCOUNT);
        String expected=restoring?"DISABLED":"ENABLED";
        String next=restoring?"ENABLED":"DISABLED";
        if (jdbc.update("UPDATE users SET status=?,updated_at=? WHERE id=? AND status=?",next,
                LocalDateTime.now(Clock.systemUTC()),userId,expected)!=1)
            return reject(operation,userId,null,Reason.STATE_CONFLICT);
        audit.append(operation,userId,Outcome.SUCCESS,Reason.COMPLETED);
        return new Result(true,Reason.COMPLETED);
    }

    private void authorize(Operation operation, long userId, Long brandId) {
        invariant.lock();
        try { access.requireSuperAdmin(); }
        catch (AccessDeniedException denied) {
            audit.rejectedMembership(operation,userId,brandId,Reason.ACCESS_DENIED);
            throw denied;
        }
    }

    private Result verifyIdentity(Operation operation, long userId, String expectedEmail, Long brandId) {
        var rows=jdbc.queryForList("SELECT email FROM users WHERE id=? FOR UPDATE",userId);
        if (rows.isEmpty()) return reject(operation,userId,brandId,Reason.NOT_FOUND);
        if (!rows.getFirst().get("email").equals(EmailNormalizer.normalize(expectedEmail)))
            return reject(operation,userId,brandId,Reason.IDENTITY_MISMATCH);
        return null;
    }

    private Result reject(Operation operation, long userId, Long brandId, Reason reason) {
        audit.append(operation,userId,brandId,Outcome.REJECTED,reason);
        return new Result(false,reason);
    }
}
