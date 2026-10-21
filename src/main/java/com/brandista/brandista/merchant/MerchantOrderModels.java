package com.brandista.brandista.merchant;

import com.brandista.brandista.order.CheckoutModels;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class MerchantOrderModels {
    private MerchantOrderModels() { }
    public enum Status {
        PLACED, ACCEPTED, PACKED, SHIPPED, DELIVERED;
        public List<Status> next() { return this==DELIVERED?List.of():List.of(values()[ordinal()+1]); }
    }
    public record Transition(@NotNull Status status,@NotNull @PositiveOrZero Long expectedVersion) { }
    public record Summary(long orderId,long brandId,LocalDateTime createdAt,Status fulfillmentStatus,long version,
                          String currency,BigDecimal itemsSubtotal,int lineCount,List<Status> availableTransitions) { }
    public record Page(List<Summary> content,int page,int size,long totalElements,int totalPages) { }
    public record Event(Status fromStatus,Status toStatus,long version,LocalDateTime occurredAt) { }
    public record Detail(Summary order,CheckoutModels.Delivery deliveryAddress,List<CheckoutModels.Line> items,
                         List<Event> timeline) { }
}
