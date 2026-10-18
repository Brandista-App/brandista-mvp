package com.brandista.brandista.brandapplication.service;

import com.brandista.brandista.brand.Brand;
import com.brandista.brandista.brand.BrandRepository;
import com.brandista.brandista.brand.BrandSlugNormalizer;
import com.brandista.brandista.brandapplication.BrandApplication;
import com.brandista.brandista.brandapplication.BrandApplicationRepository;
import com.brandista.brandista.brandapplication.BrandApplicationStatus;
import com.brandista.brandista.brandapplication.api.BrandApplicationResponse;
import com.brandista.brandista.brandapplication.api.BrandApplicationReviewRequest;
import com.brandista.brandista.brandapplication.api.BrandApplicationSubmissionRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BrandApplicationService {
    private static final List<BrandApplicationStatus> OPEN_STATUSES = List.of(
            BrandApplicationStatus.SUBMITTED, BrandApplicationStatus.NEEDS_INFORMATION);
    private final BrandApplicationRepository applicationRepository;
    private final BrandRepository brandRepository;
    private final com.brandista.brandista.onboarding.OnboardingPolicy onboarding;
    private final com.brandista.brandista.privilege.SecurityAudit audit;

    public BrandApplicationService(BrandApplicationRepository applicationRepository, BrandRepository brandRepository,
            com.brandista.brandista.onboarding.OnboardingPolicy onboarding, com.brandista.brandista.privilege.SecurityAudit audit) {
        this.applicationRepository = applicationRepository;
        this.brandRepository = brandRepository;
        this.onboarding=onboarding; this.audit=audit;
    }

    @Transactional
    public BrandApplicationResponse submit(BrandApplicationSubmissionRequest request) {
        String email = request.contactEmail().trim().toLowerCase(java.util.Locale.ROOT);
        if (applicationRepository.existsByContactEmailAndStatusIn(email, OPEN_STATUSES)) {
            throw new DuplicateOpenBrandApplicationException();
        }
        return toResponse(applicationRepository.saveAndFlush(new BrandApplication(
                request.brandName(), request.contactName(), email, request.website(), request.message())));
    }

    @Transactional(readOnly = true)
    public List<BrandApplicationResponse> list() {
        return applicationRepository.findAllByOrderByCreatedAtDesc().stream().map(BrandApplicationService::toResponse).toList();
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public BrandApplicationResponse review(Long id, BrandApplicationReviewRequest request) {
        onboarding.reviewer(request.decision()==BrandApplicationStatus.APPROVED,id);
        BrandApplication application = applicationRepository.findForReview(id)
                .orElseThrow(() -> {
                    rejectReview(id,com.brandista.brandista.privilege.SecurityAudit.Reason.NOT_FOUND);
                    return new BrandApplicationNotFoundException();
                });
        if (!OPEN_STATUSES.contains(application.getStatus())) {
            audit.rejectedOnboarding(com.brandista.brandista.privilege.SecurityAudit.Operation.APPLICATION_REVIEWED,null,null,null,id,
                    com.brandista.brandista.privilege.SecurityAudit.Reason.INVALID_INPUT);
            throw new BrandApplicationReviewConflictException();
        }

        Brand approvedBrand = null;
        if (request.decision() == BrandApplicationStatus.APPROVED) {
            String slug = application.getBrandName().trim().toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            slug = BrandSlugNormalizer.normalize(slug);
            if (slug == null || slug.isBlank() || brandRepository.existsBySlug(slug)) {
                rejectReview(id,com.brandista.brandista.privilege.SecurityAudit.Reason.INVALID_INPUT);
                throw new BrandApplicationApprovalException(
                        "Brand name cannot be converted to a unique storefront slug; resolve the duplicate before approval.");
            }
            approvedBrand = new Brand(application.getBrandName(), slug, null, null);
            approvedBrand.deactivate();
            try { approvedBrand = brandRepository.saveAndFlush(approvedBrand); }
            catch(DataIntegrityViolationException conflict) {
                rejectReview(id,com.brandista.brandista.privilege.SecurityAudit.Reason.INVALID_INPUT);
                throw new BrandApplicationApprovalException("Brand approval conflicts with an existing storefront.");
            }
        }
        try {
            application.review(request.decision(), request.reviewNote(), approvedBrand);
        } catch (IllegalStateException exception) {
            throw new BrandApplicationReviewConflictException();
        }
        BrandApplication saved=applicationRepository.saveAndFlush(application);
        audit.onboarding(com.brandista.brandista.privilege.SecurityAudit.Operation.APPLICATION_REVIEWED,null,
                approvedBrand==null?null:approvedBrand.getId(),null,id,com.brandista.brandista.privilege.SecurityAudit.Outcome.SUCCESS,
                com.brandista.brandista.privilege.SecurityAudit.Reason.COMPLETED);
        return toResponse(saved);
    }

    private void rejectReview(Long id,com.brandista.brandista.privilege.SecurityAudit.Reason reason) {
        audit.rejectedOnboarding(com.brandista.brandista.privilege.SecurityAudit.Operation.APPLICATION_REVIEWED,
                null,null,null,id,reason);
    }

    private static BrandApplicationResponse toResponse(BrandApplication application) {
        Brand brand = application.getBrand();
        return new BrandApplicationResponse(application.getId(), application.getBrandName(), application.getContactName(),
                application.getContactEmail(), application.getWebsite(), application.getMessage(), application.getStatus(),
                application.getReviewNote(), application.getReviewedAt(), brand == null ? null : brand.getId(),
                brand == null ? null : brand.getStatus().name(), application.getCreatedAt(), application.getUpdatedAt());
    }
}
