package com.brandista.brandista.registration.api;

import com.brandista.brandista.registration.validation.PasswordPolicy;
import com.brandista.brandista.user.EmailNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegistrationRequest(
        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email address")
        @Size(max = 254, message = "must not exceed 254 characters")
        String email,

        @NotBlank(message = "must not be blank")
        @PasswordPolicy
        String password) {

    public RegistrationRequest {
        email = EmailNormalizer.normalize(email);
    }
}
