package com.brandista.operator;

import com.brandista.brandista.config.PasswordEncoderConfiguration;
import com.brandista.brandista.privilege.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import java.io.Console;
import java.util.Arrays;
import static com.brandista.brandista.privilege.SecurityAudit.Outcome;

/** Separate JVM entry point; deliberately outside the web application's component scan. */
public final class BootstrapMain {
    private BootstrapMain() { }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
    @Import({OperatorProvisioning.class, SecurityInvariant.class, SecurityAudit.class, PasswordEncoderConfiguration.class})
    public static class OperatorConfiguration { }

    public static void main(String[] args) {
        // No secrets or operational input accepted as command-line arguments or stdin pipes.
        Console console = System.console();
        if (console == null || args.length != 0) {
            System.err.println("Use an interactive terminal, with no arguments. Password piping is not supported.");
            System.exit(2);
            return;
        }
        int exit = run(console);
        System.exit(exit);
    }

    private static int run(Console console) {
        SpringApplication application = new SpringApplication(OperatorConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        // A deployment's SPRING_MAIN_WEB_APPLICATION_TYPE must never turn this tool into a server.
        try (var context = application.run("--spring.main.web-application-type=none")) {
            console.printf("Offline security provisioning. Verify database configuration before continuing.%n");
            var mode = OperatorProvisioning.Mode.valueOf(console.readLine("Mode: ").trim());
            String email = console.readLine("Account email: ");
            Long id = null;
            if (mode != OperatorProvisioning.Mode.INITIAL_CREATE && mode != OperatorProvisioning.Mode.RECOVER_CREATE)
                id = Long.valueOf(console.readLine("Verified existing user ID: ").trim());
            if (!"PROCEED".equals(console.readLine("Type PROCEED to confirm this operation: ")))
                return 2;
            char[] password = null;
            try {
                if (mode.needsPassword()) {
                    password = console.readPassword("New password (12+ characters, at most 72 UTF-8 bytes): ");
                    char[] confirmation = console.readPassword("Confirm password: ");
                    boolean matches = password != null && confirmation != null && Arrays.equals(password, confirmation);
                    if (confirmation != null) Arrays.fill(confirmation, '\0');
                    if (!matches) {
                        console.printf("Password confirmation did not match; no operation performed.%n");
                        return 2;
                    }
                }
                var result = context.getBean(OperatorProvisioning.class).execute(mode, email, id, password);
                console.printf("%s: %s; target user ID: %s%n", result.outcome(), result.reason(), result.targetUserId());
                return result.outcome() == Outcome.SUCCESS ? 0 : 1;
            } finally {
                if (password != null) Arrays.fill(password, '\0');
            }
        } catch (RuntimeException failure) {
            // Do not print stack traces/configuration values from an operator invocation.
            console.printf("Operator command failed. Review database health and approved configuration securely.%n");
            return 2;
        }
    }
}
