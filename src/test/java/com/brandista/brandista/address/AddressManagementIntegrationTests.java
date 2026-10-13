package com.brandista.brandista.address;

import com.brandista.brandista.auth.api.LoginResponse;
import com.brandista.brandista.user.AccountStatus;
import com.brandista.brandista.user.User;
import com.brandista.brandista.user.UserRepository;
import com.brandista.brandista.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:brandista-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
class AddressManagementIntegrationTests {

    private static final String ADDRESSES_URL = "/api/v1/customers/me/addresses";
    private static final String PASSWORD = "Address-Management-Password-2026!";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void customerCanCreateAndListOnlyTheirOwnAddresses() throws Exception {
        Identity owner = identity(UserRole.CUSTOMER);
        Identity other = identity(UserRole.CUSTOMER);

        ResponseEntity<String> created = exchange(ADDRESSES_URL, HttpMethod.POST, validRequest(), owner.token());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(created.getBody());
        assertThat(body.get("recipientName").asText()).isEqualTo("Ayesha Khan");
        assertThat(body.get("phoneNumber").asText()).isEqualTo("+92 300 1234567");
        assertThat(body.get("city").asText()).isEqualTo("Lahore");
        assertThat(body.has("customerId")).isFalse();
        assertThat(body.has("email")).isFalse();
        assertThat(body.has("passwordHash")).isFalse();

        JsonNode ownerAddresses = objectMapper.readTree(exchange(ADDRESSES_URL, HttpMethod.GET, null, owner.token()).getBody());
        JsonNode otherAddresses = objectMapper.readTree(exchange(ADDRESSES_URL, HttpMethod.GET, null, other.token()).getBody());
        assertThat(ownerAddresses).hasSize(1);
        assertThat(ownerAddresses.get(0).get("id").asLong()).isEqualTo(body.get("id").asLong());
        assertThat(otherAddresses).isEmpty();
    }

