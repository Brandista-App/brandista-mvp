package com.brandista.brandista.merchant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MerchantOrderMigrationTests {
    @Test void v14BackfillsEachHistoricalBrandAndPreservesAllExistingRecords() throws Exception {
        String url=System.getenv("BRANDISTA_PR7A_TEST_MYSQL_URL");
        DriverManagerDataSource datasource;
        if(url==null)datasource=new DriverManagerDataSource("jdbc:h2:mem:order-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        else {
            if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/brandista_pr7a_verify\\?.*"))throw new IllegalArgumentException("Only disposable PR7A MySQL allowed");
            datasource=new DriverManagerDataSource(url.replace("/brandista_pr7a_verify?","/brandista_pr7a_upgrade?"),"root",System.getenv("BRANDISTA_PR7A_TEST_MYSQL_PASSWORD"));
        }
        Flyway.configure().dataSource(datasource).target("13").load().migrate();
        try(var connection=datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/brand-membership-upgrade-fixture.sql"));
        }
        var jdbc=new JdbcTemplate(datasource);
        jdbc.update("INSERT INTO customer_order_items(id,order_id,product_id,product_slug,product_name,brand_id,brand_name,quantity,unit_price,line_subtotal) "
                + "VALUES(2,1,999,'historic-b','Historic B',2,'Synthetic B',1,0.50,0.50),(3,1,1,'historic-a','Historic A',1,'Synthetic A',1,25.00,25.00)");
        jdbc.update("UPDATE customer_orders SET subtotal=175.50,total=175.50 WHERE id=1");
        var before=snapshot(jdbc);
        assertThat(Flyway.configure().dataSource(datasource).target("14").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(snapshot(jdbc)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_fulfillments",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_fulfillments WHERE order_id=1 AND status='PLACED' AND version=0",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_events",Long.class)).isZero();
        assertThat(Flyway.configure().dataSource(datasource).target("14").load().migrate().migrationsExecuted).isZero();
        assertThatThrownBy(()->jdbc.update("UPDATE merchant_order_fulfillments SET status='PAID' WHERE order_id=1 AND brand_id=1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .satisfies(failure->assertThat(failure.getMessage().toLowerCase(Locale.ROOT)).contains("ck_merchant_fulfillment_status"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_fulfillments WHERE order_id=1 AND status='PLACED' AND version=0",Long.class)).isEqualTo(2);
    }
    private Map<String,Object> snapshot(JdbcTemplate jdbc) {
        var rows=new LinkedHashMap<String,Object>();
        for(String table:List.of("users","brands","categories","products","customer_addresses","carts","cart_items","customer_orders","customer_order_items",
                "brand_applications","user_platform_grants","security_provisioning_state","security_audit_events","brand_admin_memberships","brand_admin_invitations","brand_invitation_accept_limits"))
            rows.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY "+(table.equals("brand_invitation_accept_limits")?"user_id":"id")));
        return rows;
    }
}
