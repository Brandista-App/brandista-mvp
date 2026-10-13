package com.brandista.brandista.address.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddressRequest(
        @NotBlank @Size(max = 120) String recipientName,
        @NotBlank @Size(max = 25)
        @Pattern(regexp = "(?=(?:\\D*\\d){7,15}\\D*$)\\+?[0-9][0-9 ()-]{5,23}",
                message = "must contain 7 to 15 digits and use only common phone formatting characters")
        String phoneNumber,
        @NotBlank @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @NotBlank @Size(max = 100) String city,
        @Size(max = 100) String province,
        @Size(max = 20) String postalCode) {
}
