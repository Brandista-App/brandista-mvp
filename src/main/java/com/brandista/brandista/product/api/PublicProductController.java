package com.brandista.brandista.product.api;

import com.brandista.brandista.product.service.ProductService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/products")
public class PublicProductController {

    private final ProductService productService;

    public PublicProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public PublicProductPageResponse listPublished(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @Positive Long brandId,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(defaultValue = "default") @Pattern(regexp = "default|price_asc|price_desc") String sort) {
        return productService.listPublic(page, size, keyword, brandId, categoryId, ProductSort.fromQuery(sort));
    }

    @GetMapping("/{slug}")
    public PublicProductResponse getPublishedBySlug(@PathVariable String slug) {
        return productService.getPublicBySlug(slug);
    }
}
