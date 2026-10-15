package com.brandista.brandista.order;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name="customer_order_items")
public class CustomerOrderItem {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="order_id",nullable=false) private CustomerOrder order;
    @Column(name="product_id",nullable=false) private Long productId;
    @Column(name="product_slug",nullable=false,length=160) private String productSlug;
    @Column(name="product_name",nullable=false,length=150) private String productName;
    @Column(name="brand_id",nullable=false) private Long brandId;
    @Column(name="brand_name",nullable=false,length=150) private String brandName;
    @Column(nullable=false) private int quantity;
    @Column(name="unit_price",nullable=false,precision=12,scale=2) private BigDecimal unitPrice;
    @Column(name="line_subtotal",nullable=false,precision=19,scale=2) private BigDecimal lineSubtotal;
    protected CustomerOrderItem() { }
    CustomerOrderItem(CustomerOrder order, CheckoutModels.Line line) {
        this.order=order; productId=line.productId(); productSlug=line.productSlug(); productName=line.productName();
        brandId=line.brandId(); brandName=line.brandName(); quantity=line.quantity(); unitPrice=line.unitPrice(); lineSubtotal=line.lineSubtotal();
    }
    CheckoutModels.Line response() { return new CheckoutModels.Line(productId,productSlug,productName,brandId,brandName,quantity,unitPrice,lineSubtotal); }
}
