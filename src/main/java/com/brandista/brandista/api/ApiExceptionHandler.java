package com.brandista.brandista.api;

import com.brandista.brandista.registration.service.DuplicateAccountIdentifierException;
import com.brandista.brandista.auth.service.LoginFailureException;
import com.brandista.brandista.brand.service.BrandNotFoundException;
import com.brandista.brandista.brand.service.DuplicateBrandSlugException;
import com.brandista.brandista.category.service.CategoryNotFoundException;
import com.brandista.brandista.category.service.DuplicateCategorySlugException;
import com.brandista.brandista.product.service.DeactivatedProductException;
import com.brandista.brandista.product.service.DuplicateProductSlugException;
import com.brandista.brandista.product.service.InactiveProductReferenceException;
import com.brandista.brandista.product.service.ProductNotFoundException;
import com.brandista.brandista.product.service.ProductReferenceNotFoundException;
import com.brandista.brandista.address.service.AddressNotFoundException;
import com.brandista.brandista.cart.service.CartNotFoundException;
import com.brandista.brandista.cart.service.CartProductUnavailableException;
import com.brandista.brandista.cart.service.CartQuantityException;
import com.brandista.brandista.brandapplication.service.BrandApplicationNotFoundException;
import com.brandista.brandista.brandapplication.service.DuplicateOpenBrandApplicationException;
import com.brandista.brandista.brandapplication.service.BrandApplicationReviewConflictException;
import com.brandista.brandista.brandapplication.service.BrandApplicationApprovalException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.transaction.CannotCreateTransactionException.class})
    public ResponseEntity<ApiErrorResponse> handleDatabaseUnavailable(
            RuntimeException exception, HttpServletRequest request) {
        // Resource exhaustion/unavailability is retryable, but never expose driver
        // messages, connection strings or credentials. Checkout must reuse its key.
        return response(HttpStatus.SERVICE_UNAVAILABLE,
                "Shopping service is temporarily unavailable. Retry shortly.", request, List.of());
    }

    @ExceptionHandler(com.brandista.brandista.onboarding.OnboardingFailure.class)
    public ResponseEntity<ApiErrorResponse> handleOnboarding(com.brandista.brandista.onboarding.OnboardingFailure exception,HttpServletRequest request) {
        return response(exception.status(),exception.getMessage(),request,List.of());
    }

    @ExceptionHandler(com.brandista.brandista.order.CheckoutFailure.class)
    public ResponseEntity<ApiErrorResponse> handleCheckout(com.brandista.brandista.order.CheckoutFailure exception,HttpServletRequest request) {
        return response(exception.status(),exception.getMessage(),request,List.of());
    }

    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleBusyCheckout(org.springframework.dao.PessimisticLockingFailureException exception,HttpServletRequest request) {
        return response(HttpStatus.CONFLICT,"Checkout is busy. Retry the same submission.",request,List.of());
    }

    @ExceptionHandler(CartNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCartNotFound(
            CartNotFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(CartProductUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleCartProductUnavailable(
            CartProductUnavailableException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(CartQuantityException.class)
    public ResponseEntity<ApiErrorResponse> handleCartQuantity(
            CartQuantityException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(AddressNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleAddressNotFound(
            AddressNotFoundException exception,
            HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(BrandNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleBrandNotFound(
            BrandNotFoundException exception,
            HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(BrandApplicationNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleBrandApplicationNotFound(
            BrandApplicationNotFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(DuplicateOpenBrandApplicationException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateBrandApplication(
            DuplicateOpenBrandApplicationException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(BrandApplicationReviewConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleBrandApplicationReviewConflict(
            BrandApplicationReviewConflictException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(BrandApplicationApprovalException.class)
    public ResponseEntity<ApiErrorResponse> handleBrandApplicationApproval(
            BrandApplicationApprovalException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(DuplicateBrandSlugException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateBrandSlug(
            DuplicateBrandSlugException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCategoryNotFound(
            CategoryNotFoundException exception,
            HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(DuplicateCategorySlugException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateCategorySlug(
            DuplicateCategorySlugException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleProductNotFound(
            ProductNotFoundException exception,
            HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ProductReferenceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleProductReferenceNotFound(
            ProductReferenceNotFoundException exception,
            HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler({DuplicateProductSlugException.class, InactiveProductReferenceException.class,
            DeactivatedProductException.class})
    public ResponseEntity<ApiErrorResponse> handleProductConflict(
            RuntimeException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(LoginFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleLoginFailure(
            LoginFailureException exception,
            HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(DuplicateAccountIdentifierException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateIdentifier(
            DuplicateAccountIdentifierException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ApiErrorResponse.FieldError> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ApiErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .distinct()
                .sorted((left, right) -> left.field().compareTo(right.field()))
                .toList();

        return response(HttpStatus.BAD_REQUEST, "Request validation failed.", request, fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "Request validation failed.", request, List.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "Request validation failed.", request, List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "Request body is malformed.", request, List.of());
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            List<ApiErrorResponse.FieldError> fieldErrors) {
        ApiErrorResponse body = new ApiErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                fieldErrors);
        return ResponseEntity.status(status).body(body);
    }
}
