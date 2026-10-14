package com.brandista.brandista.brandapplication.api;

import com.brandista.brandista.brandapplication.service.BrandApplicationService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/brand-applications")
@PreAuthorize("hasRole('ADMIN')")
public class AdminBrandApplicationController {
    private final BrandApplicationService service;
    public AdminBrandApplicationController(BrandApplicationService service) { this.service = service; }

    @GetMapping
    public List<BrandApplicationResponse> list() { return service.list(); }

    @PatchMapping("/{id}/review")
    public BrandApplicationResponse review(@PathVariable Long id, @Valid @RequestBody BrandApplicationReviewRequest request) {
        return service.review(id, request);
    }
}
