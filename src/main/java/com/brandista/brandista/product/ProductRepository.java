package com.brandista.brandista.product;

import com.brandista.brandista.brand.BrandStatus;
import com.brandista.brandista.category.CategoryStatus;
import com.brandista.brandista.brand.BrandStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    Page<Product> findAllByOrderByNameAsc(Pageable pageable);

    Page<Product> findAllByPublicationStatusAndBrand_StatusAndCategory_StatusOrderByNameAsc(
            ProductPublicationStatus publicationStatus, BrandStatus brandStatus, CategoryStatus categoryStatus,
            Pageable pageable);

    Optional<Product> findBySlugAndPublicationStatusAndBrand_StatusAndCategory_Status(
            String slug, ProductPublicationStatus publicationStatus, BrandStatus brandStatus, CategoryStatus categoryStatus);

    @EntityGraph(attributePaths = {"brand", "category"})
    Optional<Product> findByIdAndPublicationStatusAndAvailabilityAndBrand_StatusAndCategory_Status(
            Long id, ProductPublicationStatus publicationStatus,
            ProductAvailability availability, BrandStatus brandStatus, CategoryStatus categoryStatus);

    @Override
    @EntityGraph(attributePaths = {"brand", "category"})
    Page<Product> findAll(Specification<Product> specification, Pageable pageable);
}
