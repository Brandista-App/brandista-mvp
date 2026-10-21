package com.brandista.brandista.merchant;

import com.brandista.brandista.order.CheckoutFailure;
import com.brandista.brandista.order.CheckoutModels;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import static com.brandista.brandista.merchant.MerchantOrderModels.*;

@Service
@Validated
@PreAuthorize("isAuthenticated()")
@Transactional(isolation=Isolation.READ_COMMITTED)
public class MerchantOrderService {
    private final JdbcTemplate jdbc;
    private final BrandAccess access;
    public MerchantOrderService(JdbcTemplate jdbc,BrandAccess access) { this.jdbc=jdbc;this.access=access; }

    private static final String SUMMARY="SELECT f.order_id,f.brand_id,f.status,f.version,o.created_at,o.currency,"
            + "(SELECT SUM(i.line_subtotal) FROM customer_order_items i WHERE i.order_id=f.order_id AND i.brand_id=f.brand_id) AS items_subtotal,"
            + "(SELECT COUNT(*) FROM customer_order_items i WHERE i.order_id=f.order_id AND i.brand_id=f.brand_id) AS line_count "
            + "FROM merchant_order_fulfillments f JOIN customer_orders o ON o.id=f.order_id ";

    public Page list(@Positive long brandId,@Min(0) int page,@Min(1) @Max(100) int size,Status status) {
        access.requireBrand(brandId);
        String where="WHERE f.brand_id=?"+(status==null?"":" AND f.status=?");
        var args=new ArrayList<Object>();args.add(brandId);if(status!=null)args.add(status.name());
        long count=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_order_fulfillments f "+where,Long.class,args.toArray()));
        args.add(size);args.add((long)page*size);
        var rows=jdbc.query(SUMMARY+where+" ORDER BY o.created_at DESC,f.order_id DESC LIMIT ? OFFSET ?",(r,n)->summary(r),args.toArray());
        return new Page(rows,page,size,count,(int)Math.min(Integer.MAX_VALUE,(count+size-1)/size));
    }

    public Detail get(@Positive long brandId,@Positive long orderId) {
        access.requireBrand(brandId);
        return detail(brandId,orderId);
    }

    public Detail transition(@Positive long brandId,@Positive long orderId,@NotNull @Valid Transition request) {
        access.requireBrand(brandId);
        var rows=jdbc.queryForList("SELECT status,version FROM merchant_order_fulfillments WHERE order_id=? AND brand_id=? FOR UPDATE",orderId,brandId);
        if(rows.isEmpty()) throw missing();
        // A revocation committed while waiting for the fulfillment lock must be observed.
        long actor=access.requireBrand(brandId);
        Status previous=Status.valueOf((String)rows.getFirst().get("status"));
        long version=((Number)rows.getFirst().get("version")).longValue();
        if(version!=request.expectedVersion() || !previous.next().contains(request.status())) throw conflict();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM customer_orders WHERE id=? AND status='PLACED' "
                + "AND payment_method='COD' AND payment_status='UNPAID' AND currency='PKR'",Long.class,orderId)!=1) throw conflict();
        LocalDateTime now=LocalDateTime.now(Clock.systemUTC());
        if(jdbc.update("UPDATE merchant_order_fulfillments SET status=?,version=version+1,updated_at=? "
                + "WHERE order_id=? AND brand_id=? AND version=?",request.status().name(),now,orderId,brandId,version)!=1) throw conflict();
        jdbc.update("INSERT INTO merchant_order_events(order_id,brand_id,actor_user_id,from_status,to_status,version,occurred_at) "
                + "VALUES(?,?,?,?,?,?,?)",orderId,brandId,actor,previous.name(),request.status().name(),version+1,now);
        return detail(brandId,orderId);
    }

    private Detail detail(long brandId,long orderId) {
        // Keep summary, lines and timeline coherent with a concurrent transition.
        if(jdbc.queryForList("SELECT order_id FROM merchant_order_fulfillments WHERE order_id=? AND brand_id=? FOR UPDATE",orderId,brandId).isEmpty())
            throw missing();
        access.requireBrand(brandId);
        var rows=jdbc.query(SUMMARY+"WHERE f.order_id=? AND f.brand_id=?",(r,n)->summary(r),orderId,brandId);
        if(rows.isEmpty()) throw missing();
        var delivery=jdbc.queryForObject("SELECT recipient_name,phone_number,address_line1,address_line2,city,province,postal_code,delivery_instructions "
                + "FROM customer_orders WHERE id=?",(r,n)->new CheckoutModels.Delivery(r.getString("recipient_name"),r.getString("phone_number"),
                r.getString("address_line1"),r.getString("address_line2"),r.getString("city"),r.getString("province"),r.getString("postal_code"),r.getString("delivery_instructions")),orderId);
        var items=jdbc.query("SELECT product_id,product_slug,product_name,brand_id,brand_name,quantity,unit_price,line_subtotal "
                + "FROM customer_order_items WHERE order_id=? AND brand_id=? ORDER BY id",(r,n)->new CheckoutModels.Line(r.getLong("product_id"),
                r.getString("product_slug"),r.getString("product_name"),r.getLong("brand_id"),r.getString("brand_name"),r.getInt("quantity"),
                r.getBigDecimal("unit_price"),r.getBigDecimal("line_subtotal")),orderId,brandId);
        var events=jdbc.query("SELECT from_status,to_status,version,occurred_at FROM merchant_order_events WHERE order_id=? AND brand_id=? ORDER BY version",
                (r,n)->new Event(Status.valueOf(r.getString("from_status")),Status.valueOf(r.getString("to_status")),r.getLong("version"),r.getTimestamp("occurred_at").toLocalDateTime()),orderId,brandId);
        return new Detail(rows.getFirst(),delivery,items,events);
    }
    private Summary summary(ResultSet r) throws SQLException {
        Status status=Status.valueOf(r.getString("status"));
        return new Summary(r.getLong("order_id"),r.getLong("brand_id"),r.getTimestamp("created_at").toLocalDateTime(),status,
                r.getLong("version"),r.getString("currency"),r.getBigDecimal("items_subtotal"),r.getInt("line_count"),status.next());
    }
    private CheckoutFailure missing() { return new CheckoutFailure(HttpStatus.NOT_FOUND,"Order not found."); }
    private CheckoutFailure conflict() { return new CheckoutFailure(HttpStatus.CONFLICT,"Fulfillment state changed or transition is not allowed. Reload the order."); }
}
