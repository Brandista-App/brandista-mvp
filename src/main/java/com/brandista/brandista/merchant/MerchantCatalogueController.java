package com.brandista.brandista.merchant;

import com.brandista.brandista.product.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/merchant/brands")
@PreAuthorize("isAuthenticated()")
public class MerchantCatalogueController {
    private final MerchantCatalogueService merchant;
    public MerchantCatalogueController(MerchantCatalogueService merchant) { this.merchant=merchant; }
    public record StockRequest(@NotNull @Min(0) Integer stockQuantity) { }

    @GetMapping
    public List<MerchantCatalogueService.AssignedBrand> brands() { return merchant.assignedBrands(); }

    @GetMapping("/{brandId}/products")
    public ProductPageResponse products(@PathVariable @Positive long brandId,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="50") @Min(1) @Max(100) int size) { return merchant.list(brandId,page,size); }

    @GetMapping("/{brandId}/products/{id}")
    public ProductResponse product(@PathVariable @Positive long brandId, @PathVariable @Positive long id) { return merchant.get(brandId,id); }

    @PostMapping("/{brandId}/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@PathVariable @Positive long brandId, @Valid @RequestBody ProductCreateRequest request) { return merchant.create(brandId,request); }

    @PutMapping("/{brandId}/products/{id}")
    public ProductResponse update(@PathVariable @Positive long brandId, @PathVariable @Positive long id,
            @Valid @RequestBody ProductUpdateRequest request) { return merchant.update(brandId,id,request); }

    @PatchMapping("/{brandId}/products/{id}/publish")
    public ProductResponse publish(@PathVariable @Positive long brandId, @PathVariable @Positive long id) { return merchant.publish(brandId,id); }

    @PatchMapping("/{brandId}/products/{id}/unpublish")
    public ProductResponse unpublish(@PathVariable @Positive long brandId, @PathVariable @Positive long id) { return merchant.unpublish(brandId,id); }

    @PatchMapping("/{brandId}/products/{id}/deactivate")
    public ProductResponse deactivate(@PathVariable @Positive long brandId, @PathVariable @Positive long id) { return merchant.deactivate(brandId,id); }

    @GetMapping("/{brandId}/products/{id}/stock")
    public MerchantCatalogueService.Stock stock(@PathVariable @Positive long brandId, @PathVariable @Positive long id) { return merchant.stock(brandId,id); }

    @PutMapping("/{brandId}/products/{id}/stock")
    public MerchantCatalogueService.Stock stock(@PathVariable @Positive long brandId, @PathVariable @Positive long id,
            @Valid @RequestBody StockRequest request) { return merchant.updateStock(brandId,id,request.stockQuantity()); }
}
