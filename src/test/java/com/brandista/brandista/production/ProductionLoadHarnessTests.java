package com.brandista.brandista.production;

import com.brandista.brandista.onboarding.InvitationDelivery;
import com.brandista.brandista.privilege.OperatorProvisioning;
import com.brandista.brandista.privilege.SecurityAudit;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.*;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Test classpath only. Run exclusively via scripts/load/run-isolated.ps1. */
@EnabledIfEnvironmentVariable(named="BRANDISTA_PROD001_LOAD",matches="1")
@ActiveProfiles("production")
@AutoConfigureObservability
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.address=127.0.0.1",
        "management.server.port=0", "management.endpoints.web.exposure.include=health,metrics,prometheus",
        "brandista.checkout.cod-enabled=true", "brandista.security.provisioning.enabled=true"})
@Import(ProductionLoadHarnessTests.DeliveryFixture.class)
class ProductionLoadHarnessTests {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url=System.getenv("BRANDISTA_PROD001_URL");
        if(url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/brandista_prod001\\?.*"))
            throw new IllegalArgumentException("Disposable loopback PROD-001 schema required");
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.username",()->"root");
        properties.add("spring.datasource.password",()->System.getenv("BRANDISTA_PROD001_PASSWORD"));
    }
    @TestConfiguration static class DeliveryFixture {
        @Bean @Primary CaptureDelivery capture() {return new CaptureDelivery();}
    }
    static class CaptureDelivery implements InvitationDelivery {
        final Map<Long,String> tokens=new ConcurrentHashMap<>();
        public void deliver(long id,String email,String token){tokens.put(id,token);}
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    @Autowired OperatorProvisioning operator;
    @Autowired CaptureDelivery delivery;
    @LocalServerPort int port;
    @LocalManagementPort int management;
    final String password=UUID.randomUUID()+"-Synthetic!";
    final Path output=Path.of(System.getenv().getOrDefault("BRANDISTA_PROD001_OUTPUT","target/prod001-load/manual")).toAbsolutePath().normalize();
    long[] brands=new long[2],products=new long[2];
    String[] merchants=new String[2];

