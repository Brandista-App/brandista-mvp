package com.brandista.operator;

import java.io.Console;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OwnerProvisioningConsoleTests {
    @Test void consoleCreatesOnlyFixedOwnerVerifiesCommitRejectsRepeatAndResetsPassword() {
        var fixture = fixture();
        Console create = passwordConsole("INITIAL_CREATE", null, "Synthetic-console-secret-1!");
        assertThat(OwnerProvisioningMain.execute(create, fixture.app(true, false))).isZero();
        var before = OwnerAccountInspection.inspect(fixture.jdbc());
        assertThat(before.ready()).isTrue();
        assertThat(before.baseRole()).isEqualTo("CUSTOMER");
        String hash = fixture.jdbc().queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, before.id());
        assertThat(OwnerProvisioningMain.execute(passwordConsole("INITIAL_CREATE", null, "Synthetic-console-secret-2!"),
                fixture.app(true, false))).isEqualTo(1);
        assertThat(fixture.jdbc().queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, before.id())).isEqualTo(hash);
        assertThat(OwnerProvisioningMain.execute(passwordConsole("RESET_PASSWORD", before.id(), "Synthetic-console-secret-3!"),
                fixture.app(true, true))).isZero();
        assertThat(OwnerAccountInspection.inspect(fixture.jdbc())).isEqualTo(before);
        assertThat(fixture.jdbc().queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, before.id())).isNotEqualTo(hash);
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test void readOnlyMissingOwnerAndCancelledConfirmationCannotCreateAccount() {
        var fixture = fixture();
        Console verify = mock(Console.class);
        when(verify.readLine(anyString())).thenReturn("VERIFY");
        assertThat(OwnerProvisioningMain.execute(verify, fixture.app(false, false))).isEqualTo(1);
        Console cancel = mock(Console.class);
        when(cancel.readLine(anyString())).thenReturn("INITIAL_CREATE", "CANCEL");
        assertThat(OwnerProvisioningMain.execute(cancel, fixture.app(true, false))).isEqualTo(2);
        verify(cancel, never()).readPassword(anyString());
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM security_audit_events", Integer.class)).isZero();
    }

    private Console passwordConsole(String mode, Long id, String password) {
        Console console = mock(Console.class);
        String approval = "APPROVED " + mode + " " + OwnerAccountInspection.EMAIL;
        if (id == null) when(console.readLine(anyString())).thenReturn(mode, approval);
        else when(console.readLine(anyString())).thenReturn(mode, id.toString(), approval);
        when(console.readPassword(anyString())).thenReturn(password.toCharArray(), password.toCharArray());
        return console;
    }

    private Fixture fixture() {
        String url = "jdbc:h2:mem:owner-console-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        return new Fixture(url);
    }

    private record Fixture(String url) {
        JdbcTemplate jdbc() { return new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")); }
        SpringApplication app(boolean mutation, boolean recovery) {
            return new SpringApplication(BootstrapMain.OperatorConfiguration.class) {
                @Override public ConfigurableApplicationContext run(String... args) {
                    var options = new ArrayList<>(Arrays.asList(args));
                    // Command-line isolation outranks all ambient deployment datasource settings.
                    options.add("--spring.datasource.url=" + url);
                    options.add("--spring.datasource.username=sa");
                    options.add("--spring.datasource.password=");
                    options.add("--brandista.security.provisioning.enabled=" + mutation);
                    options.add("--brandista.security.provisioning.recovery-enabled=" + recovery);
                    return super.run(options.toArray(String[]::new));
                }
            };
        }
    }
}
