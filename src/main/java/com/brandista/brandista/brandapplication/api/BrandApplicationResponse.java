package com.brandista.brandista.brandapplication.api;

import com.brandista.brandista.brandapplication.BrandApplicationStatus;

import java.time.LocalDateTime;

public record BrandApplicationResponse(
        Long id, String brandName, String contactName, String contactEmail, String website, String message,
        BrandApplicationStatus status, String reviewNote, LocalDateTime reviewedAt,
        Long brandId, String approvedBrandStatus, LocalDateTime createdAt, LocalDateTime updatedAt) { }
