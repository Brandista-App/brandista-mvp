package com.brandista.operator;

import com.brandista.brandista.privilege.OperatorProvisioning;
import com.brandista.brandista.privilege.SecurityAudit;
import com.brandista.brandista.registration.validation.PasswordPolicyValidator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.brandista.operator.OwnerDiagnostics.Failure;

/** Interactive fixed-owner setup. No Spring web context, public endpoint, stdin secret pipe or automatic retry. */
public final class OwnerProvisioningMain {
    private OwnerProvisioningMain() { }
    record Options(String host, int port, Path truststore, boolean verifyOnly) {
        static Options parse(String[] args) {
            Map<String,String> values = new HashMap<>();
            boolean verify = false;
            for (int i=0; i<args.length; i++) {
                if ("--verify-only".equals(args[i])) { verify = true; continue; }
                if (!java.util.Set.of("--host","--port","--truststore").contains(args[i]) || i+1>=args.length
                        || values.put(args[i],args[++i]) != null) throw new IllegalArgumentException();
            }
            String host = values.getOrDefault("--host", "");
            int port = Integer.parseInt(values.getOrDefault("--port", "0"));
            if (!host.matches("[a-zA-Z0-9.-]+") || port<1 || port>65535 || !values.containsKey("--truststore"))
                throw new IllegalArgumentException();
            return new Options(host, port, Path.of(values.get("--truststore")).toAbsolutePath(), verify);
        }
    }
    static final class Stop extends RuntimeException {
        final Failure failure;
        Stop(Failure failure) { this.failure = failure; }
    }
    public static void main(String[] args) {
        Console console = System.console();
        if (console == null) {
            System.err.println("CONSOLE_UNAVAILABLE: " + Failure.CONSOLE_UNAVAILABLE.explanation);
            System.exit(Failure.CONSOLE_UNAVAILABLE.exit);
            return;
        }
        Options options;
        try { options = Options.parse(args); }
        catch (RuntimeException invalid) {
            console.printf("INPUT_INVALID: Launch scripts/production-owner.ps1; passwords are never accepted as arguments.%n");
            System.exit(Failure.INPUT_INVALID.exit); return;
        }
        System.exit(run(console, options));
    }
    static int run(Console console, Options options) {
        boolean mutationAttempted = false;
        char[] databasePassword = null;
        char[] databaseUsername = null;
        char[] storePassword = null;
        char[] password = null;
        char[] confirmation = null;
        // Silence libraries before connecting: no raw driver exception or connection properties reach the terminal.
        LoggingSystem logging = LoggingSystem.get(OwnerProvisioningMain.class.getClassLoader());
        logging.setLogLevel(null, LogLevel.OFF);
        logging.setLogLevel("com.zaxxer.hikari", LogLevel.OFF);
        logging.setLogLevel("org.springframework.jdbc", LogLevel.OFF);
        logging.setLogLevel("com.mysql", LogLevel.OFF);
        try {
            console.printf("Brandista owner setup. Target: %s:%d / brandista. TLS: VERIFY_IDENTITY.%n", options.host(),options.port());
            storePassword = console.readPassword("Truststore password (hidden): ");
            validateTruststore(options.truststore(), storePassword);
            console.printf("TRUSTSTORE_OK: PKCS12 CA truststore unlocked and certificate validity checked.%n");
            if (!"CONNECT brandista".equals(console.readLine("Type CONNECT brandista to approve read-only verification: "))) {
                console.printf("CANCELLED: No database connection or account change was performed.%n"); return 12;
            }
            databaseUsername = console.readPassword("Aiven database username (hidden): ");
            String username = databaseUsername == null ? null : new String(databaseUsername);
            databasePassword = console.readPassword("Aiven database password (hidden): ");
            if (username == null || username.isBlank() || databasePassword == null || databasePassword.length==0)
                throw new Stop(Failure.INPUT_INVALID);
            try (var datasource = datasource(options, username, databasePassword, storePassword)) {
                var jdbc = new JdbcTemplate(datasource);
                verifyDatabase(jdbc);
                console.printf("DATABASE_OK: Connected to actual database brandista using verified TLS.%n");
                verifySchema(jdbc);
                var existing = OwnerAccountInspection.inspect(jdbc);
                if (existing != null) {
                    console.printf("ACCOUNT_EXISTS: %s%n", existing);
                    if (existing.ready()) {
                        console.printf("SUCCESS: uzair@brandistaapp.com is persisted in brandista; ENABLED, BCrypt format verified, SUPER_ADMIN grant active. No account changes were made.%n"); return 0;
                    }
                    console.printf("ACCOUNT_NOT_READY: Existing account was left unchanged. A separately approved recovery or grant operation is required.%n"); return 11;
                }
                console.printf("ACCOUNT_ABSENT: uzair@brandistaapp.com does not exist in brandista. Read-only verification completed.%n");
                if (options.verifyOnly()) return 10;
                if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT initialized_at IS NOT NULL FROM security_provisioning_state WHERE id=1", Boolean.class))
                        || jdbc.queryForObject("SELECT COUNT(*) FROM user_platform_grants", Long.class)>0) {
                    console.printf("INITIALIZATION_BLOCKED: Prior bootstrap/grant history exists. No account created; separately approve recovery instead.%n"); return 30;
                }
                if (!"CREATE uzair@brandistaapp.com IN brandista".equals(console.readLine(
                        "To explicitly authorize creation, type CREATE uzair@brandistaapp.com IN brandista: "))) {
                    console.printf("CANCELLED: Read-only verification completed; no account changes were made.%n"); return 12;
                }
                password = console.readPassword("New Super Admin password (hidden; 12+ characters, maximum 72 UTF-8 bytes): ");
                confirmation = console.readPassword("Confirm new Super Admin password (hidden): ");
                if (password == null || confirmation == null || !Arrays.equals(password, confirmation) || new String(password).isBlank()
                        || !new PasswordPolicyValidator().isValid(new String(password), null)) throw new Stop(Failure.INPUT_INVALID);
                var app = new SpringApplication(BootstrapMain.OperatorConfiguration.class);
                app.setWebApplicationType(WebApplicationType.NONE);
                app.setLogStartupInfo(false);
                app.setRegisterShutdownHook(false);
                app.addInitializers(ctx -> ctx.getBeanFactory().registerSingleton("dataSource", datasource));
                try (var context = app.run("--spring.main.web-application-type=none", "--spring.flyway.enabled=false",
                        "--spring.sql.init.mode=never", "--logging.level.root=OFF", "--logging.level.org.springframework.jdbc=OFF",
                        "--logging.level.com.zaxxer.hikari=OFF", "--spring.main.banner-mode=off",
                        "--brandista.security.provisioning.enabled=true", "--brandista.security.provisioning.recovery-enabled=false")) {
                    mutationAttempted = true;
                    var result = context.getBean(OperatorProvisioning.class).execute(OperatorProvisioning.Mode.INITIAL_CREATE,
                            OwnerAccountInspection.EMAIL, null, password);
                    console.printf("PROVISIONING_RESULT: %s / %s; target ID: %s%n", result.outcome(), result.reason(), result.targetUserId());
                    if (result.outcome() == SecurityAudit.Outcome.FAILED) throw new Stop(Failure.STATE_UNCERTAIN);
                    if (result.outcome() != SecurityAudit.Outcome.SUCCESS) {
                        console.printf("PROVISIONING_REJECTED: %s%n", Failure.PROVISIONING_REJECTED.explanation);
                        return Failure.PROVISIONING_REJECTED.exit;
                    }
                    var persisted = OwnerAccountInspection.inspect(jdbc);
                    if (persisted == null || !persisted.ready() || persisted.id()!=result.targetUserId()) throw new Stop(Failure.STATE_UNCERTAIN);
                    console.printf("SUCCESS: uzair@brandistaapp.com was created and verified in brandista; ENABLED, BCrypt format verified, SUPER_ADMIN grant active. User ID: %d.%n",persisted.id());
                    return 0;
                }
            }
        } catch (Exception failure) {
            Failure reason = failure instanceof Stop stop ? stop.failure : OwnerDiagnostics.classify(failure);
            if (mutationAttempted) reason = Failure.STATE_UNCERTAIN;
            console.printf("%s: %s%n",reason.name(),reason.explanation);
            if (!mutationAttempted) console.printf("NO_ACCOUNT_CHANGES: Failure occurred before any provisioning operation.%n");
            return reason.exit;
        } finally {
            for (char[] secret : new char[][]{databaseUsername,databasePassword,storePassword,password,confirmation}) if(secret!=null) Arrays.fill(secret,'\0');
        }
    }
    static void validateTruststore(Path path, char[] password) {
        try {
            if (password==null || !Files.isRegularFile(path)) throw new IllegalArgumentException();
            KeyStore store = KeyStore.getInstance("PKCS12");
            try(var input=Files.newInputStream(path)) { store.load(input,password); }
            if(store.size()==0) throw new IllegalArgumentException();
            for(var aliases=store.aliases(); aliases.hasMoreElements();) {
                String alias=aliases.nextElement();
                if(!store.isCertificateEntry(alias) || !(store.getCertificate(alias) instanceof X509Certificate cert)
                        || cert.getBasicConstraints()<0) throw new IllegalArgumentException();
                cert.checkValidity();
            }
        } catch(Exception invalid) { throw new Stop(Failure.TRUSTSTORE_INVALID); }
    }
    static HikariDataSource datasource(Options options, String username, char[] password, char[] storePassword) {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://"+options.host()+":"+options.port()+"/brandista?sslMode=VERIFY_IDENTITY&connectTimeout=5000&socketTimeout=10000");
        config.setUsername(username); config.setPassword(new String(password));
        // Driver property spelling is preserved directly; no JSON / relaxed-binding round trip.
        config.addDataSourceProperty("trustCertificateKeyStoreUrl", options.truststore().toUri().toASCIIString());
        config.addDataSourceProperty("trustCertificateKeyStoreType", "PKCS12");
        config.addDataSourceProperty("trustCertificateKeyStorePassword", new String(storePassword));
        config.addDataSourceProperty("fallbackToSystemTrustStore", "false");
        config.setMaximumPoolSize(2); config.setMinimumIdle(0); config.setConnectionTimeout(10000); config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }
    static void verifyDatabase(JdbcTemplate jdbc) {
        if(!"brandista".equals(jdbc.queryForObject("SELECT DATABASE()",String.class))) throw new Stop(Failure.DATABASE_MISMATCH);
        String cipher = jdbc.queryForObject("SHOW SESSION STATUS LIKE 'Ssl_cipher'", (rs,row) -> rs.getString(2));
        if(cipher==null || cipher.isBlank()) throw new Stop(Failure.TLS_FAILED);
    }
    static void verifySchema(JdbcTemplate jdbc) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM `flyway_schema_history` WHERE `success`=true AND `version` IN "
                +"('1','2','3','4','5','6','7','8','9','10','11','12','13','14')",Integer.class);
        if(count==null || count!=14) throw new Stop(Failure.MIGRATIONS_MISSING);
    }
}
