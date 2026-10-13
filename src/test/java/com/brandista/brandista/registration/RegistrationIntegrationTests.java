package com.brandista.brandista.registration;

import com.brandista.brandista.user.AccountStatus;
import com.brandista.brandista.user.User;
import com.brandista.brandista.user.UserRepository;
import com.brandista.brandista.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:brandista-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
class RegistrationIntegrationTests {

    private static final String REGISTRATION_URL = "/api/v1/registrations";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registersNormalizedCustomerAndReturnsOnlyPublicFields() throws Exception {
        String email = "  Registration+" + UUID.randomUUID() + "@Example.COM  ";
        String password = "Strong-Test-Password-2026!";

        ResponseEntity<String> response = register(email, password, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(response.getBody());
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);
        assertThat(body.get("id").asLong()).isPositive();
        assertThat(body.get("email").asText()).isEqualTo(normalizedEmail);
        assertThat(body.get("createdAt").isTextual()).isTrue();
        assertThat(body.has("password")).isFalse();
        assertThat(body.has("passwordHash")).isFalse();
        assertThat(response.getBody()).doesNotContain(password);

        User savedUser = userRepository.findByEmail(normalizedEmail).orElseThrow();
        assertThat(savedUser.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(savedUser.getStatus()).isEqualTo(AccountStatus.ENABLED);
        assertThat(savedUser.getPasswordHash()).isNotEqualTo(password);
        assertThat(passwordEncoder.matches(password, savedUser.getPasswordHash())).isTrue();
    }

    @Test
    void rejectsInvalidEmailWithSafeValidationBody() {
        String password = "Strong-Test-Password-2026!";
        ResponseEntity<String> response = register("not-an-email", password, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Request validation failed", "email");
        assertThat(response.getBody()).doesNotContain(password);
    }

    @Test
    void rejectsBlankAndWeakPasswords() {
        String email = "password-policy-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<String> blankResponse = register(email, "   ", null);
        ResponseEntity<String> weakResponse = register(email, "short123!", null);

        assertThat(blankResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(weakResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blankResponse.getBody()).doesNotContain("   ");
        assertThat(weakResponse.getBody()).doesNotContain("short123!");
    }

    @Test
    void rejectsDuplicateNormalizedEmailWithConflict() {
        String email = "duplicate-" + UUID.randomUUID() + "@example.com";
        String password = "Strong-Test-Password-2026!";
        assertThat(register(" " + email.toUpperCase(java.util.Locale.ROOT) + " ", password, null)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> duplicate = register(email, password, null);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).contains("Account identifier is already registered.");
        assertThat(duplicate.getBody()).doesNotContain(password, "passwordHash");
    }

    @Test
    void ignoresClientSuppliedRoleAndAlwaysCreatesCustomer() {
        String email = "role-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<String> response = register(
                email,
                "Strong-Test-Password-2026!",
                "ADMIN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        User savedUser = userRepository.findByEmail(email).orElseThrow();
        assertThat(savedUser.getRole()).isEqualTo(UserRole.CUSTOMER);
    }

    private ResponseEntity<String> register(String email, String password, String requestedRole) {
        Map<String, String> requestBody = new LinkedHashMap<>();
        requestBody.put("email", email);
        requestBody.put("password", password);
        if (requestedRole != null) {
            requestBody.put("role", requestedRole);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                REGISTRATION_URL,
                HttpMethod.POST,
                new HttpEntity<>(requestBody, headers),
                String.class);
    }
}
