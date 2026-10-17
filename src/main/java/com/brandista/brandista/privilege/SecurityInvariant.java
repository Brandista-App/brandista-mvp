package com.brandista.brandista.privilege;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class SecurityInvariant {
    private final JdbcTemplate jdbc;
    public SecurityInvariant(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Must be called inside the mutation transaction, before reading security state. */
    public void lock() {
        jdbc.queryForObject("SELECT id FROM security_provisioning_state WHERE id=1 FOR UPDATE", Integer.class);
    }

    public List<Long> activeSuperAdmins() {
        // Locking reads see current committed state even under MySQL REPEATABLE READ.
        return jdbc.queryForList("SELECT u.id FROM users u JOIN user_platform_grants g ON g.user_id=u.id "
                + "WHERE u.status='ENABLED' AND g.grant_type='SUPER_ADMIN' AND g.revoked_at IS NULL FOR UPDATE", Long.class);
    }

    public boolean wouldRemoveLast(long target) {
        List<Long> active = activeSuperAdmins();
        Set<Long> pending = pendingRemovals().ids;
        long remaining = active.stream().filter(id -> !pending.contains(id)).count();
        return active.contains(target) && !pending.contains(target) && remaining <= 1;
    }

    public void removalScheduled(long target) { pendingRemovals().ids.add(target); }

    private PendingRemovals pendingRemovals() {
        // PreRemove fires before deferred/batched SQL. Count removals already scheduled in
        // this transaction too, otherwise deleting/disabling two entities could remove both.
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations())
            if (synchronization instanceof PendingRemovals pending) return pending;
        var pending = new PendingRemovals();
        TransactionSynchronizationManager.registerSynchronization(pending);
        return pending;
    }

    private static class PendingRemovals implements TransactionSynchronization {
        private final Set<Long> ids = new HashSet<>();
    }
}
