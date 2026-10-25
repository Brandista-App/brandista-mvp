package com.brandista.brandista.order;

import com.brandista.brandista.user.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("production")
@AutoConfigureObservability
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:production-ops;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "management.server.port=0",
        "management.endpoints.web.exposure.include=health,metrics,prometheus",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
class ProductionOperationsIntegrationTests {
    @LocalManagementPort int managementPort;
    @Autowired TestRestTemplate http;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory factory;

    @Test void monitoringWorksOnlyOnSeparateListenerAndApplicationAuthorizationIsPreserved() {
        assertThat(http.getForEntity("http://127.0.0.1:"+managementPort+"/actuator/prometheus",String.class)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/actuator/prometheus",String.class).getStatusCode().is4xxClientError()).isTrue();
        assertThat(http.getForEntity("/actuator/metrics",String.class).getStatusCode().is4xxClientError()).isTrue();
        assertThat(http.getForEntity("/api/v1/customers/me/orders",String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.getForEntity("/readyz",String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/livez",String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("http://127.0.0.1:"+managementPort+"/actuator/env",String.class)
                .getStatusCode().is4xxClientError()).isTrue();
    }

    @Test void orderHistoryBatchesItemsWithoutCollectionFetchPagination() {
        var tx=new TransactionTemplate(transactions);
        long customer=tx.execute(status -> {
            var user=users.saveAndFlush(new User("history-"+UUID.randomUUID()+"@example.invalid","synthetic-hash",UserRole.CUSTOMER,AccountStatus.ENABLED));
            var delivery=new CheckoutModels.Delivery("Synthetic","+923001234567","Test residence",null,"Test city",null,null,null);
            var line=new CheckoutModels.Line(1L,"synthetic","Synthetic",1L,"Fictional",1,BigDecimal.ONE,BigDecimal.ONE);
            var preview=new CheckoutModels.Preview("0".repeat(64),delivery,List.of(line),BigDecimal.ONE,
                    BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE,"PKR","COD");
            for(int i=0;i<25;i++) orders.save(new CustomerOrder(user.getId(),UUID.randomUUID().toString(),"0".repeat(64),1L,preview));
            return user.getId();
        });
        var stats=factory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        tx.executeWithoutResult(status -> {
            var page=orders.findAllByCustomerIdOrderByCreatedAtDescIdDesc(customer,PageRequest.of(0,20));
            assertThat(page.getTotalElements()).isEqualTo(25);
            assertThat(page.getContent()).hasSize(20);
            assertThat(page.getContent().stream().map(CustomerOrder::response).toList())
                    .allSatisfy(order -> assertThat(order.items()).hasSize(1));
        });
        // One SQL page, one count and one batch for all 20 item collections.
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }
}
