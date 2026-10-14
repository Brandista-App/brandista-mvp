package com.brandista.brandista.brandapplication.api;

import com.brandista.brandista.brandapplication.BrandApplicationStatus;

import java.time.LocalDateTime;

public record BrandApplicationSubmittedResponse(Long id, BrandApplicationStatus status, LocalDateTime createdAt) { }
