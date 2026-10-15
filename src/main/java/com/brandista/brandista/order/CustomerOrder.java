package com.brandista.brandista.order;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name="customer_orders",uniqueConstraints=@UniqueConstraint(name="uk_customer_order_key",columnNames={"customer_id","idempotency_key"}))
public class CustomerOrder {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="customer_id",nullable=false) Long customerId;
    @Column(name="idempotency_key",nullable=false,length=36) String idempotencyKey;
    @Column(name="request_hash",nullable=false,length=64) String requestHash;
    @Column(name="source_address_id",nullable=false) Long sourceAddressId;
    @Column(nullable=false,length=24) String status="PLACED";
    @Column(name="payment_method",nullable=false,length=16) String paymentMethod="COD";
    @Column(name="payment_status",nullable=false,length=16) String paymentStatus="UNPAID";
    @Column(nullable=false,length=3) String currency="PKR";
    @Column(nullable=false,precision=19,scale=2) BigDecimal subtotal;
    @Column(name="delivery_charge",nullable=false,precision=19,scale=2) BigDecimal deliveryCharge=BigDecimal.ZERO;
    @Column(nullable=false,precision=19,scale=2) BigDecimal tax=BigDecimal.ZERO;
    @Column(nullable=false,precision=19,scale=2) BigDecimal discount=BigDecimal.ZERO;
    @Column(nullable=false,precision=19,scale=2) BigDecimal total;
    @Embedded OrderDelivery delivery;
    @Column(name="created_at",nullable=false,updatable=false) LocalDateTime createdAt;
    @OneToMany(mappedBy="order",cascade=CascadeType.PERSIST)
    @OrderBy("id ASC") List<CustomerOrderItem> items=new ArrayList<>();
    protected CustomerOrder() { }
    CustomerOrder(Long customerId,String key,String requestHash,Long addressId,CheckoutModels.Preview preview) {
        this.customerId=customerId; this.idempotencyKey=key; this.requestHash=requestHash; this.sourceAddressId=addressId;
        subtotal=preview.subtotal(); total=preview.total(); delivery=new OrderDelivery(preview.deliveryAddress());
        createdAt=LocalDateTime.now(Clock.systemUTC());
        preview.items().forEach(line -> items.add(new CustomerOrderItem(this,line)));
    }
    CheckoutModels.OrderResponse response() {
        return new CheckoutModels.OrderResponse(id,status,paymentMethod,paymentStatus,currency,subtotal,deliveryCharge,tax,
                discount,total,delivery.response(),items.stream().map(CustomerOrderItem::response).toList(),createdAt);
    }
}