    JsonNode call(String method,String path,String token,Object body,int expected) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null)headers.setBearerAuth(token);
        var response=http.exchange(path,HttpMethod.valueOf(method),new HttpEntity<>(body,headers),JsonNode.class);
        assertThat(response.getStatusCode().value()).as(method+" "+path).isEqualTo(expected);
        return response.getBody();
    }
    String login(String email){return call("POST","/api/v1/auth/login",null,Map.of("email",email,"password",password),200).path("accessToken").asText();}
    String register(String email){call("POST","/api/v1/registrations",null,Map.of("email",email,"password",password),201);return login(email);}

    @Test void isolatedCommercialAcceptanceAndControlledLoad() throws Exception {
        assertThat(output.startsWith(Path.of("target/prod001-load").toAbsolutePath().normalize())).isTrue();
        Files.createDirectories(output);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("brandista_prod001");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isZero();
        var provision=operator.execute(OperatorProvisioning.Mode.INITIAL_CREATE,"operator@example.invalid",null,password.toCharArray());
        assertThat(provision.outcome()).isEqualTo(SecurityAudit.Outcome.SUCCESS);
        String admin=login("operator@example.invalid");
        long category=call("POST","/api/v1/admin/categories",admin,Map.of("name","Synthetic category","slug","synthetic-category"),201).path("id").asLong();
        for(int i=0;i<2;i++) {
            String email="merchant"+i+"@example.invalid";
            merchants[i]=register(email);
            long application=call("POST","/api/v1/brand-applications",null,Map.of("brandName","Fictional PROD brand "+i,
                    "contactName","Synthetic merchant","contactEmail",email),201).path("id").asLong();
            brands[i]=call("PATCH","/api/v1/admin/brand-applications/"+application+"/review",admin,Map.of("decision","APPROVED"),200).path("brandId").asLong();
            long invitation=call("POST","/api/v1/super-admin/brand-applications/"+application+"/invitations",admin,null,201).path("id").asLong();
            call("POST","/api/v1/brand-invitations/accept",merchants[i],Map.of("token",delivery.tokens.remove(invitation)),200);
            call("POST","/api/v1/super-admin/brand-applications/"+application+"/activate",admin,null,200);
            String root="/api/v1/merchant/brands/"+brands[i]+"/products";
            products[i]=call("POST",root,merchants[i],Map.of("name","Synthetic product "+i,"slug","synthetic-product-"+i,
                    "brandId",brands[i],"categoryId",category,"price",i==0?100:200,"availability","AVAILABLE"),201).path("id").asLong();
            call("PUT",root+"/"+products[i]+"/stock",merchants[i],Map.of("stockQuantity",1000000),200);
            call("PATCH",root+"/"+products[i]+"/publish",merchants[i],null,200);
        }
        call("GET","/api/v1/merchant/brands/"+brands[1]+"/products",merchants[0],null,403);
        String customer=register("journey@example.invalid");
        long address=call("POST","/api/v1/customers/me/addresses",customer,Map.of("recipientName","Synthetic buyer",
                "phoneNumber","+923001234567","addressLine1","Synthetic residence","city","Test city"),201).path("id").asLong();
        for(long product:products)call("POST","/api/v1/customers/me/cart/items",customer,Map.of("productId",product,"quantity",1),201);
        var quote=call("POST","/api/v1/customers/me/checkout/preview",customer,Map.of("addressId",address),200);
        var headers=new HttpHeaders();headers.setBearerAuth(customer);headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key",UUID.randomUUID().toString());
        var entity=new HttpEntity<>(Map.of("addressId",address,"quoteToken",quote.path("quoteToken").asText()),headers);
        var order=http.postForEntity("/api/v1/customers/me/orders",entity,JsonNode.class);
        assertThat(order.getStatusCode().value()).isEqualTo(201);
        long orderId=order.getBody().path("id").asLong();
        assertThat(order.getBody().path("total").asInt()).isEqualTo(300);
        var replay=http.postForEntity("/api/v1/customers/me/orders",entity,JsonNode.class);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody().path("id").asLong()).isEqualTo(orderId);
        for(int i=0;i<2;i++) {
            var detail=call("GET","/api/v1/merchant/brands/"+brands[i]+"/orders/"+orderId,merchants[i],null,200);
            assertThat(detail.path("items")).hasSize(1);
            assertThat(detail.path("items").get(0).path("brandId").asLong()).isEqualTo(brands[i]);
        }
        String[] transitions={"ACCEPTED","PACKED","SHIPPED","DELIVERED"};
        for(int i=0;i<transitions.length;i++)call("POST","/api/v1/merchant/brands/"+brands[0]+"/orders/"+orderId+"/fulfillment",
                merchants[0],Map.of("status",transitions[i],"expectedVersion",i),200);
        assertThat(call("GET","/api/v1/merchant/brands/"+brands[1]+"/orders/"+orderId,merchants[1],null,200)
                .path("order").path("fulfillmentStatus").asText()).isEqualTo("PLACED");
        assertThat(call("GET","/api/v1/customers/me/orders",customer,null,200).path("content")).hasSize(1);
        Files.writeString(output.resolve("commercial-acceptance.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "status","PASS","database","disposable MySQL 8.4.11","provisioning","OperatorProvisioning.INITIAL_CREATE (test-only service invocation; interactive CLI not tested)",
                "invitationDelivery","authorized in-memory test capture; no production email",
                "journey","two brands, invitation activation, published stock, mixed COD, idempotent replay, tenant denial, per-brand visibility, A delivered/B placed, customer history")));

        // Larger synthetic catalogue and isolated account fixtures, not privileged runtime setup.
        String hash=encoder.encode(password);
        var userBatch=new ArrayList<Object[]>();
        for(int i=0;i<500;i++)userBatch.add(new Object[]{"load"+i+"@example.invalid",hash});
        jdbc.batchUpdate("INSERT INTO users(email,password_hash,role,status,created_at,updated_at) VALUES(?,?,'CUSTOMER','ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",userBatch);
        var productBatch=new ArrayList<Object[]>();
        for(int i=0;i<1000;i++)productBatch.add(new Object[]{"Synthetic catalogue "+i,"synthetic-catalogue-"+i,brands[i%2],category});
        jdbc.batchUpdate("INSERT INTO products(name,slug,brand_id,category_id,price,stock_quantity,availability,publication_status,created_at,updated_at) "
                +"VALUES(?,?,?,?,100,1000,'AVAILABLE','PUBLISHED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",productBatch);
        var identities=json.createArrayNode();
        for(int i=0;i<500;i++) {
            String email="load"+i+"@example.invalid";
            long user=jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,email);
            long product=jdbc.queryForObject("SELECT id FROM products WHERE slug=?",Long.class,"synthetic-catalogue-"+i);
            if(i%10==2)jdbc.update("INSERT INTO brand_admin_memberships(user_id,brand_id,created_at) VALUES(?,?,CURRENT_TIMESTAMP)",user,brands[0]);
            identities.addObject().put("email",email).put("productId",product);
        }
        ObjectNode fixture=json.createObjectNode().put("guard","brandista-prod001-disposable")
                .put("base","http://127.0.0.1:"+port).put("management","http://127.0.0.1:"+management)
                .put("password",password).put("brandId",brands[0]).put("slug","synthetic-product-0");
        fixture.set("users",identities); fixture.set("productIds",json.valueToTree(products));
        var process=new ProcessBuilder("node","scripts/load/mixed-load.mjs").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var writer=new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8);
        writer.write(fixture.toString()+"\n");writer.flush();
        ArrayNode stages=json.createArrayNode();
        try(var reader=process.inputReader(StandardCharsets.UTF_8)) {
            String line;while((line=reader.readLine())!=null) {
                ObjectNode result=(ObjectNode)json.readTree(line);
                result.set("database",json.valueToTree(databaseSnapshot()));
                assertIntegrity();
                stages.add(result);
                Files.writeString(output.resolve("results.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(stages));
                System.out.println("PROD001 stage users="+result.path("users")+" rps="+result.path("rps")+" p95="+result.path("p95")+" errors="+result.path("errorRate"));
                writer.write("continue\n");writer.flush();
            }
        } finally {
            writer.close();
            if(!process.waitFor(10,TimeUnit.SECONDS))process.destroyForcibly();
        }
        assertThat(process.waitFor()).isZero();assertThat(stages).isNotEmpty();
        assertIntegrity();
        Files.writeString(output.resolve("integrity.json"),"{\"stockReconciliation\":\"PASS\",\"uniqueIdempotency\":\"PASS\",\"fulfillmentsMatchSnapshottedBrands\":\"PASS\",\"unpaidCODTotalsMatchItems\":\"PASS\"}");
    }
    Map<String,Object> databaseSnapshot() {
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("globalStatus",jdbc.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Max_used_connections','Slow_queries','Innodb_row_lock_waits','Innodb_row_lock_time')"));
        snapshot.put("slowQueryCount",jdbc.queryForObject("SELECT COUNT(*) FROM mysql.slow_log",Long.class));
        snapshot.put("topStatementDigests",jdbc.queryForList("SELECT DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT/1000000000000 AS total_seconds,SUM_ROWS_EXAMINED,SUM_ERRORS "
                +"FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='brandista_prod001' ORDER BY SUM_TIMER_WAIT DESC LIMIT 8"));
        snapshot.put("integrity","PASS");return snapshot;
    }
    void assertIntegrity() {
        for(long product:products) {
            long stock=jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?",Long.class,product);
            long sold=jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM customer_order_items WHERE product_id=?",Long.class,product);
            assertThat(stock+sold).isEqualTo(1000000);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT customer_id,idempotency_key FROM customer_orders GROUP BY customer_id,idempotency_key HAVING COUNT(*)>1) x",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders o WHERE (SELECT COUNT(*) FROM merchant_order_fulfillments f WHERE f.order_id=o.id)<>(SELECT COUNT(DISTINCT i.brand_id) FROM customer_order_items i WHERE i.order_id=o.id)",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_fulfillments f WHERE NOT EXISTS(SELECT 1 FROM customer_order_items i WHERE i.order_id=f.order_id AND i.brand_id=f.brand_id)",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders o WHERE payment_status<>'UNPAID' OR payment_method<>'COD' OR currency<>'PKR' OR total<>(SELECT SUM(i.line_subtotal) FROM customer_order_items i WHERE i.order_id=o.id)",Long.class)).isZero();
    }
}
