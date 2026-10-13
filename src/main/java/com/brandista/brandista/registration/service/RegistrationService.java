package com.brandista.brandista.registration.service;

import com.brandista.brandista.registration.api.RegistrationRequest;
import com.brandista.brandista.registration.api.RegistrationResponse;
import com.brandista.brandista.user.AccountStatus;
import com.brandista.brandista.user.EmailNormalizer;
import com.brandista.brandista.user.User;
import com.brandista.brandista.user.UserRepository;
import com.brandista.brandista.user.UserRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegistrationResponse register(RegistrationRequest request) {
        String email = EmailNormalizer.normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateAccountIdentifierException();
        }

        User newCustomer = new User(
                email,
                passwordEncoder.encode(request.password()),
                UserRole.CUSTOMER,
                AccountStatus.ENABLED);

        try {
            User savedUser = userRepository.saveAndFlush(newCustomer);
            return new RegistrationResponse(savedUser.getId(), savedUser.getEmail(), savedUser.getCreatedAt());
        } catch (DataIntegrityViolationException exception) {
            // Handles two concurrent registrations that pass the pre-check together.
            throw new DuplicateAccountIdentifierException();
        }
    }
}
