package com.brandista.brandista.brand.api;

import com.brandista.brandista.brand.BrandSlugNormalizer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BrandCreateRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 150, message = "must not exceed 150 characters")
        String name,

        @NotBlank(message = "must not be blank")
        @Size(max = 160, message = "must not exceed 160 characters")
        @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*", message = "must contain lowercase letters or digits separated by single hyphens")
        String slug,

        @Size(max = 2000, message = "must not exceed 2000 characters")
        String description,

        @Size(max = 2048, message = "must not exceed 2048 characters")
        String logoReference) {

    public BrandCreateRequest {
        name = name == null ? null : name.trim();
        slug = BrandSlugNormalizer.normalize(slug);
        description = blankToNull(description);
        logoReference = blankToNull(logoReference);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
