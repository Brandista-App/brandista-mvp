package com.brandista.brandista.brandapplication.api;

import com.brandista.brandista.brandapplication.BrandApplicationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;

public record BrandApplicationReviewRequest(
        @NotNull BrandApplicationStatus decision,
        @Size(max = 2000) String reviewNote) {
    @AssertTrue(message = "must be NEEDS_INFORMATION, APPROVED, or REJECTED")
    public boolean isDecisionAllowed() {
        return decision == BrandApplicationStatus.NEEDS_INFORMATION
                || decision == BrandApplicationStatus.APPROVED
                || decision == BrandApplicationStatus.REJECTED;
    }
}