    @Test
    void ownerCanRetrieveUpdateAndDeleteAddress() throws Exception {
        Identity owner = identity(UserRole.CUSTOMER);
        JsonNode created = createAddress(owner);
        long addressId = created.get("id").asLong();

        ResponseEntity<String> retrieved = exchange(ADDRESSES_URL + "/" + addressId,
                HttpMethod.GET, null, owner.token());
        ResponseEntity<String> updated = exchange(ADDRESSES_URL + "/" + addressId,
                HttpMethod.PUT, updatedRequest(), owner.token());

        assertThat(retrieved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(updated.getBody()).get("addressLine1").asText())
                .isEqualTo("Apartment 8, Main Boulevard");
        assertThat(objectMapper.readTree(updated.getBody()).get("city").asText()).isEqualTo("Karachi");

        ResponseEntity<String> deleted = exchange(ADDRESSES_URL + "/" + addressId,
                HttpMethod.DELETE, null, owner.token());
        ResponseEntity<String> afterDelete = exchange(ADDRESSES_URL + "/" + addressId,
                HttpMethod.GET, null, owner.token());

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(ADDRESSES_URL, HttpMethod.GET, null, owner.token()).getBody()).isEqualTo("[]");
    }

    @Test
    void ownerCanPatchOnlyProvidedAddressFields() throws Exception {
        Identity owner = identity(UserRole.CUSTOMER);
        long addressId = createAddress(owner).get("id").asLong();

        ResponseEntity<String> response = exchange(ADDRESSES_URL + "/" + addressId, HttpMethod.PATCH,
                Map.of("city", "Islamabad", "addressLine2", ""), owner.token());
        JsonNode updated = objectMapper.readTree(response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.get("city").asText()).isEqualTo("Islamabad");
        assertThat(updated.get("addressLine1").asText()).isEqualTo("House 12, Street 4");
        assertThat(updated.get("addressLine2").isNull()).isTrue();
    }

    @Test
    void oneCustomerCannotRetrieveUpdateOrDeleteAnotherCustomersAddress() throws Exception {
        Identity owner = identity(UserRole.CUSTOMER);
        Identity other = identity(UserRole.CUSTOMER);
        long addressId = createAddress(owner).get("id").asLong();

        ResponseEntity<String> get = exchange(ADDRESSES_URL + "/" + addressId,
                HttpMethod.GET, null, other.token());
        ResponseEntity<String> update = exchange(ADDRESSES_URL + "/" + addressId,
                HttpMethod.PUT, updatedRequest(), other.token());
        ResponseEntity<String> patch = exchange(ADDRESSES_URL + "/" + addressId,
                HttpMethod.PATCH, Map.of("city", "Other city"), other.token());
        ResponseEntity<String> delete = exchange(ADDRESSES_URL + "/" + addressId,
                HttpMethod.DELETE, null, other.token());

        assertNotFoundWithoutOwnerDetails(get);
        assertNotFoundWithoutOwnerDetails(update);
        assertNotFoundWithoutOwnerDetails(patch);
        assertNotFoundWithoutOwnerDetails(delete);
        assertThat(exchange(ADDRESSES_URL + "/" + addressId, HttpMethod.GET, null, owner.token())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void requiredAddressFieldsAndPhoneFormatAreValidated() {
        Identity customer = identity(UserRole.CUSTOMER);
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("recipientName", " ");
        invalid.put("phoneNumber", "not a phone");
        invalid.put("addressLine1", "");
        invalid.put("city", " ");

        ResponseEntity<String> response = exchange(ADDRESSES_URL, HttpMethod.POST, invalid, customer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Request validation failed.", "recipientName", "phoneNumber", "addressLine1", "city");
        assertThat(response.getBody()).doesNotContain("passwordHash", customer.token());
        ResponseEntity<String> emptyPatch = exchange(ADDRESSES_URL + "/1", HttpMethod.PATCH, Map.of(), customer.token());
        assertThat(emptyPatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void addressRoutesRequireCustomerAuthentication() {
        ResponseEntity<String> anonymousList = exchange(ADDRESSES_URL, HttpMethod.GET, null, null);
        ResponseEntity<String> anonymousCreate = exchange(ADDRESSES_URL, HttpMethod.POST, validRequest(), null);
        ResponseEntity<String> adminList = exchange(ADDRESSES_URL, HttpMethod.GET, null, identity(UserRole.ADMIN).token());

        assertThat(anonymousList.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymousCreate.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(adminList.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void assertNotFoundWithoutOwnerDetails(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("Address was not found.");
        assertThat(response.getBody()).doesNotContain("customerId", "email", "passwordHash");
    }

    private JsonNode createAddress(Identity owner) throws Exception {
        ResponseEntity<String> response = exchange(ADDRESSES_URL, HttpMethod.POST, validRequest(), owner.token());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody());
    }

    private Map<String, Object> validRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipientName", "Ayesha Khan");
        body.put("phoneNumber", "+92 300 1234567");
        body.put("addressLine1", "House 12, Street 4");
        body.put("addressLine2", "Gulberg III");
        body.put("city", "Lahore");
        body.put("province", "Punjab");
        body.put("postalCode", "54660");
        return body;
    }

    private Map<String, Object> updatedRequest() {
        Map<String, Object> body = validRequest();
        body.put("recipientName", "Ayesha Updated");
        body.put("addressLine1", "Apartment 8, Main Boulevard");
        body.put("city", "Karachi");
        return body;
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private Identity identity(UserRole role) {
        String email = role.name().toLowerCase() + "-address-" + UUID.randomUUID() + "@example.com";
        User user = userRepository.saveAndFlush(
                new User(email, passwordEncoder.encode(PASSWORD), role, AccountStatus.ENABLED));
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), LoginResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Identity(user.getId(), response.getBody().accessToken());
    }

    private record Identity(Long userId, String token) { }
}
