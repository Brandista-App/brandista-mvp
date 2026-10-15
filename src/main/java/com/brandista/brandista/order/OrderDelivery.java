package com.brandista.brandista.order;

import jakarta.persistence.*;

@Embeddable
public class OrderDelivery {
    @Column(name="recipient_name",nullable=false,length=120) private String recipientName;
    @Column(name="phone_number",nullable=false,length=25) private String phoneNumber;
    @Column(name="address_line1",nullable=false,length=255) private String addressLine1;
    @Column(name="address_line2",length=255) private String addressLine2;
    @Column(nullable=false,length=100) private String city;
    @Column(length=100) private String province;
    @Column(name="postal_code",length=20) private String postalCode;
    @Column(name="delivery_instructions",length=500) private String deliveryInstructions;
    protected OrderDelivery() { }
    OrderDelivery(CheckoutModels.Delivery value) {
        recipientName=value.recipientName(); phoneNumber=value.phoneNumber(); addressLine1=value.addressLine1();
        addressLine2=value.addressLine2(); city=value.city(); province=value.province(); postalCode=value.postalCode();
        deliveryInstructions=value.deliveryInstructions();
    }
    CheckoutModels.Delivery response() {
        return new CheckoutModels.Delivery(recipientName,phoneNumber,addressLine1,addressLine2,city,province,postalCode,deliveryInstructions);
    }
}
