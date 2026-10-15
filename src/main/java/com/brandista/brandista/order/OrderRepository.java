package com.brandista.brandista.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<CustomerOrder,Long> {
    Optional<CustomerOrder> findByCustomerIdAndIdempotencyKey(Long customerId,String key);
    Optional<CustomerOrder> findByIdAndCustomerId(Long id,Long customerId);
    Page<CustomerOrder> findAllByCustomerIdOrderByCreatedAtDescIdDesc(Long customerId,Pageable pageable);
}
