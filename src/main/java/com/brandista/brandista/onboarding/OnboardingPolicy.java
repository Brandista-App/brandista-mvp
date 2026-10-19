package com.brandista.brandista.onboarding;

import com.brandista.brandista.merchant.BrandAccess;
import com.brandista.brandista.privilege.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import static com.brandista.brandista.privilege.SecurityAudit.*;
import static org.springframework.http.HttpStatus.*;

@Component
public class OnboardingPolicy {
    private final JdbcTemplate jdbc;
    private final BrandAccess access;
    private final SecurityInvariant invariant;
    private final SecurityAudit audit;
    public OnboardingPolicy(JdbcTemplate jdbc,BrandAccess access,SecurityInvariant invariant,SecurityAudit audit) {
        this.jdbc=jdbc; this.access=access; this.invariant=invariant; this.audit=audit;
    }
    public void superAdmin(Operation operation,Long application,Long brand,Long invitation) {
        invariant.lock();
        try { access.requireSuperAdmin(); }
        catch(AccessDeniedException denied) {
            audit.rejectedOnboarding(operation,null,brand,invitation,application,Reason.ACCESS_DENIED);
            throw denied;
        }
    }
    public void reviewer(boolean approval,long application) {
        if(approval) { superAdmin(Operation.APPLICATION_REVIEWED,application,null,null); return; }
        invariant.lock();
        long actor=access.currentUser();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND role='ADMIN'",Long.class,actor)==0)
            superAdmin(Operation.APPLICATION_REVIEWED,application,null,null);
    }
    public boolean eligible(long brand) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_invitations i "
                + "JOIN brand_applications a ON a.id=i.application_id AND a.brand_id=i.brand_id AND a.status='APPROVED' "
                + "JOIN brand_admin_memberships m ON m.id=i.membership_id AND m.user_id=i.accepted_user_id AND m.brand_id=i.brand_id "
                + "JOIN users u ON u.id=m.user_id WHERE i.brand_id=? AND i.accepted_at IS NOT NULL "
                + "AND m.revoked_at IS NULL AND m.version=i.accepted_membership_version AND u.status='ENABLED'",Long.class,brand)>0;
    }
    public void requireActivation(long application,long brand) {
        superAdmin(Operation.BRAND_ACTIVATED,application,brand,null);
        if(!eligible(brand)) {
            audit.rejectedOnboarding(Operation.BRAND_ACTIVATED,null,brand,null,application,Reason.ONBOARDING_INCOMPLETE);
            throw new OnboardingFailure(CONFLICT,"Brand onboarding requirements are not satisfied.");
        }
    }
    /** Guard the existing global brand-update API as well as the new activation API. */
    public void guardGlobalActivation(long brand) {
        var rows=jdbc.queryForList("SELECT id FROM brand_applications WHERE brand_id=? AND status='APPROVED' ORDER BY id",Long.class,brand);
        if(!rows.isEmpty()) {
            requireActivation(rows.getFirst(),brand);
            audit.onboarding(Operation.BRAND_ACTIVATED,null,brand,null,rows.getFirst(),Outcome.SUCCESS,Reason.COMPLETED);
        }
    }
}
