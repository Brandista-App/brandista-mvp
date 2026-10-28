package com.brandista.operator;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import javax.net.ssl.SSLException;

/** Closed diagnostics only. Driver messages and configuration values are never rendered. */
public final class OwnerDiagnostics {
    private OwnerDiagnostics() { }
    public enum Failure {
        CONSOLE_UNAVAILABLE(20, "A real interactive Windows terminal is required; no passwords were requested."),
        INPUT_INVALID(21, "Check the host, port and password policy; no operation was performed."),
        TRUSTSTORE_INVALID(22, "Cannot unlock or validate the PKCS12 CA truststore. Check its password, CA validity and file access."),
        TLS_FAILED(23, "Database TLS certificate or hostname verification failed. Check the approved service CA and DNS hostname. TLS was not weakened."),
        DATABASE_CREDENTIALS(24, "The database rejected the username or password."),
        CONNECTION_FAILED(25, "Cannot reach the database. Check the approved hostname, port, network allowlist and service availability."),
        MIGRATIONS_MISSING(26, "Required tables, columns or successful Flyway V1-V14 history are missing. This tool never runs migrations."),
        DATABASE_MISMATCH(27, "The connected database is not brandista, or that database does not exist. No account operation was performed."),
        DATABASE_PERMISSION(28, "The database identity lacks required permissions for this operation."),
        OPERATOR_STARTUP(29, "The offline application could not initialize. Use the reviewed launcher and its matching rebuilt JAR."),
        PROVISIONING_REJECTED(30, "Provisioning was rejected. No automatic recovery, grant change or retry was attempted."),
        STATE_UNCERTAIN(31, "An operation was attempted but final state could not be verified. Do not retry creation; run the same command with -VerifyOnly first.");
        public final int exit;
        public final String explanation;
        Failure(int exit, String explanation) { this.exit = exit; this.explanation = explanation; }
    }
    public static Failure classify(Throwable error) {
        // Prefer TLS over a surrounding connection exception, without parsing sensitive messages.
        for (Throwable t = error; t != null; t = t.getCause()) if (t instanceof SSLException) return Failure.TLS_FAILED;
        boolean connectionFailure = false;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                if (sql.getErrorCode() == 1049) return Failure.DATABASE_MISMATCH;
                if (sql.getErrorCode() == 1045 || "28000".equals(sql.getSQLState())) return Failure.DATABASE_CREDENTIALS;
                if (sql.getErrorCode() == 1044 || sql.getErrorCode() == 1142 || sql.getErrorCode() == 1143 || sql.getErrorCode() == 1227)
                    return Failure.DATABASE_PERMISSION;
                if (sql.getErrorCode() == 1146 || sql.getErrorCode() == 1054 || "42S02".equals(sql.getSQLState()))
                    return Failure.MIGRATIONS_MISSING;
                if (sql.getSQLState() != null && sql.getSQLState().startsWith("08")) connectionFailure = true;
            }
            if (t instanceof UnknownHostException || t instanceof ConnectException || t instanceof SocketTimeoutException)
                connectionFailure = true;
        }
        return connectionFailure ? Failure.CONNECTION_FAILED : Failure.OPERATOR_STARTUP;
    }
}
