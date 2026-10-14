package com.brandista.brandista.brandapplication.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record BrandApplicationSubmissionRequest(
        @NotBlank @Size(max = 150) String brandName,
        @NotBlank @Size(max = 150) String contactName,
        @NotBlank @Email @Size(max = 254) String contactEmail,
        @Size(max = 2048) @Pattern(regexp = "https?://[^\\s]+", message = "must be an http or https URL") String website,
        @Size(max = 2000) String message) {
    public BrandApplicationSubmissionRequest {
        website = website == null || website.isBlank() ? null : website.trim();
        message = message == null || message.isBlank() ? null : message.trim();
    }
}
