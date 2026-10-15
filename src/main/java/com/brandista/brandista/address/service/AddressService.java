package com.brandista.brandista.address.service;

import com.brandista.brandista.address.CustomerAddress;
import com.brandista.brandista.address.CustomerAddressRepository;
import com.brandista.brandista.address.api.AddressRequest;
import com.brandista.brandista.address.api.AddressPatchRequest;
import com.brandista.brandista.address.api.AddressResponse;
import com.brandista.brandista.user.User;
import com.brandista.brandista.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AddressService {

    private final CustomerAddressRepository addressRepository;
    private final UserRepository userRepository;

    public AddressService(CustomerAddressRepository addressRepository, UserRepository userRepository) {
        this.addressRepository = addressRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public AddressResponse create(String principalSubject, AddressRequest request) {
        User customer = requireCustomer(principalSubject);
        CustomerAddress address = new CustomerAddress(customer, request.recipientName(), request.phoneNumber(),
                request.addressLine1(), request.addressLine2(), request.city(), request.province(), request.postalCode());
        address.setDeliveryInstructions(request.deliveryInstructions());
        addressRepository.saveAndFlush(address);
        if (customer.getDefaultAddressId() == null) customer.setDefaultAddressId(address.getId());
        return toResponse(address);
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> list(String principalSubject) {
        Long customerId = customerId(principalSubject);
        return addressRepository.findAllByCustomer_IdOrderByCreatedAtDescIdDesc(customerId).stream()
                .map(AddressService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AddressResponse get(String principalSubject, Long addressId) {
        return toResponse(findOwned(principalSubject, addressId));
    }

    @Transactional
    public AddressResponse update(String principalSubject, Long addressId, AddressRequest request) {
        requireCustomer(principalSubject);
        CustomerAddress address = findOwned(principalSubject, addressId);
        address.updateDetails(request.recipientName(), request.phoneNumber(), request.addressLine1(),
                request.addressLine2(), request.city(), request.province(), request.postalCode());
        address.setDeliveryInstructions(request.deliveryInstructions());
        return toResponse(addressRepository.saveAndFlush(address));
    }

    @Transactional
    public AddressResponse patch(String principalSubject, Long addressId, AddressPatchRequest request) {
        requireCustomer(principalSubject);
        CustomerAddress address = findOwned(principalSubject, addressId);
        address.updateDetails(valueOr(request.recipientName(), address.getRecipientName()),
                valueOr(request.phoneNumber(), address.getPhoneNumber()),
                valueOr(request.addressLine1(), address.getAddressLine1()),
                valueOr(request.addressLine2(), address.getAddressLine2()),
                valueOr(request.city(), address.getCity()),
                valueOr(request.province(), address.getProvince()),
                valueOr(request.postalCode(), address.getPostalCode()));
        if (request.deliveryInstructions() != null) address.setDeliveryInstructions(request.deliveryInstructions());
        return toResponse(addressRepository.saveAndFlush(address));
    }

    @Transactional
    public void delete(String principalSubject, Long addressId) {
        User customer = requireCustomer(principalSubject);
        CustomerAddress address = findOwned(principalSubject, addressId);
        if (addressId.equals(customer.getDefaultAddressId())) {
            customer.setDefaultAddressId(addressRepository.findAllByCustomer_IdOrderByCreatedAtDescIdDesc(customer.getId())
                    .stream().filter(a -> !addressId.equals(a.getId())).map(CustomerAddress::getId).findFirst().orElse(null));
            userRepository.flush();
        }
        addressRepository.delete(address);
    }

    @Transactional
    public AddressResponse setDefault(String subject, Long addressId) {
        User customer = requireCustomer(subject);
        CustomerAddress address = findOwned(subject, addressId);
        customer.setDefaultAddressId(address.getId());
        return toResponse(address);
    }

    private CustomerAddress findOwned(String principalSubject, Long addressId) {
        return addressRepository.findByIdAndCustomer_Id(addressId, customerId(principalSubject))
                .orElseThrow(AddressNotFoundException::new);
    }

    private User requireCustomer(String principalSubject) {
        return userRepository.findByIdForUpdate(customerId(principalSubject)).orElseThrow(AddressNotFoundException::new);
    }

    private Long customerId(String principalSubject) {
        try {
            long id = Long.parseLong(principalSubject);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Only application-issued JWT subjects contain numeric user IDs.
        }
        throw new AddressNotFoundException();
    }

    private String valueOr(String candidate, String existing) {
        return candidate == null ? existing : candidate;
    }

    private static AddressResponse toResponse(CustomerAddress address) {
        return new AddressResponse(address.getId(), address.getRecipientName(), address.getPhoneNumber(),
                address.getAddressLine1(), address.getAddressLine2(), address.getCity(), address.getProvince(),
                address.getPostalCode(), address.getCreatedAt(), address.getUpdatedAt(),
                address.getDeliveryInstructions(), address.getId().equals(address.getCustomer().getDefaultAddressId()));
    }
}
