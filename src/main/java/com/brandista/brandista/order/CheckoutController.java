package com.brandista.brandista.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;
import static com.brandista.brandista.order.CheckoutModels.*;

@RestController
@RequestMapping("/api/v1/customers/me")
@PreAuthorize("hasRole('CUSTOMER')")
@Validated
public class CheckoutController {
    private final CheckoutService service;
    public CheckoutController(CheckoutService service) { this.service=service; }
    @GetMapping("/checkout/config") public Map<String,Object> config() { return Map.of("codEnabled",service.isCodEnabled(),"currency","PKR"); }
    @PostMapping("/checkout/preview") public Preview preview(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody PreviewRequest request) {
        return service.preview(jwt.getSubject(),request.addressId());
    }
    @PostMapping("/orders") public ResponseEntity<OrderResponse> place(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody PlaceRequest request) {
        Placement result=service.place(jwt.getSubject(),key.toString(),request);
        return ResponseEntity.status(result.replayed()?200:201).body(result.order());
    }
    @GetMapping("/orders") public OrderPage list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return service.list(jwt.getSubject(),page,size);
    }
    @GetMapping("/orders/{id}") public OrderResponse get(@AuthenticationPrincipal Jwt jwt,@PathVariable @Positive Long id) { return service.get(jwt.getSubject(),id); }
}
