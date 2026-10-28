package com.brandista.brandista.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("production")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:health-contract;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "management.server.port=0"})
class HealthContractIntegrationTests {
    @Autowired TestRestTemplate http;
    @Autowired ConfigurableApplicationContext context;
    @Autowired ApplicationAvailability availability;
    @Autowired HealthEndpointGroups groups;

    @Test void productionGroupsAndStateTransitionsAreIndependent() {
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
        assertThat(groups.get("readiness").isMember("db")).isTrue();
        assertThat(groups.get("readiness").isMember("livenessState")).isFalse();
        assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
        assertThat(groups.get("liveness").isMember("db")).isFalse();
        assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        assertStatus("/readyz", 200);
        assertStatus("/livez", 200);
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            assertStatus("/readyz", 503);
            assertStatus("/livez", 200);
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
            AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);
            assertStatus("/livez", 503);
            assertStatus("/readyz", 200);
        } finally {
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
        assertStatus("/readyz", 200);
        assertStatus("/livez", 200);
    }

    private void assertStatus(String path, int status) {
        var response = http.getForEntity(path, String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).doesNotContain("components", "details", "database", "jdbc:");
    }
}
