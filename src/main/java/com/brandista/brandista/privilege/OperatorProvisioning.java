package com.brandista.brandista.privilege;

import com.brandista.brandista.registration.validation.PasswordPolicyValidator;
import com.brandista.brandista.user.EmailNormalizer;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import static com.brandista.brandista.privilege.SecurityAudit.*;

/** Offline-only operations. Never injected into a controller or invoked during app startup. */
@Service
public class OperatorProvisioning {
    public enum Mode {
        INITIAL_CREATE, INITIAL_GRANT, RECOVER_CREATE, RECOVER_GRANT, RESET_PASSWORD, REVOKE, DISABLE;
        public boolean needsPassword() { return this == INITIAL_CREATE || this == RECOVER_CREATE || this == RESET_PASSWORD; }
        boolean initial() { return this == INITIAL_CREATE || this == INITIAL_GRANT; }
        boolean create() { return this == INITIAL_CREATE || this == RECOVER_CREATE; }
    }
    public record Result(Outcome outcome, Reason reason, Long targetUserId) { }
    private record Identity(@NotBlank @Email @Size(max=254) String email) { }

    private final JdbcTemplate jdbc;
    private final SecurityInvariant invariant;
    private final SecurityAudit audit;
    private final PasswordEncoder passwords;
    private final Validator validator;
    private final boolean enabled;
    private final boolean recoveryEnabled;
    private final TransactionTemplate transaction;

    public OperatorProvisioning(JdbcTemplate jdbc, SecurityInvariant invariant, SecurityAudit audit,
            PasswordEncoder passwords, Validator validator, PlatformTransactionManager transactions,
            @Value("${brandista.security.provisioning.enabled:false}") boolean enabled,
            @Value("${brandista.security.provisioning.recovery-enabled:false}") boolean recoveryEnabled) {
        this.jdbc = jdbc; this.invariant = invariant; this.audit = audit;
        this.passwords = passwords; this.validator = validator;
        this.enabled = enabled; this.recoveryEnabled = recoveryEnabled;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(30);
    }

