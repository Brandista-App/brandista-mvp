package com.brandista.operator;

import com.brandista.brandista.privilege.OperatorProvisioning;
import com.brandista.brandista.privilege.SecurityAudit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.Console;
import java.util.Arrays;
import java.util.Set;

/** Fixed-owner workflow using the existing audited service; never scanned by the web application. */
public final class OwnerProvisioningMain {
    private OwnerProvisioningMain() { }

    public static void main(String[] args) {
        Console console = System.console();
        if (console == null || args.length != 0) {
            System.err.println("Use an interactive terminal with no arguments or password pipes.");
            System.exit(2);
            return;
        }
        System.exit(run(console, new SpringApplication(BootstrapMain.OperatorConfiguration.class)));
    }

    static int run(Console console, SpringApplication app) {
        try {
            return execute(console, app);
        } catch (RuntimeException failure) {
            console.printf("Owner command failed. State may have committed; inspect with VERIFY before retrying. Review approved configuration privately.%n");
            return 2;
        }
    }

    static int execute(Console console, SpringApplication app) {
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        app.setRegisterShutdownHook(false);
        // Override inherited deployment settings: this console cannot migrate or start a server.
        try (var context = app.run("--spring.main.web-application-type=none", "--spring.flyway.enabled=false",
                "--logging.level.root=OFF", "--spring.main.banner-mode=off",
                "--spring.datasource.hikari.register-mbeans=false")) {
            var jdbc = context.getBean(JdbcTemplate.class);
            Integer schema = jdbc.queryForObject("SELECT COUNT(*) FROM `flyway_schema_history` WHERE `success`=true "
                    + "AND `version` IN ('10','11','12','13','14')", Integer.class);
            if (schema == null || schema != 5) {
                console.printf("Required reviewed migrations V10-V14 are missing; no operation performed.%n");
                return 2;
            }
            var before = OwnerAccountInspection.inspect(jdbc);
            console.printf("Aiven connection and schema check succeeded. Account: %s%n", before == null ? "ABSENT" : before);
            String modeText = console.readLine("Mode (VERIFY, INITIAL_CREATE, INITIAL_GRANT, RESET_PASSWORD): ");
            if (modeText == null) return 2;
            if ("VERIFY".equals(modeText.trim())) return before != null && before.ready() ? 0 : 1;
            if (!Set.of("INITIAL_CREATE", "INITIAL_GRANT", "RESET_PASSWORD").contains(modeText.trim())) return 2;
            var mode = OperatorProvisioning.Mode.valueOf(modeText.trim());
            Long expectedId = null;
            if (mode != OperatorProvisioning.Mode.INITIAL_CREATE)
                expectedId = Long.valueOf(console.readLine("Independently verified existing user ID: ").trim());
            String approval = "APPROVED " + mode.name() + " " + OwnerAccountInspection.EMAIL;
            console.printf("Requires owner approval for this database and operation. Type: %s%n", approval);
            if (!approval.equals(console.readLine("Confirmation: "))) return 2;
            char[] password = null;
            char[] confirmation = null;
            try {
                if (mode.needsPassword()) {
                    password = console.readPassword("New password (12+ characters, at most 72 UTF-8 bytes): ");
                    confirmation = console.readPassword("Confirm new password: ");
                    if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) {
                        console.printf("Password confirmation failed; no operation performed.%n");
                        return 2;
                    }
                }
                var result = context.getBean(OperatorProvisioning.class)
                        .execute(mode, OwnerAccountInspection.EMAIL, expectedId, password);
                console.printf("%s: %s; target ID: %s%n", result.outcome(), result.reason(), result.targetUserId());
                if (result.outcome() != SecurityAudit.Outcome.SUCCESS) return 1;
                var persisted = OwnerAccountInspection.inspect(jdbc);
                if (persisted == null || !persisted.ready() || persisted.id() != result.targetUserId()) {
                    console.printf("Mutation committed but verification failed. Stop and investigate; do not retry automatically.%n");
                    return 2;
                }
                console.printf("Verified committed account: %s%n", persisted);
                return 0;
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (confirmation != null) Arrays.fill(confirmation, '\0');
            }
        }
    }
}
