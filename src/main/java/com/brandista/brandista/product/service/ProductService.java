package com.brandista.brandista.product.service;

import com.brandista.brandista.brand.Brand;
import com.brandista.brandista.brand.BrandRepository;
import com.brandista.brandista.brand.BrandStatus;
import com.brandista.brandista.category.Category;
import com.brandista.brandista.category.CategoryRepository;
import com.brandista.brandista.category.CategoryStatus;
import com.brandista.brandista.product.Product;
import com.brandista.brandista.product.ProductAvailability;
import com.brandista.brandista.product.ProductPublicationStatus;
import com.brandista.brandista.product.ProductRepository;
import com.brandista.brandista.product.ProductSlugNormalizer;
import com.brandista.brandista.product.api.ProductCreateRequest;
import com.brandista.brandista.product.api.ProductPageResponse;
import com.brandista.brandista.product.api.ProductResponse;
import com.brandista.brandista.product.api.ProductUpdateRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private static final int MAX_PAGE_SIZE = 100;
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;

    public ProductService(ProductRepository productRepository, BrandRepository brandRepository,
                          CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.brandRepository = brandRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional
    public ProductResponse create(ProductCreateRequest request) {
        String slug = ProductSlugNormalizer.normalize(request.slug());
        if (productRepository.existsBySlug(slug)) throw new DuplicateProductSlugException();
        Brand brand = requireActiveBrand(request.brandId());
        Category category = requireActiveCategory(request.categoryId());
        try {
            return toResponse(productRepository.saveAndFlush(new Product(request.name(), slug, request.description(),
                    brand, category, request.price(), request.availability())));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateProductSlugException();
        }
    }

    @Transactional(readOnly = true)
    public ProductPageResponse listAll(int page, int size) {
        return page(productRepository.findAllByOrderByNameAsc(pageRequest(page, size)));
    }

    @Transactional(readOnly = true)
    public ProductResponse getById(Long id) {
        return toResponse(findById(id));
    }

    @Transactional
    public ProductResponse update(Long id, ProductUpdateRequest request) {
        Product product = findById(id);
        ensureEditable(product);
        String slug = ProductSlugNormalizer.normalize(request.slug());
        if (productRepository.existsBySlugAndIdNot(slug, id)) throw new DuplicateProductSlugException();
        Brand brand = requireActiveBrand(request.brandId());
        Category category = requireActiveCategory(request.categoryId());
        try {
            product.updateDetails(request.name(), slug, request.description(), brand, category,
                    request.price(), request.availability());
            return toResponse(productRepository.saveAndFlush(product));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateProductSlugException();
        } catch (IllegalStateException exception) {
            throw new DeactivatedProductException();
        }
    }

    @Transactional
    public ProductResponse publish(Long id) {
        Product product = findById(id);
        ensureEditable(product);
        ensureActiveReferences(product.getBrand(), product.getCategory());
        product.publish();
        return toResponse(productRepository.saveAndFlush(product));
    }

    @Transactional
    public ProductResponse unpublish(Long id) {
        Product product = findById(id);
        ensureEditable(product);
        product.unpublish();
        return toResponse(productRepository.saveAndFlush(product));
    }

    @Transactional
    public ProductResponse deactivate(Long id) {
        Product product = findById(id);
        product.deactivate();
        return toResponse(productRepository.saveAndFlush(product));
    }

    @Transactional(readOnly = true)
    public ProductPageResponse listPublic(int page, int size) {
        return page(productRepository.findAllByPublicationStatusAndBrand_StatusAndCategory_StatusOrderByNameAsc(
                ProductPublicationStatus.PUBLISHED, BrandStatus.ACTIVE, CategoryStatus.ACTIVE, pageRequest(page, size)));
    }

    @Transactional(readOnly = true)
    public ProductResponse getPublicBySlug(String slug) {
        String normalizedSlug = ProductSlugNormalizer.normalize(slug);
        Product product = productRepository.findBySlugAndPublicationStatusAndBrand_StatusAndCategory_Status(
                        normalizedSlug, ProductPublicationStatus.PUBLISHED, BrandStatus.ACTIVE, CategoryStatus.ACTIVE)
                .orElseThrow(ProductNotFoundException::new);
        return toResponse(product);
    }

    private Brand requireActiveBrand(Long id) {
        Brand brand = brandRepository.findById(id).orElseThrow(ProductReferenceNotFoundException::new);
        if (brand.getStatus() != BrandStatus.ACTIVE) throw new InactiveProductReferenceException();
        return brand;
    }

    private Category requireActiveCategory(Long id) {
        Category category = categoryRepository.findById(id).orElseThrow(ProductReferenceNotFoundException::new);
        if (category.getStatus() != CategoryStatus.ACTIVE) throw new InactiveProductReferenceException();
        return category;
    }

    private void ensureActiveReferences(Brand brand, Category category) {
        if (brand.getStatus() != BrandStatus.ACTIVE || category.getStatus() != CategoryStatus.ACTIVE) {
            throw new InactiveProductReferenceException();
        }
    }

    private void ensureEditable(Product product) {
        if (product.getPublicationStatus() == ProductPublicationStatus.DEACTIVATED) {
            throw new DeactivatedProductException();
        }
    }

    private PageRequest pageRequest(int page, int size) {
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by("name").ascending().and(Sort.by("id").ascending()));
    }

    private ProductPageResponse page(Page<Product> source) {
        return new ProductPageResponse(source.getContent().stream().map(ProductService::toResponse).toList(),
                source.getNumber(), source.getSize(), source.getTotalElements(), source.getTotalPages());
    }

    private Product findById(Long id) {
        return productRepository.findById(id).orElseThrow(ProductNotFoundException::new);
    }

    private static ProductResponse toResponse(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getSlug(), product.getDescription(),
                product.getBrand().getId(), product.getBrand().getName(), product.getCategory().getId(),
                product.getCategory().getName(), product.getPrice(), "PKR", product.getAvailability(),
                product.getPublicationStatus(), product.getCreatedAt(), product.getUpdatedAt());
    }
}