    /** expectedId is mandatory for existing accounts: email alone never silently promotes one. */
    public Result execute(Mode mode, String email, Long expectedId, char[] password) {
        Objects.requireNonNull(mode);
        Operation operation = Operation.valueOf(mode.name());
        try {
            return transaction.execute(status -> perform(mode, operation, EmailNormalizer.normalize(email), expectedId, password));
        } catch (RuntimeException failure) {
            // No exception messages/causes or request fields: driver errors may contain secrets.
            try {
                audit.failedIndependently(operation, expectedId);
            } catch (RuntimeException unavailable) {
                throw new IllegalStateException("Provisioning failed; audit storage unavailable. Check database health securely.");
            }
            return new Result(Outcome.FAILED, Reason.STORAGE_FAILURE, expectedId);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private Result perform(Mode mode, Operation operation, String email, Long expectedId, char[] password) {
        if (!enabled) return reject(operation, expectedId, Reason.DISABLED);
        if (!mode.initial() && !recoveryEnabled) return reject(operation, expectedId, Reason.RECOVERY_DISABLED);
        if (!validator.validate(new Identity(email)).isEmpty()) return reject(operation, expectedId, Reason.INVALID_INPUT);
        String secret = password == null ? null : new String(password);
        if (mode.needsPassword() && (secret == null || secret.isBlank()
                || !new PasswordPolicyValidator().isValid(secret, null)))
            return reject(operation, expectedId, Reason.INVALID_INPUT);
        if (!mode.create() && (expectedId == null || expectedId <= 0))
            return reject(operation, expectedId, Reason.IDENTITY_MISMATCH);

        invariant.lock();
        if (mode.initial()) {
            var initialized = jdbc.queryForList("SELECT initialized_at FROM security_provisioning_state WHERE id=1").getFirst();
            // Existing grants (including revoked ones) also permanently disqualify first bootstrap.
            if (initialized.get("initialized_at") != null
                    || !jdbc.queryForList("SELECT id FROM user_platform_grants FOR UPDATE").isEmpty())
                return reject(operation, expectedId, Reason.ALREADY_INITIALIZED);
        }
        var users = jdbc.queryForList("SELECT id,status FROM users WHERE email=? FOR UPDATE", email);
        long target;
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        if (mode.create()) {
            if (!users.isEmpty()) return reject(operation, null, Reason.IDENTITY_EXISTS);
            String hash = passwords.encode(secret);
            var keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO users(email,password_hash,role,status,created_at,updated_at) "
                        + "VALUES (?,?,'CUSTOMER','ENABLED',?,?)", Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, email); statement.setString(2, hash);
                statement.setObject(3, now); statement.setObject(4, now);
                return statement;
            }, keys);
            target = Objects.requireNonNull(keys.getKey()).longValue();
        } else {
            if (users.isEmpty()) return reject(operation, expectedId, Reason.NOT_FOUND);
            target = ((Number) users.getFirst().get("id")).longValue();
            if (target != expectedId) return reject(operation, expectedId, Reason.IDENTITY_MISMATCH);
            if (!"ENABLED".equals(users.getFirst().get("status")))
                return reject(operation, target, Reason.ACCOUNT_DISABLED);
        }
        if (mode == Mode.REVOKE || mode == Mode.DISABLE) {
            if (mode == Mode.REVOKE && !invariant.activeSuperAdmins().contains(target))
                return reject(operation, target, Reason.NOT_GRANTED);
            if (mode == Mode.DISABLE && jdbc.queryForList(
                    "SELECT id FROM user_platform_grants WHERE user_id=? FOR UPDATE", target).isEmpty())
                return reject(operation, target, Reason.NOT_GRANTED);
            if (invariant.wouldRemoveLast(target)) return reject(operation, target, Reason.LAST_SUPER_ADMIN);
            if (mode == Mode.REVOKE) {
                int changed = jdbc.update("UPDATE user_platform_grants SET revoked_at=?,version=version+1 "
                        + "WHERE user_id=? AND grant_type='SUPER_ADMIN' AND revoked_at IS NULL", now, target);
                if (changed == 0) return reject(operation, target, Reason.NOT_GRANTED);
            } else {
                jdbc.update("UPDATE users SET status='DISABLED',updated_at=? WHERE id=?", now, target);
            }
        } else if (mode == Mode.RESET_PASSWORD) {
            // Recovery cannot reset a random customer's password or silently grant privileges.
            if (!invariant.activeSuperAdmins().contains(target)) return reject(operation, target, Reason.NOT_GRANTED);
            jdbc.update("UPDATE users SET password_hash=?,updated_at=? WHERE id=?", passwords.encode(secret), now, target);
        } else {
            var grants = jdbc.queryForList("SELECT id,revoked_at FROM user_platform_grants WHERE user_id=? FOR UPDATE", target);
            if (grants.isEmpty()) {
                jdbc.update("INSERT INTO user_platform_grants(user_id,grant_type,created_at,version) VALUES (?,'SUPER_ADMIN',?,0)", target, now);
            } else {
                if (grants.getFirst().get("revoked_at") == null) return reject(operation, target, Reason.ALREADY_GRANTED);
                jdbc.update("UPDATE user_platform_grants SET revoked_at=NULL,version=version+1 WHERE user_id=?", target);
            }
        }
        jdbc.update("UPDATE security_provisioning_state SET initialized_at=COALESCE(initialized_at,?) WHERE id=1", now);
        audit.append(operation, target, Outcome.SUCCESS, Reason.COMPLETED);
        return new Result(Outcome.SUCCESS, Reason.COMPLETED, target);
    }

    private Result reject(Operation operation, Long target, Reason reason) {
        audit.append(operation, target, Outcome.REJECTED, reason);
        return new Result(Outcome.REJECTED, reason, target);
    }
}
