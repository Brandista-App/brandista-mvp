package com.brandista.brandista.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** Durable reference-only outbox. No raw or encrypted token is stored in the database. */
@Component
public class MerchantInvitationDelivery {
    @Configuration @EnableScheduling static class Scheduling {}
    private final JdbcTemplate jdbc;
    private final MerchantEmailProvider provider;
    private final InvitationTokens tokens;
    private final TransactionTemplate tx;
    private final boolean enabled;
    public MerchantInvitationDelivery(JdbcTemplate jdbc,MerchantEmailProvider provider,InvitationTokens tokens,PlatformTransactionManager manager,
        @Value("${brandista.invitations.email-enabled:false}") boolean enabled){
        this.jdbc=jdbc;this.provider=provider;this.tokens=tokens;this.tx=new TransactionTemplate(manager);this.enabled=enabled;
        if(enabled&&!tokens.configured())throw new IllegalStateException("Email delivery requires the invitation token key.");
    }
    public boolean enabled(){return enabled;}
    public void enqueue(long id){jdbc.update("INSERT INTO merchant_invitation_delivery(invitation_id,state,next_attempt_at,updated_at) VALUES(?,'QUEUED',?,?)",id,now(),now());}
    @Scheduled(initialDelayString="${brandista.invitations.initial-delay-ms:10000}",fixedDelayString="${brandista.invitations.dispatch-delay-ms:10000}")
    public void dispatch(){
        if(!enabled)return;
        // Network requests never hold a database transaction; leases permit safe recovery after restart.
        try {process();}
        catch(RuntimeException unavailable){/* Safe retry on the next run; never log secret-bearing provider responses. */}
    }
    void process(){for(int n=0;n<20;n++){Map<String,Object> row=tx.execute(s->claim());if(row==null)break;if(!row.isEmpty())send(row);}}
    private Map<String,Object> claim(){
        var rows=jdbc.queryForList("SELECT d.*,i.token_hash,i.delivery_nonce,i.recipient_email,i.expires_at,i.revoked_at,i.accepted_at,b.name AS brand_name FROM merchant_invitation_delivery d JOIN brand_admin_invitations i ON i.id=d.invitation_id JOIN brands b ON b.id=i.brand_id WHERE d.next_attempt_at<=? AND (d.state IN ('QUEUED','RETRY') OR (d.state='SENDING' AND d.lease_until<=?)) ORDER BY d.invitation_id LIMIT 1 FOR UPDATE",now(),now());
        if(rows.isEmpty())return null;
        var row=rows.getFirst();long id=((Number)row.get("invitation_id")).longValue();
        LocalDateTime first=time(row.get("first_attempt_at"));
        if(row.get("revoked_at")!=null||row.get("accepted_at")!=null||!time(row.get("expires_at")).isAfter(now())){finish(id,"CANCELLED",null);return Map.of();}
        // Provider idempotency lasts 24h. Never retry an ambiguous send outside that window.
        if(first!=null&&!first.plusHours(23).isAfter(now())){finish(id,"FAILED","RETRY_WINDOW_EXPIRED");return Map.of();}
        jdbc.update("UPDATE merchant_invitation_delivery SET state='SENDING',attempts=attempts+1,first_attempt_at=COALESCE(first_attempt_at,?),lease_until=?,updated_at=? WHERE invitation_id=?",now(),now().plusMinutes(2),now(),id);
        return row;
    }
    private void send(Map<String,Object> row){
        long id=((Number)row.get("invitation_id")).longValue();
        try {
            String token=tokens.token((String)row.get("delivery_nonce"));
            if(!InvitationTokens.hash(token).equals(row.get("token_hash"))){finish(id,"FAILED","TOKEN_KEY_CHANGED");return;}
            String providerId=provider.send("merchant-invitation/"+row.get("delivery_nonce"),(String)row.get("recipient_email"),(String)row.get("brand_name"),token,time(row.get("expires_at")).toString());
            jdbc.update("UPDATE merchant_invitation_delivery SET state='PROVIDER_ACCEPTED',provider_id=?,error_code=NULL,lease_until=NULL,next_attempt_at=?,updated_at=? WHERE invitation_id=?",providerId,now().plusMinutes(1),now(),id);
        }catch(RuntimeException unavailable){
            int attempts=((Number)row.get("attempts")).intValue()+1;
            jdbc.update("UPDATE merchant_invitation_delivery SET state=?,error_code='PROVIDER_UNAVAILABLE',lease_until=NULL,next_attempt_at=?,updated_at=? WHERE invitation_id=?",attempts>=6?"FAILED":"RETRY",now().plusSeconds(Math.min(900,30L<<attempts)),now(),id);
        }
    }
    @Scheduled(initialDelayString="${brandista.invitations.initial-delay-ms:10000}",fixedDelayString="${brandista.invitations.poll-delay-ms:60000}")
    public void poll(){
        if(!enabled)return;
        try {for(var row:jdbc.queryForList("SELECT invitation_id,provider_id FROM merchant_invitation_delivery WHERE state IN ('PROVIDER_ACCEPTED','DELIVERED') AND next_attempt_at<=? AND first_attempt_at>? ORDER BY next_attempt_at LIMIT 10",now(),now().minusDays(7))){
            long id=((Number)row.get("invitation_id")).longValue();
            try {String status=provider.status((String)row.get("provider_id"));
                jdbc.update("UPDATE merchant_invitation_delivery SET state=?,error_code=?,next_attempt_at=?,updated_at=? WHERE invitation_id=?",status,status.equals("FAILED")?"PROVIDER_DELIVERY_FAILED":null,now().plusMinutes(5),now(),id);
            }catch(RuntimeException unavailable){jdbc.update("UPDATE merchant_invitation_delivery SET next_attempt_at=? WHERE invitation_id=?",now().plusMinutes(5),id);}
        }}catch(RuntimeException unavailable){/* Keep provider acceptance distinct from actual delivery. */}
    }
    private void finish(long id,String state,String error){jdbc.update("UPDATE merchant_invitation_delivery SET state=?,error_code=?,lease_until=NULL,updated_at=? WHERE invitation_id=?",state,error,now(),id);}
    private static LocalDateTime now(){return LocalDateTime.now(Clock.systemUTC());}
    private static LocalDateTime time(Object value){return value==null?null:value instanceof java.sql.Timestamp t?t.toLocalDateTime():(LocalDateTime)value;}
}
