package com.brandista.brandista.onboarding;

import com.brandista.brandista.merchant.BrandAccess;
import com.brandista.brandista.privilege.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.brandista.brandista.privilege.SecurityAudit.*;
import static org.springframework.http.HttpStatus.*;

@Service
public class BrandInvitationService {
    public record Status(long id,long applicationId,long brandId,String recipientEmail,String status,
                         LocalDateTime createdAt,LocalDateTime expiresAt,Long replacesInvitationId,Long acceptedUserId) { }
    public record Page(List<Status> content,int page,int size,long totalElements) { }
    public record State(long applicationId,String applicationStatus,Long brandId,String brandStatus,boolean activationEligible) { }
    public record Accepted(long invitationId,long brandId,long membershipId) { }
    private record Application(long id,Long brand,String email,String status) { }
    private record Invitation(long id,long application,long brand,String email,LocalDateTime created,LocalDateTime expires,
                              LocalDateTime revoked,LocalDateTime accepted,Long expectedVersion,Long replaces,Long acceptedUser) {
        String state() { return accepted!=null?"ACCEPTED":revoked!=null?"REVOKED":!expires.isAfter(now())?"EXPIRED":"PENDING"; }
        Status safe() { return new Status(id,application,brand,email,state(),created,expires,replaces,acceptedUser); }
    }
    private final JdbcTemplate jdbc;
    private final BrandAccess access;
    private final OnboardingPolicy policy;
    private final SecurityInvariant invariant;
    private final SecurityAudit audit;
    private final InvitationDelivery delivery;
    private final InvitationRateLimiter limiter;
    private final TransactionTemplate acceptance;
    private final Duration ttl;
    private final SecureRandom random=new SecureRandom();

