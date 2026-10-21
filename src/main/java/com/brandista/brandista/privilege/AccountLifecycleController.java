package com.brandista.brandista.privilege;

import com.brandista.brandista.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/super-admin/users/{userId}")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AccountLifecycleController {
    public record TargetIdentity(@NotBlank @Email @Size(max=254) String expectedEmail) {
        public TargetIdentity { expectedEmail=com.brandista.brandista.user.EmailNormalizer.normalize(expectedEmail); }
    }
    private final AccountLifecycleService service;
    public AccountLifecycleController(AccountLifecycleService service) { this.service=service; }

    @PostMapping("/brand-memberships/{brandId}/revoke")
    public ResponseEntity<?> revoke(@PathVariable @Positive long userId, @PathVariable @Positive long brandId,
                                    @Valid @RequestBody TargetIdentity target, HttpServletRequest request) {
        return response(service.revoke(userId,target.expectedEmail(),brandId),request);
    }

    @PostMapping("/disable")
    public ResponseEntity<?> disable(@PathVariable @Positive long userId,
                                     @Valid @RequestBody TargetIdentity target, HttpServletRequest request) {
        return response(service.disable(userId,target.expectedEmail()),request);
    }

    @PostMapping("/restore")
    public ResponseEntity<?> restore(@PathVariable @Positive long userId,
                                     @Valid @RequestBody TargetIdentity target, HttpServletRequest request) {
        return response(service.restore(userId,target.expectedEmail()),request);
    }

    // Translate after the service transaction commits, preserving rejected-attempt audit rows.
    private ResponseEntity<?> response(AccountLifecycleService.Result result, HttpServletRequest request) {
        if (result.changed()) return ResponseEntity.noContent().build();
        HttpStatus status=result.reason()==SecurityAudit.Reason.NOT_FOUND?HttpStatus.NOT_FOUND:HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(new ApiErrorResponse(status.value(),status.getReasonPhrase(),
                "Lifecycle operation rejected. Verify the approved target and current state.",request.getRequestURI(),List.of()));
    }
}
