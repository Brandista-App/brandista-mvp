package com.brandista.operator;

import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only projection: credential material never leaves the database. */
public final class OwnerAccountInspection {
    public static final String EMAIL = "uzair@brandistaapp.com";
    private OwnerAccountInspection() { }

    public record State(long id, String email, String baseRole, String status,
                        boolean bcrypt, boolean superAdmin) {
        public boolean ready() { return EMAIL.equals(email) && "ENABLED".equals(status) && bcrypt && superAdmin; }
    }

    public static State inspect(JdbcTemplate jdbc) {
        var rows = jdbc.query("""
                SELECT u.id,u.email,u.role,u.status,
                    CASE WHEN CHAR_LENGTH(u.password_hash)=60 AND
                        (u.password_hash LIKE '$2a$%' OR u.password_hash LIKE '$2b$%' OR
                         u.password_hash LIKE '$2y$%') THEN 1 ELSE 0 END AS bcrypt,
                    CASE WHEN EXISTS (SELECT 1 FROM user_platform_grants g WHERE g.user_id=u.id
                        AND g.grant_type='SUPER_ADMIN' AND g.revoked_at IS NULL) THEN 1 ELSE 0 END AS super_admin
                FROM users u WHERE u.email=?
                """, (rs, row) -> new State(rs.getLong("id"), rs.getString("email"), rs.getString("role"),
                rs.getString("status"), rs.getBoolean("bcrypt"), rs.getBoolean("super_admin")), EMAIL);
        if (rows.size() > 1) throw new IllegalStateException("Account uniqueness check failed");
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
