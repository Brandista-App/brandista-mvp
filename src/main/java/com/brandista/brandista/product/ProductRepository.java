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

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Product p join fetch p.brand join fetch p.category where p.id = :id")
    Optional<Product> findForCheckout(@org.springframework.data.repository.query.Param("id") Long id);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    Page<Product> findAllByOrderByNameAsc(Pageable pageable);

    @EntityGraph(attributePaths = {"brand", "category"})
    Page<Product> findAllByBrand_IdOrderByNameAscIdAsc(Long brandId, Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Product p where p.id=:id and p.brand.id=:brandId")
    Optional<Product> findForMerchant(@org.springframework.data.repository.query.Param("id") Long id,
                                     @org.springframework.data.repository.query.Param("brandId") Long brandId);

    @EntityGraph(attributePaths = {"brand", "category"})
    Optional<Product> findByIdAndBrand_Id(Long id, Long brandId);

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
