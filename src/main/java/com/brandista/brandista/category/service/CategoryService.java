package com.brandista.brandista.category.service;

import com.brandista.brandista.category.Category;
import com.brandista.brandista.category.CategoryRepository;
import com.brandista.brandista.category.CategorySlugNormalizer;
import com.brandista.brandista.category.CategoryStatus;
import com.brandista.brandista.category.api.CategoryCreateRequest;
import com.brandista.brandista.category.api.CategoryPageResponse;
import com.brandista.brandista.category.api.CategoryResponse;
import com.brandista.brandista.category.api.CategoryUpdateRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private static final int MAX_PAGE_SIZE = 100;
    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Transactional
    public CategoryResponse create(CategoryCreateRequest request) {
        String slug = CategorySlugNormalizer.normalize(request.slug());
        if (categoryRepository.existsBySlug(slug)) throw new DuplicateCategorySlugException();
        try {
            return toResponse(categoryRepository.saveAndFlush(
                    new Category(request.name(), slug, request.description())));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateCategorySlugException();
        }
    }

    @Transactional(readOnly = true)
    public CategoryPageResponse listAll(int page, int size) {
        return page(categoryRepository.findAllByOrderByNameAsc(pageRequest(page, size)));
    }

    @Transactional(readOnly = true)
    public CategoryPageResponse listActive(int page, int size) {
        return page(categoryRepository.findAllByStatusOrderByNameAsc(
                CategoryStatus.ACTIVE, pageRequest(page, size)));
    }

    @Transactional(readOnly = true)
    public CategoryResponse getById(Long id) {
        return toResponse(findById(id));
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryUpdateRequest request) {
        Category category = findById(id);
        String slug = CategorySlugNormalizer.normalize(request.slug());
        if (categoryRepository.existsBySlugAndIdNot(slug, id)) throw new DuplicateCategorySlugException();
        category.updateDetails(request.name(), slug, request.description(), request.status());
        try {
            return toResponse(categoryRepository.saveAndFlush(category));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateCategorySlugException();
        }
    }

    @Transactional
    public CategoryResponse deactivate(Long id) {
        Category category = findById(id);
        category.deactivate();
        return toResponse(categoryRepository.saveAndFlush(category));
    }

    private PageRequest pageRequest(int page, int size) {
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("name").ascending().and(Sort.by("id").ascending()));
    }

    private CategoryPageResponse page(org.springframework.data.domain.Page<Category> source) {
        return new CategoryPageResponse(
                source.getContent().stream().map(CategoryService::toResponse).toList(),
                source.getNumber(), source.getSize(), source.getTotalElements(), source.getTotalPages());
    }

    private Category findById(Long id) {
        return categoryRepository.findById(id).orElseThrow(CategoryNotFoundException::new);
    }

    private static CategoryResponse toResponse(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug(), category.getDescription(),
                category.getStatus(), category.getCreatedAt(), category.getUpdatedAt());
    }
}
