package com.brandista.brandista.auth.api;

import com.brandista.brandista.user.EmailNormalizer;
import com.brandista.brandista.auth.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email address")
        @Size(max = 254, message = "must not exceed 254 characters")
        String email,

        @NotBlank(message = "must not be blank")
        @MaxUtf8Bytes(72)
        String password) {

    public LoginRequest {
        email = EmailNormalizer.normalize(email);
    }
}
