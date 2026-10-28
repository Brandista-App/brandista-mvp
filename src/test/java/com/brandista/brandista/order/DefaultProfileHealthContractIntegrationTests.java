package com.brandista.brandista.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import static org.assertj.core.api.Assertions.assertThat;

/** Matches the authenticated failed-deployment log: no active Spring profile. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:default-health-contract;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="})
class DefaultProfileHealthContractIntegrationTests {
    @Autowired TestRestTemplate http;
    @Autowired Environment environment;

    @Test void missingProductionProfileLeavesAdditionalPathsAbsent() {
        assertThat(environment.getActiveProfiles()).isEmpty();
        assertThat(http.getForEntity("/actuator/health/readiness", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/actuator/health/liveness", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/readyz", String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(http.getForEntity("/livez", String.class).getStatusCode().value()).isEqualTo(404);
    }
}
