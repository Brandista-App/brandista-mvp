package com.brandista.brandista.order;

import com.brandista.brandista.address.*;
import com.brandista.brandista.address.service.AddressNotFoundException;
import com.brandista.brandista.cart.*;
import com.brandista.brandista.product.*;
import com.brandista.brandista.brand.BrandStatus;
import com.brandista.brandista.category.CategoryStatus;
import com.brandista.brandista.user.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;
import static com.brandista.brandista.order.CheckoutModels.*;

@Service
public class CheckoutService {
    private final UserRepository users;
    private final CustomerAddressRepository addresses;
    private final CartRepository carts;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final ObjectMapper mapper;
    private final boolean codEnabled;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public CheckoutService(UserRepository users,CustomerAddressRepository addresses,CartRepository carts,
                           ProductRepository products,OrderRepository orders,ObjectMapper mapper,
                           @Value("${brandista.checkout.cod-enabled:false}") boolean codEnabled,
                           org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.users=users;this.addresses=addresses;this.carts=carts;this.products=products;this.orders=orders;this.mapper=mapper;this.codEnabled=codEnabled;
        this.jdbc=jdbc;
    }
    public boolean isCodEnabled() { return codEnabled; }
    @Transactional
    public Preview preview(String subject,Long addressId) {
        User customer=customer(subject,true);
        requireEnabled();
        return prepare(customer,addressId).preview;
    }
    @Transactional
    public Placement place(String subject,String key,PlaceRequest request) {
        User customer=customer(subject,true); // Serializes cart, address/default and checkout writes per customer.
        String requestHash=hash(List.of(request.addressId(),request.quoteToken(),"COD"));
        var previous=orders.findByCustomerIdAndIdempotencyKey(customer.getId(),key);
        if(previous.isPresent()) {
            if(!previous.get().requestHash.equals(requestHash)) throw conflict("This checkout key was already used for a different review.");
            return new Placement(previous.get().response(),true);
        }
        requireEnabled();
        Prepared prepared=prepare(customer,request.addressId());
        if(!prepared.preview.quoteToken().equals(request.quoteToken())) throw conflict("Your cart, address or prices changed. Review checkout again.");
        // All lines have been validated and locked before any mutation. One transaction commits everything.
        prepared.products.forEach((product,quantity) -> product.consumeStock(quantity));
        CustomerOrder saved=orders.saveAndFlush(new CustomerOrder(customer.getId(),key,requestHash,request.addressId(),prepared.preview));
        // Same checkout transaction: exactly one fulfillment per snapshotted brand.
        jdbc.update("INSERT INTO merchant_order_fulfillments(order_id,brand_id,status,version,created_at,updated_at) "
                + "SELECT DISTINCT o.id,i.brand_id,'PLACED',0,o.created_at,o.created_at "
                + "FROM customer_orders o JOIN customer_order_items i ON i.order_id=o.id WHERE o.id=?",saved.id);
        prepared.cart.clearItems();
        carts.saveAndFlush(prepared.cart);
        return new Placement(saved.response(),false);
    }
    @Transactional(readOnly=true)
    public OrderResponse get(String subject,Long id) {
        User customer=customer(subject,false);
        return orders.findByIdAndCustomerId(id,customer.getId()).orElseThrow(() -> new CheckoutFailure(HttpStatus.NOT_FOUND,"Order not found.")).response();
    }
    @Transactional(readOnly=true)
    public OrderPage list(String subject,int page,int size) {
        var result=orders.findAllByCustomerIdOrderByCreatedAtDescIdDesc(customer(subject,false).getId(),PageRequest.of(page,size));
        return new OrderPage(result.getContent().stream().map(CustomerOrder::response).toList(),page,size,result.getTotalElements(),result.getTotalPages());
    }
    private Prepared prepare(User customer,Long addressId) {
        CustomerAddress address=addresses.findByIdAndCustomer_Id(addressId,customer.getId()).orElseThrow(AddressNotFoundException::new);
        Cart cart=carts.findByCustomer_Id(customer.getId()).orElseThrow(() -> conflict("Your cart is empty."));
        if(cart.getItems().isEmpty()) throw conflict("Your cart is empty.");
        // Access proxy IDs without loading stale product state; lock current product/brand/category rows in ID order.
        List<CartItem> sorted=cart.getItems().stream().sorted(Comparator.comparing(item -> item.getProduct().getId())).toList();
        Map<Product,Integer> locked=new LinkedHashMap<>();
        List<Line> lines=new ArrayList<>();
        BigDecimal subtotal=BigDecimal.ZERO;
        for(CartItem item:sorted) {
            Product product=products.findForCheckout(item.getProduct().getId()).orElseThrow(() -> conflict("A cart product is no longer available."));
            if(product.getPublicationStatus()!=ProductPublicationStatus.PUBLISHED || product.getAvailability()!=ProductAvailability.AVAILABLE
                    || product.getBrand().getStatus()!=BrandStatus.ACTIVE || product.getCategory().getStatus()!=CategoryStatus.ACTIVE) {
                throw conflict("A cart product is no longer available. Update your cart.");
            }
            if(item.getQuantity()>product.getStockQuantity()) throw conflict("Not enough stock for " + product.getName() + ". Update your cart.");
            BigDecimal line=product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotal=subtotal.add(line);
            locked.put(product,item.getQuantity());
            lines.add(new Line(product.getId(),product.getSlug(),product.getName(),product.getBrand().getId(),product.getBrand().getName(),item.getQuantity(),product.getPrice(),line));
        }
        if(subtotal.precision()-subtotal.scale()>17) throw conflict("Cart total exceeds the supported range.");
        Delivery delivery=new Delivery(address.getRecipientName(),address.getPhoneNumber(),address.getAddressLine1(),address.getAddressLine2(),address.getCity(),address.getProvince(),address.getPostalCode(),address.getDeliveryInstructions());
        String token=hash(List.of(addressId,delivery,lines,subtotal));
        return new Prepared(cart,locked,new Preview(token,delivery,List.copyOf(lines),subtotal,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,subtotal,"PKR","COD"));
    }
    private User customer(String subject,boolean lock) {
        long id;
        try { id=Long.parseLong(subject); } catch(NumberFormatException exception) { throw new CheckoutFailure(HttpStatus.FORBIDDEN,"Customer account required."); }
        User user=(lock?users.findByIdForUpdate(id):users.findById(id)).orElseThrow(() -> new CheckoutFailure(HttpStatus.FORBIDDEN,"Customer account required."));
        if(user.getRole()!=UserRole.CUSTOMER || user.getStatus()!=AccountStatus.ENABLED) throw new CheckoutFailure(HttpStatus.FORBIDDEN,"Enabled customer account required.");
        return user;
    }
    private void requireEnabled() { if(!codEnabled) throw conflict("COD checkout is not enabled in this environment."); }
    private CheckoutFailure conflict(String message) { return new CheckoutFailure(HttpStatus.CONFLICT,message); }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value))); }
        catch(Exception exception) { throw new IllegalStateException("Checkout review could not be generated",exception); }
    }
    private record Prepared(Cart cart,Map<Product,Integer> products,Preview preview) { }
}
