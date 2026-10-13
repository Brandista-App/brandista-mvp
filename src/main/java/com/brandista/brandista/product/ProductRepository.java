package com.brandista.brandista.product;

import com.brandista.brandista.brand.BrandStatus;
import com.brandista.brandista.category.CategoryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    Page<Product> findAllByOrderByNameAsc(Pageable pageable);

    Page<Product> findAllByPublicationStatusAndBrand_StatusAndCategory_StatusOrderByNameAsc(
            ProductPublicationStatus publicationStatus, BrandStatus brandStatus, CategoryStatus categoryStatus,
            Pageable pageable);

    Optional<Product> findBySlugAndPublicationStatusAndBrand_StatusAndCategory_Status(
            String slug, ProductPublicationStatus publicationStatus, BrandStatus brandStatus, CategoryStatus categoryStatus);
}
