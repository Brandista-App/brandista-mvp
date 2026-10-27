package com.brandista.brandista.privilege;

import com.brandista.brandista.user.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:auth-unavailable;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password="})
class CurrentAccountAvailabilityIntegrationTests {
    @MockitoBean UserRepository users;
    @Autowired TestRestTemplate http;
    @Autowired JwtEncoder encoder;
    @Autowired com.brandista.brandista.config.JwtProperties properties;
    String token() {
        var now=Instant.now();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(properties.audience()))
                .subject("42").issuedAt(now).expiresAt(now.plusSeconds(300)).build())).getTokenValue();
    }
    ResponseEntity<String> get(String token) {
        var headers=new HttpHeaders();headers.setBearerAuth(token);
        return http.exchange("/api/v1/super-admin/identity",HttpMethod.GET,new HttpEntity<>(headers),String.class);
    }
    @Test void poolAndTransactionFailuresDenyAccessWithSanitized503AndRecoverWithoutCaching() {
        String token=token();
        for(RuntimeException failure:List.of(
            new org.springframework.dao.DataAccessResourceFailureException("jdbc:mysql://secret/password=private"),
            new org.springframework.transaction.CannotCreateTransactionException("private transaction details"))) {
            doThrow(failure).when(users).findCurrentAuthorizationById(42L);
            var response=get(token);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).contains("\"status\":503","Shopping service is temporarily unavailable.")
                .doesNotContain("jdbc:","password=","private","stackTrace");
        }
        doReturn(Optional.of(new CurrentAuthorization(UserRole.CUSTOMER,AccountStatus.ENABLED,false))).when(users).findCurrentAuthorizationById(42L);
        assertThat(get(token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(users,times(3)).findCurrentAuthorizationById(42L);
        verifyNoMoreInteractions(users);
    }
    @Test void invalidJwtStillReturns401BeforeAnyAccountLookup() {
        assertThat(get("invalid.jwt.signature").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(users);
    }
}
