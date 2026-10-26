package com.brandista.brandista.privilege;

import com.brandista.brandista.user.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:current-authorization;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
class CurrentAuthorizationLookupIntegrationTests {
    @Autowired UserRepository users;
    @Autowired PlatformGrantRepository grants;
    @Autowired CurrentAccountAuthorities converter;
    @Autowired EntityManagerFactory factory;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    User user() {return users.saveAndFlush(new User(UUID.randomUUID()+"@example.invalid","synthetic-unused-hash",UserRole.CUSTOMER,AccountStatus.ENABLED));}
    Jwt token(String subject) {
        return Jwt.withTokenValue("test-only").header("alg","HS256").subject(subject)
                .claim("role","SUPER_ADMIN").claim("grants",java.util.List.of("SUPER_ADMIN")).build();
    }
    @Test void oneScalarReadUsesCurrentRoleStatusAndGrantOnEveryConversion() {
        var custodian=user();grants.saveAndFlush(new PlatformGrant(custodian,PlatformGrantType.SUPER_ADMIN));
        var subject=user();var grant=grants.saveAndFlush(new PlatformGrant(subject,PlatformGrantType.SUPER_ADMIN));
        Jwt jwt=token(subject.getId().toString());
        var stats=factory.unwrap(SessionFactory.class).getStatistics();stats.clear();
        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_CUSTOMER","ROLE_SUPER_ADMIN");
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
        assertThat(stats.getEntityLoadCount()).isZero();

        grant.revoke();grants.saveAndFlush(grant);
        stats.clear();
        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_CUSTOMER");
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);

        subject.changeRole(UserRole.ADMIN);subject=users.saveAndFlush(subject);
        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
        subject.changeStatus(AccountStatus.DISABLED);users.saveAndFlush(subject);
        assertThatThrownBy(()->converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
    }
    @Test void malformedSubjectsDoNotStartTransactionsOrQueries() {
        var stats=factory.unwrap(SessionFactory.class).getStatistics();
        for(String subject:new String[]{"","0","-1","abc","999999999999999999999999"}) {
            stats.clear();
            assertThatThrownBy(()->converter.convert(token(subject))).isInstanceOf(InvalidBearerTokenException.class);
            assertThat(stats.getPrepareStatementCount()).isZero();
            assertThat(stats.getTransactionCount()).isZero();
        }
    }
    @Test void missingAccountIsDeniedAfterOneFreshRead() {
        var stats=factory.unwrap(SessionFactory.class).getStatistics();stats.clear();
        assertThatThrownBy(()->converter.convert(token(Long.toString(Long.MAX_VALUE)))).isInstanceOf(InvalidBearerTokenException.class);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }
    @Test void passwordWorkFactorAndVerificationRemainUnchanged() {
        String password="Perf002-Synthetic-Password!";
        String encoded=passwords.encode(password);
        assertThat(encoded.substring(4,6)).isEqualTo("10");
        assertThat(passwords.matches(password,encoded)).isTrue();
        assertThat(passwords.matches("wrong-password",encoded)).isFalse();
    }
}
