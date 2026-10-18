package com.brandista.brandista.onboarding;

import com.brandista.brandista.privilege.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import java.time.*;
import static com.brandista.brandista.privilege.SecurityAudit.*;

/** Database-backed account limiter, shared across instances. Failed attempts consume quota. */
@Component
public class InvitationRateLimiter {
    private final JdbcTemplate jdbc;
    private final SecurityInvariant invariant;
    private final SecurityAudit audit;
    private final TransactionTemplate transaction;
    private final Duration window;
    private final int maximum;
    public InvitationRateLimiter(JdbcTemplate jdbc,SecurityInvariant invariant,SecurityAudit audit,PlatformTransactionManager manager,
            @Value("${brandista.invitations.acceptance-window:PT1M}") Duration window,
            @Value("${brandista.invitations.acceptance-attempts:10}") int maximum) {
        this.jdbc=jdbc; this.invariant=invariant; this.audit=audit; this.window=window; this.maximum=maximum;
        if(window.compareTo(Duration.ofSeconds(10))<0 || window.compareTo(Duration.ofHours(1))>0 || maximum<1 || maximum>100)
            throw new IllegalArgumentException("Invitation acceptance rate configuration is invalid.");
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public boolean consume(long user) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            invariant.lock();
            LocalDateTime now=LocalDateTime.now(Clock.systemUTC());
            var rows=jdbc.queryForList("SELECT window_started_at,attempt_count FROM brand_invitation_accept_limits WHERE user_id=? FOR UPDATE",user);
            if(rows.isEmpty()) jdbc.update("INSERT INTO brand_invitation_accept_limits(user_id,window_started_at,attempt_count) VALUES(?,?,1)",user,now);
            else {
                Object stored=rows.getFirst().get("window_started_at");
                LocalDateTime started=stored instanceof java.sql.Timestamp timestamp?timestamp.toLocalDateTime():(LocalDateTime)stored;
                int attempts=((Number)rows.getFirst().get("attempt_count")).intValue();
                if(!started.plus(window).isAfter(now))
                    jdbc.update("UPDATE brand_invitation_accept_limits SET window_started_at=?,attempt_count=1 WHERE user_id=?",now,user);
                else if(attempts>=maximum) {
                    audit.onboarding(Operation.INVITATION_ACCEPT_ATTEMPT,user,null,null,null,Outcome.REJECTED,Reason.RATE_LIMITED);
                    return false;
                } else jdbc.update("UPDATE brand_invitation_accept_limits SET attempt_count=attempt_count+1 WHERE user_id=?",user);
            }
            return true;
        }));
    }
}
