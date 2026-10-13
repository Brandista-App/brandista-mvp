package com.brandista.brandista.category;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    Page<Category> findAllByStatusOrderByNameAsc(CategoryStatus status, Pageable pageable);

    Page<Category> findAllByOrderByNameAsc(Pageable pageable);
}
