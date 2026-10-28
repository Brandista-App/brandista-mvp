package com.brandista.brandista.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionPoolStartupRetryTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withPropertyValues("spring.profiles.active=production",
                    "spring.datasource.url=jdbc:h2:mem:startup-retry;DB_CLOSE_DELAY=-1",
                    "spring.datasource.username=sa", "spring.datasource.password=",
                    "spring.datasource.driver-class-name=" + TransientDriver.class.getName());

    @Test void productionPoolRetriesTransientInitialConnectionFailureWithoutIncreasingRuntimeWait() {
        TransientDriver.attempts.set(0);
        runner.run(context -> {
            var pool = context.getBean(HikariDataSource.class);
            try (Connection connection = pool.getConnection()) {
                assertThat(connection.isValid(1)).isTrue();
                assertThat(TransientDriver.attempts.get()).isGreaterThanOrEqualTo(2);
                assertThat(pool.getConnectionTimeout()).isEqualTo(3000);
            }
        });
    }

    @Test void previousFailFastDefaultRejectsTheSameTransientFailure() {
        TransientDriver.attempts.set(0);
        runner.withPropertyValues("spring.datasource.hikari.initialization-fail-timeout=1")
                .run(context -> {
                    var pool = context.getBean(HikariDataSource.class);
                    assertThatThrownBy(pool::getConnection)
                            .hasStackTraceContaining("synthetic transient login failure");
                    assertThat(TransientDriver.attempts.get()).isEqualTo(1);
                });
    }

    /** Deterministic connection-establishment fault; no real DB or secret involved. */
    public static final class TransientDriver implements Driver {
        static final AtomicInteger attempts = new AtomicInteger();
        private final org.h2.Driver delegate = new org.h2.Driver();
        public TransientDriver() { }
        @Override public Connection connect(String url, Properties properties) throws SQLException {
            if (attempts.incrementAndGet() == 1)
                throw new SQLException("synthetic transient login failure", "08S01");
            return delegate.connect(url, properties);
        }
        @Override public boolean acceptsURL(String url) { return url != null && url.startsWith("jdbc:h2:"); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getLogger("synthetic-startup-driver"); }
    }
}
