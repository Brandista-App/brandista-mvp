package com.brandista.brandista.privilege;

import com.brandista.brandista.user.AccountStatus;
import com.brandista.brandista.user.User;
import com.brandista.brandista.user.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Collection;

@Component
public class CurrentAccountAuthorities implements Converter<Jwt, Collection<GrantedAuthority>> {
    private final UserRepository users;
    private final PlatformGrantRepository grants;

    public CurrentAccountAuthorities(UserRepository users, PlatformGrantRepository grants) {
        this.users = users;
        this.grants = grants;
    }

    /** Signature/issuer/audience/expiry were checked by JwtDecoder before this conversion. */
    @Override
    @Transactional(readOnly = true)
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        final long id;
        try {
            if (jwt.getSubject() == null || !jwt.getSubject().matches("[1-9][0-9]*"))
                throw new NumberFormatException();
            id = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw invalidAccount();
        }
        User user = users.findById(id).orElseThrow(CurrentAccountAuthorities::invalidAccount);
        if (user.getStatus() != AccountStatus.ENABLED) throw invalidAccount();

        // Never derive authority from role/roles/scope/grants or identifiers in client input.
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        if (grants.existsByUserIdAndGrantTypeAndRevokedAtIsNull(id, PlatformGrantType.SUPER_ADMIN))
            authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        return authorities;
    }

    private static InvalidBearerTokenException invalidAccount() {
        return new InvalidBearerTokenException("Current account is not eligible for authentication.");
    }
}
