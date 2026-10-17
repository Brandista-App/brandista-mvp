package com.brandista.brandista.product.api;

import com.brandista.brandista.product.ProductRepository;
import com.brandista.brandista.product.service.ProductNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/products")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminInventoryController {
    private final ProductRepository products;
    public AdminInventoryController(ProductRepository products) { this.products = products; }
    public record StockRequest(@jakarta.validation.constraints.NotNull @Min(0) Integer stockQuantity) { }
    public record StockResponse(Long productId, int stockQuantity) { }
    @PutMapping("/{id}/stock")
    @Transactional
    public StockResponse update(@PathVariable Long id, @Valid @RequestBody StockRequest request) {
        var product = products.findForCheckout(id).orElseThrow(ProductNotFoundException::new);
        product.setStockQuantity(request.stockQuantity());
        return new StockResponse(id, product.getStockQuantity());
    }
}
