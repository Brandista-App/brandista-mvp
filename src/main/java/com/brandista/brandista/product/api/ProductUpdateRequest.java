package com.brandista.brandista.product.api;

import com.brandista.brandista.product.ProductAvailability;
import com.brandista.brandista.product.ProductSlugNormalizer;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductUpdateRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 150, message = "must not exceed 150 characters")
        String name,

        @NotBlank(message = "must not be blank")
        @Size(max = 160, message = "must not exceed 160 characters")
        @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*", message = "must contain lowercase letters or digits separated by single hyphens")
        String slug,

        @Size(max = 4000, message = "must not exceed 4000 characters")
        String description,

        @NotNull(message = "must be provided") @Positive(message = "must be a positive ID")
        Long brandId,

        @NotNull(message = "must be provided") @Positive(message = "must be a positive ID")
        Long categoryId,

        @NotNull(message = "must be provided")
        @DecimalMin(value = "0.00", inclusive = true, message = "must be zero or greater")
        @Digits(integer = 10, fraction = 2, message = "must have at most 10 integer and 2 fractional digits")
        BigDecimal price,

        @NotNull(message = "must be provided")
        ProductAvailability availability) {

    public ProductUpdateRequest {
        name = name == null ? null : name.trim();
        slug = ProductSlugNormalizer.normalize(slug);
        description = blankToNull(description);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
