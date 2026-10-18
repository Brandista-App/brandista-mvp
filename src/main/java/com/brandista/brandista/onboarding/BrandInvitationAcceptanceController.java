package com.brandista.brandista.onboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/brand-invitations")
@PreAuthorize("isAuthenticated()")
public class BrandInvitationAcceptanceController {
    public record Acceptance(@NotBlank @Size(max=256) String token) {
        @Override public String toString() { return "Acceptance[token=REDACTED]"; }
    }
    private final BrandInvitationService service;
    public BrandInvitationAcceptanceController(BrandInvitationService service) { this.service=service; }
    @PostMapping("/accept")
    public BrandInvitationService.Accepted accept(@Valid @RequestBody Acceptance request) { return service.accept(request.token()); }
}