    public BrandInvitationService(JdbcTemplate jdbc,BrandAccess access,OnboardingPolicy policy,SecurityInvariant invariant,
            SecurityAudit audit,InvitationDelivery delivery,InvitationRateLimiter limiter,PlatformTransactionManager manager,
            @Value("${brandista.invitations.ttl:PT24H}") Duration ttl) {
        this.jdbc=jdbc; this.access=access; this.policy=policy; this.invariant=invariant; this.audit=audit;
        this.delivery=delivery; this.limiter=limiter; this.ttl=ttl;
        if(ttl.compareTo(Duration.ofMinutes(5))<0 || ttl.compareTo(Duration.ofDays(7))>0)
            throw new IllegalArgumentException("Invitation TTL must be between five minutes and seven days.");
        acceptance=new TransactionTemplate(manager);
        acceptance.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        acceptance.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Status create(long applicationId) {
        policy.superAdmin(Operation.INVITATION_CREATED,applicationId,null,null);
        return issue(approved(applicationId),null);
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Status reissue(long id) {
        policy.superAdmin(Operation.INVITATION_REISSUED,null,null,id);
        Invitation previous=find(id);
        if(previous.accepted!=null || previous.revoked!=null) throw conflict(Operation.INVITATION_REISSUED,previous,"Invitation cannot be reissued.");
        jdbc.update("UPDATE brand_admin_invitations SET revoked_at=? WHERE id=?",now(),id);
        audit.onboarding(Operation.INVITATION_REVOKED,null,previous.brand,id,previous.application,Outcome.SUCCESS,Reason.COMPLETED);
        Status next=issue(approved(previous.application),id);
        audit.onboarding(Operation.INVITATION_REISSUED,null,next.brandId(),next.id(),next.applicationId(),Outcome.SUCCESS,Reason.COMPLETED);
        return next;
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Status revoke(long id) {
        policy.superAdmin(Operation.INVITATION_REVOKED,null,null,id);
        Invitation invitation=find(id);
        if(invitation.accepted!=null || invitation.revoked!=null) throw conflict(Operation.INVITATION_REVOKED,invitation,"Invitation is not pending.");
        jdbc.update("UPDATE brand_admin_invitations SET revoked_at=? WHERE id=?",now(),id);
        audit.onboarding(Operation.INVITATION_REVOKED,null,invitation.brand,id,invitation.application,Outcome.SUCCESS,Reason.COMPLETED);
        return find(id).safe();
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Page list(long applicationId,int page,int size) {
        policy.superAdmin(Operation.PRIVILEGED_ACCESS,applicationId,null,null);
        application(applicationId);
        if(page<0 || size<1 || size>100 || page>100000) throw new OnboardingFailure(BAD_REQUEST,"Invalid pagination.");
        var rows=jdbc.query("SELECT * FROM brand_admin_invitations WHERE application_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                (row,index)->map(row),applicationId,size,(long)page*size);
        return new Page(rows.stream().map(Invitation::safe).toList(),page,size,
                jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_invitations WHERE application_id=?",Long.class,applicationId));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public State state(long applicationId) {
        policy.superAdmin(Operation.PRIVILEGED_ACCESS,applicationId,null,null);
        Application app=application(applicationId);
        String brandStatus=app.brand==null?null:jdbc.queryForObject("SELECT status FROM brands WHERE id=?",String.class,app.brand);
        return new State(app.id,app.status,app.brand,brandStatus,app.brand!=null && app.status.equals("APPROVED") && policy.eligible(app.brand));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public State activate(long applicationId) {
        policy.superAdmin(Operation.BRAND_ACTIVATED,applicationId,null,null);
        Application app=approved(applicationId);
        policy.requireActivation(app.id,app.brand);
        jdbc.update("UPDATE brands SET status='ACTIVE',updated_at=? WHERE id=?",now(),app.brand);
        audit.onboarding(Operation.BRAND_ACTIVATED,null,app.brand,null,app.id,Outcome.SUCCESS,Reason.COMPLETED);
        return state(applicationId);
    }

    @PreAuthorize("isAuthenticated()")
    public Accepted accept(String rawToken) {
        long user=access.currentUser();
        if(!limiter.consume(user)) throw new OnboardingFailure(TOO_MANY_REQUESTS,"Invitation acceptance is temporarily limited.");
        // Return rejection rather than throwing inside the transaction, so its audit commits.
        Accepted result=acceptance.execute(status -> acceptWithin(user,rawToken));
        if(result==null) throw new OnboardingFailure(BAD_REQUEST,"Invitation could not be accepted.");
        return result;
    }

    private Accepted acceptWithin(long user,String token) {
        invariant.lock();
        access.currentUser(); // Account status may have changed after the quota transaction.
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) return rejected(user,null);
        var matches=jdbc.query("SELECT * FROM brand_admin_invitations WHERE token_hash=? FOR UPDATE",(row,index)->map(row),hash(token));
        if(matches.isEmpty()) return rejected(user,null);
        Invitation invitation=matches.getFirst();
        String email=jdbc.queryForObject("SELECT email FROM users WHERE id=?",String.class,user);
        if(!invitation.state().equals("PENDING") || !invitation.email.equals(email)
                || jdbc.queryForObject("SELECT COUNT(*) FROM brand_applications WHERE id=? AND brand_id=? AND status='APPROVED'",Long.class,invitation.application,invitation.brand)!=1)
            return rejected(user,invitation);
        var memberships=jdbc.queryForList("SELECT id,version,revoked_at FROM brand_admin_memberships WHERE user_id=? AND brand_id=? FOR UPDATE",user,invitation.brand);
        Long currentVersion=memberships.isEmpty()?null:((Number)memberships.getFirst().get("version")).longValue();
        if(!Objects.equals(currentVersion,invitation.expectedVersion)) return rejected(user,invitation);
        boolean changed=memberships.isEmpty() || memberships.getFirst().get("revoked_at")!=null;
        long version=currentVersion==null?0:changed?currentVersion+1:currentVersion;
        if(memberships.isEmpty()) jdbc.update("INSERT INTO brand_admin_memberships(user_id,brand_id,created_at) VALUES(?,?,?)",user,invitation.brand,now());
        else if(changed) jdbc.update("UPDATE brand_admin_memberships SET revoked_at=NULL,version=version+1 WHERE user_id=? AND brand_id=?",user,invitation.brand);
        long membership=jdbc.queryForObject("SELECT id FROM brand_admin_memberships WHERE user_id=? AND brand_id=?",Long.class,user,invitation.brand);
        jdbc.update("UPDATE brand_admin_invitations SET accepted_at=?,accepted_user_id=?,membership_id=?,accepted_membership_version=? WHERE id=?",
                now(),user,membership,version,invitation.id);
        if(changed) audit.onboarding(Operation.BRAND_MEMBERSHIP_GRANTED,user,invitation.brand,invitation.id,invitation.application,Outcome.SUCCESS,Reason.COMPLETED);
        audit.onboarding(Operation.INVITATION_ACCEPTED,user,invitation.brand,invitation.id,invitation.application,Outcome.SUCCESS,Reason.COMPLETED);
        return new Accepted(invitation.id,invitation.brand,membership);
    }

    private Status issue(Application app,Long replaces) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_invitations WHERE brand_id=? AND recipient_email=? AND accepted_at IS NULL "
                + "AND revoked_at IS NULL AND expires_at>?",Long.class,app.brand,app.email,now())>0) {
            audit.rejectedOnboarding(Operation.INVITATION_CREATED,null,app.brand,null,app.id,Reason.ALREADY_GRANTED);
            throw new OnboardingFailure(CONFLICT,"A pending invitation already exists. Reissue it to rotate delivery.");
        }
        var versions=jdbc.queryForList("SELECT m.version FROM brand_admin_memberships m JOIN users u ON u.id=m.user_id WHERE u.email=? AND m.brand_id=?",Long.class,app.email,app.brand);
        Long expected=versions.isEmpty()?null:versions.getFirst();
        byte[] entropy=new byte[32]; random.nextBytes(entropy);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy); Arrays.fill(entropy,(byte)0);
        String digest=hash(token);
        LocalDateTime created=now();
        jdbc.update("INSERT INTO brand_admin_invitations(application_id,brand_id,recipient_email,token_hash,created_by,created_at,expires_at,expected_membership_version,replaces_invitation_id) "
                + "VALUES(?,?,?,?,?,?,?,?,?)",app.id,app.brand,app.email,digest,access.currentUser(),created,created.plus(ttl),expected,replaces);
        long id=jdbc.queryForObject("SELECT id FROM brand_admin_invitations WHERE token_hash=?",Long.class,digest);
        try { delivery.deliver(id,app.email,token); }
        catch(RuntimeException unavailable) {
            audit.rejectedOnboarding(Operation.INVITATION_CREATED,null,app.brand,null,app.id,Reason.DELIVERY_UNAVAILABLE);
            throw new OnboardingFailure(SERVICE_UNAVAILABLE,"Invitation delivery is unavailable.");
        }
        audit.onboarding(Operation.INVITATION_CREATED,null,app.brand,id,app.id,Outcome.SUCCESS,Reason.COMPLETED);
        return find(id).safe();
    }

    private Accepted rejected(long user,Invitation invitation) {
        audit.onboarding(Operation.INVITATION_ACCEPT_ATTEMPT,user,invitation==null?null:invitation.brand,
                invitation==null?null:invitation.id,invitation==null?null:invitation.application,Outcome.REJECTED,Reason.TOKEN_REJECTED);
        return null;
    }
    private OnboardingFailure conflict(Operation operation,Invitation invitation,String message) {
        audit.rejectedOnboarding(operation,null,invitation.brand,invitation.id,invitation.application,Reason.INVALID_INPUT);
        return new OnboardingFailure(CONFLICT,message);
    }
    private Application approved(long id) {
        Application app=application(id);
        if(!app.status.equals("APPROVED") || app.brand==null) {
            audit.rejectedOnboarding(Operation.INVITATION_CREATED,null,app.brand,null,id,Reason.INVALID_INPUT);
            throw new OnboardingFailure(CONFLICT,"Application is not approved for onboarding.");
        }
        return app;
    }
    private Application application(long id) {
        var rows=jdbc.query("SELECT id,brand_id,contact_email,status FROM brand_applications WHERE id=?",
                (row,index)->new Application(row.getLong(1),row.getObject(2,Long.class),row.getString(3),row.getString(4)),id);
        if(rows.isEmpty()) {
            audit.rejectedOnboarding(Operation.PRIVILEGED_ACCESS,null,null,null,id,Reason.NOT_FOUND);
            throw new OnboardingFailure(NOT_FOUND,"Application not found.");
        }
        return rows.getFirst();
    }
    private Invitation find(long id) {
        var rows=jdbc.query("SELECT * FROM brand_admin_invitations WHERE id=? FOR UPDATE",(row,index)->map(row),id);
        if(rows.isEmpty()) {
            audit.rejectedOnboarding(Operation.PRIVILEGED_ACCESS,null,null,id,null,Reason.NOT_FOUND);
            throw new OnboardingFailure(NOT_FOUND,"Invitation not found.");
        }
        return rows.getFirst();
    }
    private Invitation map(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Invitation(row.getLong("id"),row.getLong("application_id"),row.getLong("brand_id"),row.getString("recipient_email"),
                timestamp(row,"created_at"),timestamp(row,"expires_at"),timestamp(row,"revoked_at"),timestamp(row,"accepted_at"),
                row.getObject("expected_membership_version",Long.class),row.getObject("replaces_invitation_id",Long.class),row.getObject("accepted_user_id",Long.class));
    }
    private LocalDateTime timestamp(java.sql.ResultSet row,String column) throws java.sql.SQLException { var value=row.getTimestamp(column); return value==null?null:value.toLocalDateTime(); }
    private static LocalDateTime now() { return LocalDateTime.now(Clock.systemUTC()); }
    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch(NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Invitation hashing unavailable."); }
    }
}
