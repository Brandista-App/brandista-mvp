package com.brandista.brandista.address.api;

import java.time.LocalDateTime;

public record AddressResponse(
        Long id,
        String recipientName,
        String phoneNumber,
        String addressLine1,
        String addressLine2,
        String city,
        String province,
        String postalCode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String deliveryInstructions,
        boolean isDefault) {
}
