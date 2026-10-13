package com.brandista.brandista.brand;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BrandRepository extends JpaRepository<Brand, Long> {

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    Optional<Brand> findBySlug(String slug);

    List<Brand> findAllByStatusOrderByNameAsc(BrandStatus status);

    List<Brand> findAllByOrderByNameAsc();
}
