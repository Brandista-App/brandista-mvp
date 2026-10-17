package com.brandista.brandista.privilege;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only capability probe, not a provisioning or role-management API. */
@RestController
@RequestMapping("/api/v1/super-admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminIdentityController {
    private final SecurityAudit audit;
    public SuperAdminIdentityController(SecurityAudit audit) { this.audit = audit; }
    public record Identity(long userId, List<String> platformGrants) { }

    @GetMapping("/identity")
    public Identity identity(@AuthenticationPrincipal Jwt jwt) {
        audit.append(SecurityAudit.Operation.PRIVILEGED_ACCESS, Long.parseLong(jwt.getSubject()),
                SecurityAudit.Outcome.SUCCESS, SecurityAudit.Reason.COMPLETED);
        return new Identity(Long.parseLong(jwt.getSubject()), List.of("SUPER_ADMIN"));
    }
}
