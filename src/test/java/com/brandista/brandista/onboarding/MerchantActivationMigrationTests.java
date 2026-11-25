package com.brandista.brandista.onboarding;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MerchantActivationMigrationTests {
    @Test void v16PreservesExistingApplicationsInvitationsAccountsAndCommerce() throws Exception {
        String url=System.getenv("MERCHANT_TEST_MYSQL_URL");
        DriverManagerDataSource source;
        if(url==null)source=new DriverManagerDataSource("jdbc:h2:mem:merchant-migration-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        else {
            if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/brandista_merchant_verify\\?.*"))throw new IllegalArgumentException("Only disposable local merchant migration schemas are permitted.");
            source=new DriverManagerDataSource(url,"root",System.getenv("MERCHANT_TEST_MYSQL_PASSWORD"));
        }
        Flyway.configure().dataSource(source).target("15").load().migrate();
        try(var connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/brand-membership-upgrade-fixture.sql"));}
        var jdbc=new JdbcTemplate(source);
        jdbc.update("INSERT INTO brand_applications(id,brand_name,contact_name,contact_email,status,brand_id,created_at,updated_at) VALUES(900,'Synthetic approved','Synthetic merchant','customer@example.invalid','APPROVED',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO brand_admin_invitations(application_id,brand_id,recipient_email,token_hash,created_by,created_at,expires_at) VALUES(900,1,'customer@example.invalid',?,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","c".repeat(64));
        var before=snapshot(jdbc);
        assertThat(Flyway.configure().dataSource(source).load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(snapshot(jdbc)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_invitation_delivery",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_registration_limits",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT terms_accepted_at FROM brand_admin_invitations WHERE application_id=900",Object.class)).isNull();
        assertThat(Flyway.configure().dataSource(source).load().migrate().migrationsExecuted).isZero();
    }
    private Map<String,Object> snapshot(JdbcTemplate jdbc){
        var result=new LinkedHashMap<String,Object>();
        for(String table:List.of("users","user_platform_grants","brands","brand_applications","brand_admin_memberships","customer_addresses","carts","cart_items","customer_orders","customer_order_items","products","security_audit_events"))result.put(table,jdbc.queryForList("SELECT * FROM "+table));
        result.put("invitations",jdbc.queryForList("SELECT id,application_id,brand_id,recipient_email,token_hash,created_by,created_at,expires_at,accepted_at,revoked_at FROM brand_admin_invitations"));
        return result;
    }
}
