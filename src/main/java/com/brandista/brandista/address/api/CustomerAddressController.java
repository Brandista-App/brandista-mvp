package com.brandista.brandista.address.api;

import com.brandista.brandista.address.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/customers/me/addresses")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerAddressController {

    private final AddressService addressService;

    public CustomerAddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressResponse create(@AuthenticationPrincipal Jwt principal,
                                  @Valid @RequestBody AddressRequest request) {
        return addressService.create(principal.getSubject(), request);
    }

    @GetMapping
    public List<AddressResponse> list(@AuthenticationPrincipal Jwt principal) {
        return addressService.list(principal.getSubject());
    }

    @GetMapping("/{addressId}")
    public AddressResponse get(@AuthenticationPrincipal Jwt principal, @PathVariable Long addressId) {
        return addressService.get(principal.getSubject(), addressId);
    }

    @PutMapping("/{addressId}")
    public AddressResponse update(@AuthenticationPrincipal Jwt principal, @PathVariable Long addressId,
                                  @Valid @RequestBody AddressRequest request) {
        return addressService.update(principal.getSubject(), addressId, request);
    }

    @PatchMapping("/{addressId}")
    public AddressResponse patch(@AuthenticationPrincipal Jwt principal, @PathVariable Long addressId,
                                 @Valid @RequestBody AddressPatchRequest request) {
        return addressService.patch(principal.getSubject(), addressId, request);
    }

    @DeleteMapping("/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt principal, @PathVariable Long addressId) {
        addressService.delete(principal.getSubject(), addressId);
    }
}
