package com.brandista.brandista.merchant;

import com.brandista.brandista.product.*;
import com.brandista.brandista.product.api.*;
import com.brandista.brandista.product.service.DeactivatedProductException;
import com.brandista.brandista.product.service.ProductNotFoundException;
import com.brandista.brandista.product.service.ProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.util.List;

@Service
@Validated
@PreAuthorize("isAuthenticated()")
@Transactional(isolation=Isolation.READ_COMMITTED)
public class MerchantCatalogueService {
    public record AssignedBrand(long id, String name, String slug, String logoReference) { }
    public record Stock(long productId, int stockQuantity) { }
    private final BrandAccess access;
    private final ProductRepository products;
    private final ProductService catalogue;
    private final JdbcTemplate jdbc;

    public MerchantCatalogueService(BrandAccess access, ProductRepository products, ProductService catalogue, JdbcTemplate jdbc) {
        this.access=access; this.products=products; this.catalogue=catalogue; this.jdbc=jdbc;
    }

    public List<AssignedBrand> assignedBrands() {
        long user = access.currentUser();
        var brands = jdbc.query("SELECT b.id,b.name,b.slug,b.logo_reference FROM brands b JOIN brand_admin_memberships m ON m.brand_id=b.id "
                + "WHERE m.user_id=? AND m.revoked_at IS NULL AND b.status='ACTIVE' ORDER BY b.name,b.id",
                (row, index) -> new AssignedBrand(row.getLong("id"), row.getString("name"), row.getString("slug"), row.getString("logo_reference")), user);
        if (brands.isEmpty()) throw BrandAccess.denied();
        return brands;
    }

    public ProductPageResponse list(@Positive long brandId, @Min(0) int page, @Min(1) int size) {
        access.requireBrand(brandId);
        return catalogue.listForBrand(brandId, page, size);
    }

    public ProductResponse get(@Positive long brandId, @Positive long productId) {
        ownedProduct(brandId, productId, false);
        return catalogue.getById(productId);
    }

    public ProductResponse create(@Positive long brandId, @NotNull @Valid ProductCreateRequest request) {
        access.requireBrand(brandId);
        sameBrand(brandId, request.brandId());
        return catalogue.create(request);
    }

    public ProductResponse update(@Positive long brandId, @Positive long productId, @NotNull @Valid ProductUpdateRequest request) {
        access.requireBrand(brandId);
        sameBrand(brandId, request.brandId());
        ownedProduct(brandId, productId);
        return catalogue.update(productId, request);
    }

    public ProductResponse publish(@Positive long brandId, @Positive long productId) {
        ownedProduct(brandId, productId);
        return catalogue.publish(productId);
    }

    public ProductResponse unpublish(@Positive long brandId, @Positive long productId) {
        ownedProduct(brandId, productId);
        return catalogue.unpublish(productId);
    }

    public ProductResponse deactivate(@Positive long brandId, @Positive long productId) {
        ownedProduct(brandId, productId);
        return catalogue.deactivate(productId);
    }

    public Stock stock(@Positive long brandId, @Positive long productId) {
        Product product = ownedProduct(brandId, productId, false);
        return new Stock(productId, product.getStockQuantity());
    }

    public Stock updateStock(@Positive long brandId, @Positive long productId, @Min(0) int quantity) {
        Product product = ownedProduct(brandId, productId);
        if (product.getPublicationStatus() == ProductPublicationStatus.DEACTIVATED) throw new DeactivatedProductException();
        product.setStockQuantity(quantity); // Same domain invariant and row lock used by checkout/global inventory.
        products.saveAndFlush(product);
        return new Stock(productId, product.getStockQuantity());
    }

    private Product ownedProduct(long brandId, long productId) {
        return ownedProduct(brandId, productId, true);
    }

    private Product ownedProduct(long brandId, long productId, boolean mutation) {
        access.requireBrand(brandId);
        // Composite lookup before any shared, unscoped business operation. Lock through commit
        // so concurrent global reassignment cannot move a product between this check and write.
        return (mutation ? products.findForMerchant(productId, brandId) : products.findByIdAndBrand_Id(productId, brandId))
                .orElseThrow(ProductNotFoundException::new);
    }

    private void sameBrand(long brandId, Long bodyBrandId) {
        if (bodyBrandId == null || bodyBrandId != brandId) throw BrandAccess.denied();
    }
}
