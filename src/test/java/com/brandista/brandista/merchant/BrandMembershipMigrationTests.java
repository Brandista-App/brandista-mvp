package com.brandista.brandista.merchant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BrandMembershipMigrationTests {
    @Test void v12PreservesPopulatedCommerceAndSecurityDataWithoutAutomaticMemberships() throws Exception {
        String url="jdbc:h2:mem:membership-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
        var datasource=new DriverManagerDataSource(url,"sa","");
        Flyway.configure().dataSource(datasource).target("11").load().migrate();
        try(var connection=datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/brand-membership-upgrade-fixture.sql"));
        }
        var jdbc=new JdbcTemplate(datasource);
        var before=snapshot(jdbc);
        assertThat(Flyway.configure().dataSource(datasource).load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(snapshot(jdbc)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT target_brand_id FROM security_audit_events",Long.class)).isNull();
        jdbc.update("INSERT INTO brand_admin_memberships(user_id,brand_id,created_at) VALUES(1,1,CURRENT_TIMESTAMP),(1,2,CURRENT_TIMESTAMP),(2,1,CURRENT_TIMESTAMP)");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships",Long.class)).isEqualTo(3);
        for(String values:List.of("1,1","999,1","1,999"))
            assertThatThrownBy(() -> jdbc.update("INSERT INTO brand_admin_memberships(user_id,brand_id,created_at) VALUES("+values+",CURRENT_TIMESTAMP)"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(Flyway.configure().dataSource(datasource).load().migrate().migrationsExecuted).isZero();
    }

    private Map<String,Object> snapshot(JdbcTemplate jdbc) {
        var state=new LinkedHashMap<String,Object>();
        for(String table:List.of("users","brands","categories","products","customer_addresses","carts","cart_items",
                "customer_orders","customer_order_items","brand_applications","user_platform_grants","security_provisioning_state"))
            state.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id"));
        state.put("audit",jdbc.queryForList("SELECT id,actor,operation,target_user_id,occurred_at,outcome,reason FROM security_audit_events ORDER BY id"));
        return state;
    }
}
