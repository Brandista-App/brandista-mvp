package com.brandista.brandista.onboarding;

import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@Validated
@RequestMapping("/api/v1/super-admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class BrandInvitationController {
    private final BrandInvitationService service;
    public BrandInvitationController(BrandInvitationService service) { this.service=service; }
    @PostMapping("/brand-applications/{id}/invitations") @ResponseStatus(HttpStatus.CREATED)
    public BrandInvitationService.Status create(@PathVariable @Positive long id) { return service.create(id); }
    @GetMapping("/brand-applications/{id}/invitations")
    public BrandInvitationService.Page list(@PathVariable @Positive long id,@RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="50") @Min(1) @Max(100) int size) { return service.list(id,page,size); }
    @PostMapping("/brand-invitations/{id}/revoke")
    public BrandInvitationService.Status revoke(@PathVariable @Positive long id) { return service.revoke(id); }
    @PostMapping("/brand-invitations/{id}/reissue") @ResponseStatus(HttpStatus.CREATED)
    public BrandInvitationService.Status reissue(@PathVariable @Positive long id) { return service.reissue(id); }
    @GetMapping("/brand-applications/{id}/onboarding")
    public BrandInvitationService.State state(@PathVariable @Positive long id) { return service.state(id); }
    @PostMapping("/brand-applications/{id}/activate")
    public BrandInvitationService.State activate(@PathVariable @Positive long id) { return service.activate(id); }
}
