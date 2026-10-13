package com.brandista.brandista.address;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, Long> {

    List<CustomerAddress> findAllByCustomer_IdOrderByCreatedAtDescIdDesc(Long customerId);

    Optional<CustomerAddress> findByIdAndCustomer_Id(Long id, Long customerId);
}
