package com.brandista.brandista.brand.api;

import com.brandista.brandista.brand.service.BrandService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/brands")
@PreAuthorize("hasRole('ADMIN')")
public class AdminBrandController {

    private final BrandService brandService;

    public AdminBrandController(BrandService brandService) {
        this.brandService = brandService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BrandResponse create(@Valid @RequestBody BrandCreateRequest request) {
        return brandService.create(request);
    }

    @GetMapping
    public List<BrandResponse> listAll() {
        return brandService.listAll();
    }

    @GetMapping("/{id}")
    public BrandResponse getById(@PathVariable Long id) {
        return brandService.getById(id);
    }

    @PutMapping("/{id}")
    public BrandResponse update(@PathVariable Long id, @Valid @RequestBody BrandUpdateRequest request) {
        return brandService.update(id, request);
    }

    @PatchMapping("/{id}/deactivate")
    public BrandResponse deactivate(@PathVariable Long id) {
        return brandService.deactivate(id);
    }
}
