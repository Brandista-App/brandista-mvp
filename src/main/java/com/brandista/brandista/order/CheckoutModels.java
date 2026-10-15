package com.brandista.brandista.order;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class CheckoutModels {
    private CheckoutModels() { }
    public record PreviewRequest(@NotNull @Positive Long addressId) { }
    public record PlaceRequest(@NotNull @Positive Long addressId,
                               @NotBlank @Pattern(regexp="[a-f0-9]{64}") String quoteToken) { }
    public record Delivery(String recipientName, String phoneNumber, String addressLine1,
                           String addressLine2, String city, String province, String postalCode,
                           String deliveryInstructions) { }
    public record Line(Long productId, String productSlug, String productName, Long brandId,
                       String brandName, int quantity, BigDecimal unitPrice, BigDecimal lineSubtotal) { }
    public record Preview(String quoteToken, Delivery deliveryAddress, List<Line> items,
                          BigDecimal subtotal, BigDecimal deliveryCharge, BigDecimal tax,
                          BigDecimal discount, BigDecimal total, String currency, String paymentMethod) { }
    public record OrderResponse(Long id, String status, String paymentMethod, String paymentStatus,
                                String currency, BigDecimal subtotal, BigDecimal deliveryCharge,
                                BigDecimal tax, BigDecimal discount, BigDecimal total,
                                Delivery deliveryAddress, List<Line> items, LocalDateTime createdAt) { }
    public record OrderPage(List<OrderResponse> content, int page, int size, long totalElements, int totalPages) { }
    public record Placement(OrderResponse order, boolean replayed) { }
}
