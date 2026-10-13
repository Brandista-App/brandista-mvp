package com.brandista.brandista.address.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddressPatchRequest(
        @Size(max = 120) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String recipientName,
        @Size(max = 25)
        @Pattern(regexp = "(?=(?:\\D*\\d){7,15}\\D*$)\\+?[0-9][0-9 ()-]{5,23}",
                message = "must contain 7 to 15 digits and use only common phone formatting characters")
        String phoneNumber,
        @Size(max = 255) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String addressLine1,
        @Size(max = 255) String addressLine2,
        @Size(max = 100) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String city,
        @Size(max = 100) String province,
        @Size(max = 20) String postalCode) {

    @AssertTrue(message = "at least one address field must be provided")
    public boolean isAtLeastOneFieldProvided() {
        return recipientName != null || phoneNumber != null || addressLine1 != null || addressLine2 != null
                || city != null || province != null || postalCode != null;
    }
}
