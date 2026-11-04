package com.brandista.brandista.onboarding;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.brandista.brandista.registration.validation.PasswordPolicy;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/merchant-registration")
public class MerchantRegistrationController {
    public record Verify(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token){
        @Override public String toString(){return "Verify[REDACTED]";}
    }
    public record Register(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
        @NotBlank @Size(max=254) @Email String email,@NotBlank @PasswordPolicy String password,
        @AssertTrue boolean termsAccepted,@NotBlank @Size(max=64) String termsVersion){
        @Override public String toString(){return "Register[REDACTED]";}
    }
    private final BrandInvitationService service;
    public MerchantRegistrationController(BrandInvitationService service){this.service=service;}
    @PostMapping("/verify")
    public ResponseEntity<BrandInvitationService.Preview> verify(@Valid @RequestBody Verify input,HttpServletRequest request){
        service.publicBudget(request.getRemoteAddr(),false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(input.token()));
    }
    @PostMapping("/complete")
    public ResponseEntity<BrandInvitationService.Accepted> complete(@Valid @RequestBody Register input,HttpServletRequest request){
        service.publicBudget(request.getRemoteAddr(),true);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.register(input));
    }
}
