package com.brandista.brandista.merchant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Current DB state, not JWT role/tenant claims, is the authorization source. */
@Component
public class BrandAccess {
    private final JdbcTemplate jdbc;
    public BrandAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) throw denied();
        String subject = token.getToken().getSubject();
        final long id;
        try {
            if (subject == null || !subject.matches("[1-9][0-9]*")) throw new NumberFormatException();
            id = Long.parseLong(subject);
        } catch (NumberFormatException invalid) { throw denied(); }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND status='ENABLED'", Long.class, id) != 1)
            throw denied();
        return id;
    }

    public long requireBrand(long brandId) {
        long user = currentUser();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM brand_admin_memberships m JOIN brands b ON b.id=m.brand_id "
                + "WHERE m.user_id=? AND m.brand_id=? AND m.revoked_at IS NULL AND b.status='ACTIVE'",
                Long.class, user, brandId) != 1) throw denied();
        return user;
    }

    public void requireSuperAdmin() {
        long user = currentUser();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM user_platform_grants WHERE user_id=? "
                + "AND grant_type='SUPER_ADMIN' AND revoked_at IS NULL", Long.class, user) != 1) throw denied();
    }

    static AccessDeniedException denied() { return new AccessDeniedException("Access is forbidden."); }
}
