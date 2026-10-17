package com.brandista.operator;

import com.brandista.brandista.privilege.OperatorProvisioning;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.context.WebServerApplicationContext;
import static org.assertj.core.api.Assertions.*;

class OperatorConfigurationTests {
    @Test void offlineContextHasNoHttpServerControllersOrJwtRequirementsAndDefaultsToDisabled() {
        var app = new SpringApplication(BootstrapMain.OperatorConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(java.util.Map.of("spring.main.web-application-type", "servlet"));
        // Command-line test overrides outrank any deployment datasource environment variables.
        try (var context = app.run("--spring.main.web-application-type=none",
                "--spring.datasource.url=jdbc:h2:mem:offline-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=")) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansWithAnnotation(org.springframework.web.bind.annotation.RestController.class)).isEmpty();
            assertThat(context.getBean(OperatorProvisioning.class).execute(OperatorProvisioning.Mode.INITIAL_CREATE,
                    "synthetic@example.invalid", null, null).reason())
                    .isEqualTo(com.brandista.brandista.privilege.SecurityAudit.Reason.DISABLED);
        }
    }
}
