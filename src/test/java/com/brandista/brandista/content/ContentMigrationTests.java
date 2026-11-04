package com.brandista.brandista.content;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ContentMigrationTests {
 @Test void v15PreservesCommerceAndIdentityRecordsAndIsIdempotent() throws Exception {
  String url=System.getenv("CONTENT_TEST_MYSQL_URL");
  DriverManagerDataSource source;
  if(url==null)source=new DriverManagerDataSource("jdbc:h2:mem:content-migration-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
  else {
   if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/brandista_content_verify\\?.*"))throw new IllegalArgumentException("Only a disposable local content test database is allowed");
   source=new DriverManagerDataSource(url,"root",System.getenv("CONTENT_TEST_MYSQL_PASSWORD"));
  }
  Flyway.configure().dataSource(source).target("13").load().migrate();
  try(var connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/brand-membership-upgrade-fixture.sql"));}
  Flyway.configure().dataSource(source).target("14").load().migrate();
  var db=new JdbcTemplate(source);var before=snapshot(db);
  assertThat(Flyway.configure().dataSource(source).target("15").load().migrate().migrationsExecuted).isEqualTo(1);
  assertThat(snapshot(db)).isEqualTo(before);
  assertThat(db.queryForObject("SELECT version FROM app_content WHERE id=1",Long.class)).isZero();
  assertThat(db.queryForObject("SELECT published FROM app_content WHERE id=1",String.class)).isNull();
  assertThat(Flyway.configure().dataSource(source).target("15").load().migrate().migrationsExecuted).isZero();
  // Exercises the UTF-8 indexed image URL column on real MySQL as well as H2.
  db.update("INSERT INTO app_content_images(url,actor,created_at) VALUES(?,'fixture',CURRENT_TIMESTAMP)","https://images.example.test/app-content/fixture.png");
 }
 Map<String,Object> snapshot(JdbcTemplate db) {
  var rows=new LinkedHashMap<String,Object>();
  for(String table:List.of("users","brands","categories","products","customer_addresses","carts","cart_items","customer_orders","customer_order_items","brand_applications","user_platform_grants","security_provisioning_state","security_audit_events","brand_admin_memberships","brand_admin_invitations","brand_invitation_accept_limits","merchant_order_fulfillments","merchant_order_events"))
   rows.put(table,db.queryForList("SELECT * FROM "+table));
  return rows;
 }
}
