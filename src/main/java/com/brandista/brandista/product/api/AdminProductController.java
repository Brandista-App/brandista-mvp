package com.brandista.brandista.product.api;

import com.brandista.brandista.product.service.ProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/products")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminProductController {

    private final ProductService productService;

    public AdminProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody ProductCreateRequest request) {
        return productService.create(request);
    }

    @GetMapping
    public ProductPageResponse listAll(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return productService.listAll(page, size);
    }

    @GetMapping("/{id}")
    public ProductResponse getById(@PathVariable Long id) {
        return productService.getById(id);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductUpdateRequest request) {
        return productService.update(id, request);
    }

    @PatchMapping("/{id}/publish")
    public ProductResponse publish(@PathVariable Long id) {
        return productService.publish(id);
    }

    @PatchMapping("/{id}/unpublish")
    public ProductResponse unpublish(@PathVariable Long id) {
        return productService.unpublish(id);
    }

    @PatchMapping("/{id}/deactivate")
    public ProductResponse deactivate(@PathVariable Long id) {
        return productService.deactivate(id);
    }
}
