package com.brandista.brandista.privilege;

import com.brandista.brandista.user.AccountStatus;
import com.brandista.brandista.user.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Collection;

@Component
public class CurrentAccountAuthorities implements Converter<Jwt, Collection<GrantedAuthority>> {
    private final UserRepository users;

    public CurrentAccountAuthorities(UserRepository users) {
        this.users = users;
    }

    /** Signature/issuer/audience/expiry were checked by JwtDecoder before this conversion. */
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        final long id;
        try {
            if (jwt.getSubject() == null || !jwt.getSubject().matches("[1-9][0-9]*"))
                throw new NumberFormatException();
            id = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw invalidAccount();
        }
        // One fresh scalar read needs no surrounding multi-statement transaction.
        // Keep status, role and grant verification on every bearer request.
        final com.brandista.brandista.user.CurrentAuthorization account;
        try {
            account = users.findCurrentAuthorizationById(id).orElseThrow(CurrentAccountAuthorities::invalidAccount);
        } catch (org.springframework.dao.DataAccessResourceFailureException |
                 org.springframework.transaction.CannotCreateTransactionException unavailable) {
            // Fail closed through the bearer filter; MVC advice cannot handle this stage.
            throw new CurrentAccountUnavailableException(unavailable);
        }
        if (account.status() != AccountStatus.ENABLED) throw invalidAccount();

        // Never derive authority from role/roles/scope/grants or identifiers in client input.
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + account.role().name()));
        if (account.superAdmin())
            authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        return authorities;
    }

    private static InvalidBearerTokenException invalidAccount() {
        return new InvalidBearerTokenException("Current account is not eligible for authentication.");
    }
}
