package com.brandista.brandista.privilege;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PlatformGrantMigrationTests {
    @Test
    void upgradesV9WithoutChangingCustomersLegacyAdminsOrExistingData() throws Exception {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("9").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users(email,password_hash,role,status,created_at,updated_at) VALUES " +
                    "('customer@example.invalid','test-only-hash','CUSTOMER','ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)," +
                    "('admin@example.invalid','test-only-hash','ADMIN','ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        }
        assertThat(Flyway.configure().dataSource(url, "sa", "").target("10").load().migrate().migrationsExecuted).isEqualTo(1);
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement sql = connection.createStatement()) {
            try (ResultSet rows = sql.executeQuery("SELECT role,COUNT(*) FROM users GROUP BY role ORDER BY role")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("ADMIN"); assertThat(rows.getInt(2)).isEqualTo(1);
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("CUSTOMER"); assertThat(rows.getInt(2)).isEqualTo(1);
                assertThat(rows.next()).isFalse();
            }
            try (ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM user_platform_grants")) {
                rows.next(); assertThat(rows.getInt(1)).isZero();
            }
            sql.executeUpdate("INSERT INTO user_platform_grants(user_id,grant_type,created_at) SELECT id,'SUPER_ADMIN',CURRENT_TIMESTAMP FROM users WHERE role='CUSTOMER'");
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO user_platform_grants(user_id,grant_type,created_at) SELECT id,'SUPER_ADMIN',CURRENT_TIMESTAMP FROM users WHERE role='CUSTOMER'"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO user_platform_grants(user_id,grant_type,created_at) VALUES (99999,'SUPER_ADMIN',CURRENT_TIMESTAMP)"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO user_platform_grants(user_id,grant_type,created_at) SELECT id,'BRAND_ADMIN',CURRENT_TIMESTAMP FROM users WHERE role='ADMIN'"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
