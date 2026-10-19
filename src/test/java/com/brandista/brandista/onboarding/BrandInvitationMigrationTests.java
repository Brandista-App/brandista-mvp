package com.brandista.brandista.onboarding;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BrandInvitationMigrationTests {
    @Test void v13PreservesCommerceApplicationsGrantsMembershipsAndAudits() throws Exception {
        var datasource=new DriverManagerDataSource("jdbc:h2:mem:invitations-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(datasource).target("12").load().migrate();
        try(var connection=datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/brand-membership-upgrade-fixture.sql"));
        }
        var jdbc=new JdbcTemplate(datasource);
        jdbc.update("INSERT INTO brand_admin_memberships(user_id,brand_id,created_at) VALUES(1,1,CURRENT_TIMESTAMP)");
        var before=snapshot(jdbc);
        assertThat(Flyway.configure().dataSource(datasource).load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(snapshot(jdbc)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_invitations",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_invitation_accept_limits",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT target_invitation_id FROM security_audit_events",Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT target_application_id FROM security_audit_events",Long.class)).isNull();
        assertThat(Flyway.configure().dataSource(datasource).load().migrate().migrationsExecuted).isZero();
    }
    private Map<String,Object> snapshot(JdbcTemplate jdbc) {
        var state=new LinkedHashMap<String,Object>();
        for(String table:List.of("users","brands","categories","products","customer_addresses","carts","cart_items",
                "customer_orders","customer_order_items","brand_applications","user_platform_grants","security_provisioning_state","brand_admin_memberships"))
            state.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id"));
        state.put("audit",jdbc.queryForList("SELECT id,actor,operation,target_user_id,target_brand_id,occurred_at,outcome,reason FROM security_audit_events ORDER BY id"));
        return state;
    }
}
