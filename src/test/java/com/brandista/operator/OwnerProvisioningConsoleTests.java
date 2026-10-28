package com.brandista.operator;

import java.nio.file.Files;
import java.security.KeyStore;
import java.sql.SQLException;
import java.util.UUID;
import javax.net.ssl.SSLHandshakeException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static com.brandista.operator.OwnerDiagnostics.Failure;

class OwnerProvisioningConsoleTests {
    @Test void acceptsOnlyNonSecretKnownOptionsAndDoesNotAcceptArbitrarySpringOverrides() {
        var options=OwnerProvisioningMain.Options.parse(new String[]{"--host","localhost","--port","12345","--truststore","fixture.p12","--verify-only"});
        assertThat(options.verifyOnly()).isTrue();
        assertThat(options.port()).isEqualTo(12345);
        for(String[] args:new String[][]{{},{"--password","secret"},{"--spring.flyway.enabled","true"},
                {"--host","localhost","--port","0","--truststore","x"},
                {"--host","localhost/other","--port","3306","--truststore","x"}})
            assertThatThrownBy(()->OwnerProvisioningMain.Options.parse(args)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsMissingMalformedAndEmptyTruststoresWithoutRawErrors() throws Exception {
        var missing=java.nio.file.Path.of("target","missing-owner-"+UUID.randomUUID()+".p12");
        assertThatThrownBy(()->OwnerProvisioningMain.validateTruststore(missing,"synthetic".toCharArray()))
                .isInstanceOfSatisfying(OwnerProvisioningMain.Stop.class,e->assertThat(e.failure).isEqualTo(Failure.TRUSTSTORE_INVALID));
        var empty=Files.createTempFile("owner-empty-", ".p12");
        try {
            var store=KeyStore.getInstance("PKCS12"); store.load(null,"synthetic".toCharArray());
            try(var out=Files.newOutputStream(empty)) { store.store(out,"synthetic".toCharArray()); }
            assertThatThrownBy(()->OwnerProvisioningMain.validateTruststore(empty,"synthetic".toCharArray()))
                    .isInstanceOf(OwnerProvisioningMain.Stop.class);
            assertThatThrownBy(()->OwnerProvisioningMain.validateTruststore(empty,"wrong".toCharArray()))
                    .isInstanceOf(OwnerProvisioningMain.Stop.class);
        } finally { Files.deleteIfExists(empty); }
    }
    @Test void validatesRealFlywayHistoryReadOnlyAndRejectsMissingVersion() {
        String url="jdbc:h2:mem:owner-history-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa", "").load().migrate();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa", ""));
        OwnerProvisioningMain.verifySchema(jdbc);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class)).isZero();
        jdbc.update("DELETE FROM flyway_schema_history WHERE version='14'");
        assertThatThrownBy(()->OwnerProvisioningMain.verifySchema(jdbc)).isInstanceOf(OwnerProvisioningMain.Stop.class);
    }
    @Test void classifiesFailuresWithoutUsingPotentiallySensitiveDriverMessages() {
        String sensitive="password=do-not-render; jdbc:mysql://private-host; token=do-not-render";
        assertThat(OwnerDiagnostics.classify(new SQLException(sensitive,"28000",1045))).isEqualTo(Failure.DATABASE_CREDENTIALS);
        assertThat(OwnerDiagnostics.classify(new SQLException(sensitive,"42000",1142))).isEqualTo(Failure.DATABASE_PERMISSION);
        assertThat(OwnerDiagnostics.classify(new SQLException(sensitive,"42S02",1146))).isEqualTo(Failure.MIGRATIONS_MISSING);
        assertThat(OwnerDiagnostics.classify(new SQLException(sensitive,"42000",1049))).isEqualTo(Failure.DATABASE_MISMATCH);
        assertThat(OwnerDiagnostics.classify(new IllegalStateException(sensitive,new SSLHandshakeException(sensitive)))).isEqualTo(Failure.TLS_FAILED);
        assertThat(OwnerDiagnostics.classify(new java.net.ConnectException(sensitive))).isEqualTo(Failure.CONNECTION_FAILED);
        assertThat(OwnerDiagnostics.classify(new IllegalStateException(sensitive))).isEqualTo(Failure.OPERATOR_STARTUP);
        for(var reason:Failure.values()) assertThat(reason.explanation).doesNotContain(sensitive,"do-not-render");
    }
}
