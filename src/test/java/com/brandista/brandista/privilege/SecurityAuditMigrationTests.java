package com.brandista.brandista.privilege;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SecurityAuditMigrationTests {
    @Test void v11IsAdditiveAndNeverPromotesOrRewritesExistingAccountsOrGrants() throws Exception {
        String url = "jdbc:h2:mem:audit-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("10").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users(email,password_hash,role,status,created_at,updated_at) VALUES "
                    + "('legacy@example.invalid','synthetic-hash','ADMIN','ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO user_platform_grants(user_id,grant_type,created_at,revoked_at) "
                    + "SELECT id,'SUPER_ADMIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM users");
        }
        assertThat(Flyway.configure().dataSource(url, "sa", "").load().migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT role,password_hash FROM users")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("ADMIN");
                assertThat(rows.getString(2)).isEqualTo("synthetic-hash"); assertThat(rows.next()).isFalse();
            }
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM user_platform_grants WHERE revoked_at IS NOT NULL")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(1);
            }
            try (var rows = sql.executeQuery("SELECT initialized_at FROM security_provisioning_state WHERE id=1")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getTimestamp(1)).isNull();
            }
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO security_provisioning_state(id) VALUES (2)"))
                    .isInstanceOf(SQLException.class);
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM security_audit_events")) {
                rows.next(); assertThat(rows.getInt(1)).isZero();
            }
        }
    }
}
