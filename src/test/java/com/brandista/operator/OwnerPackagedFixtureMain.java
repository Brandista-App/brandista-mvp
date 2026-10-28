package com.brandista.operator;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

/** Test classes only: cannot run against any non-loopback database. Never packaged in the operator JAR. */
public final class OwnerPackagedFixtureMain {
    public static void main(String[] args) {
        LoggingSystem.get(OwnerPackagedFixtureMain.class.getClassLoader()).setLogLevel(null,LogLevel.OFF);
        try {
            String port=System.getenv("OWNER_FIXTURE_PORT");
            if(port==null || !port.matches("[1-9][0-9]{0,4}")) throw new IllegalArgumentException();
            var options=new OwnerProvisioningMain.Options("localhost",Integer.parseInt(port),Path.of(System.getenv("OWNER_FIXTURE_STORE")),true);
            char[] password=System.getenv("OWNER_FIXTURE_PASSWORD").toCharArray();
            char[] storePassword=System.getenv("OWNER_FIXTURE_STORE_PASSWORD").toCharArray();
            OwnerProvisioningMain.validateTruststore(options.truststore(),storePassword);
            try(var datasource=OwnerProvisioningMain.datasource(options,"root",password,storePassword)) {
                Flyway.configure().dataSource(datasource).load().migrate();
            } finally { java.util.Arrays.fill(password,'\0'); java.util.Arrays.fill(storePassword,'\0'); }
            System.out.println("PASS: isolated loopback fixture migrated with verified TLS");
        } catch(Exception failure) {
            System.err.println("Isolated fixture failed: "+OwnerDiagnostics.classify(failure)); System.exit(1);
        }
    }
}
