package com.brandista.brandista.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        properties = {
                "spring.datasource.url=jdbc:h2:mem:brandista-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
class UserRepositoryIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Test
    void persistsNormalizedEmailRoleStatusAndTimestamps() {
        User user = userRepository.saveAndFlush(new User(
                "  ADMIN+" + UUID.randomUUID() + "@Example.COM ",
                "$2a$test-hash-value",
                UserRole.ADMIN,
                AccountStatus.DISABLED));

        User loaded = userRepository.findById(user.getId()).orElseThrow();

        assertThat(loaded.getEmail()).isEqualTo(user.getEmail());
        assertThat(loaded.getEmail()).isEqualTo(loaded.getEmail().toLowerCase(Locale.ROOT));
        assertThat(loaded.getPasswordHash()).isEqualTo("$2a$test-hash-value");
        assertThat(loaded.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(loaded.getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
    }

    @Test
    void rejectsDuplicateEmailAfterNormalization() {
        String email = "duplicate-" + UUID.randomUUID() + "@example.com";
        User saved = userRepository.saveAndFlush(
                new User(email, "hash-one", UserRole.CUSTOMER, AccountStatus.ENABLED));

        User loaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(loaded.getStatus()).isEqualTo(AccountStatus.ENABLED);

        assertThatThrownBy(() -> userRepository.saveAndFlush(new User(
                "  " + email.toUpperCase() + "  ",
                "hash-two",
                UserRole.CUSTOMER,
                AccountStatus.ENABLED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesRequiredAndLengthConstraints() {
        assertThatThrownBy(() -> userRepository.saveAndFlush(new User(
                null, "hash", UserRole.CUSTOMER, AccountStatus.ENABLED)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("email is required");

        assertThatThrownBy(() -> userRepository.saveAndFlush(new User(
                "long-hash-" + UUID.randomUUID() + "@example.com",
                "x".repeat(256),
                UserRole.CUSTOMER,
                AccountStatus.ENABLED)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("password hash must not exceed 255 characters");
    }

    @Test
    void updatesTheUpdateTimestampWhenAccountStateChanges() throws InterruptedException {
        User user = userRepository.saveAndFlush(new User(
                "timestamp-" + UUID.randomUUID() + "@example.com",
                "hash",
                UserRole.CUSTOMER,
                AccountStatus.ENABLED));
        LocalDateTime originalUpdatedAt = user.getUpdatedAt();

        Thread.sleep(10);
        user.changeStatus(AccountStatus.DISABLED);
        User updated = userRepository.saveAndFlush(user);

        assertThat(updated.getStatus()).isEqualTo(AccountStatus.DISABLED);
        assertThat(updated.getUpdatedAt()).isAfter(originalUpdatedAt);
        assertThat(updated.getCreatedAt()).isEqualTo(user.getCreatedAt());
    }
}
