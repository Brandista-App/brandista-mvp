package com.brandista.brandista.category.api;

import com.brandista.brandista.category.CategorySlugNormalizer;
import com.brandista.brandista.category.CategoryStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CategoryUpdateRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 150, message = "must not exceed 150 characters")
        String name,

        @NotBlank(message = "must not be blank")
        @Size(max = 160, message = "must not exceed 160 characters")
        @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*", message = "must contain lowercase letters or digits separated by single hyphens")
        String slug,

        @Size(max = 2000, message = "must not exceed 2000 characters")
        String description,

        @NotNull(message = "must be provided")
        CategoryStatus status) {

    public CategoryUpdateRequest {
        name = name == null ? null : name.trim();
        slug = CategorySlugNormalizer.normalize(slug);
        description = blankToNull(description);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
