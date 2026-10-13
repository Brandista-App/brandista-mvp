package com.brandista.brandista.registration.api;

import java.time.LocalDateTime;

public record RegistrationResponse(Long id, String email, LocalDateTime createdAt) {
}
