package com.brandista.brandista;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:brandista-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
class BrandistaApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void applicationContextStarts() {
        assertThat(port).isPositive();
    }

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessAndDatabaseReadinessProbesArePublicAndHideDetails() {
        for (String probe : List.of("liveness", "readiness")) {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "http://localhost:" + port + "/actuator/health/" + probe, String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"status\":\"UP\"");
            assertThat(response.getBody()).doesNotContain("components", "details");
        }
    }

    @Test
    void versionedMigrationRunsOnceAndIsRecorded() {
        assertThat(java.util.Arrays.stream(flyway.info().applied())
                .map(migration -> migration.getVersion().getVersion()).toList())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
