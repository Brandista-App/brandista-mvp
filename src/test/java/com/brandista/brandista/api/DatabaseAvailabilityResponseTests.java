package com.brandista.brandista.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

class DatabaseAvailabilityResponseTests {
    @RestController @Profile("availability-response-fixture") static class UnavailableFixture {
        @GetMapping("/resource-unavailable") String resource() {
            throw new DataAccessResourceFailureException("jdbc:mysql://private-db/password=synthetic-secret");
        }
        @GetMapping("/transaction-unavailable") String transaction() {
            throw new CannotCreateTransactionException("Unable to acquire connection; synthetic-secret");
        }
    }
    @Test void exhaustedPoolUsesExistingErrorEnvelopeWithRetryable503AndNoDriverDetails() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new UnavailableFixture()).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/resource-unavailable"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.path").value("/resource-unavailable"))
                .andExpect(jsonPath("$.message").value("Shopping service is temporarily unavailable. Retry shortly."))
                .andExpect(content().string(not(containsString("synthetic-secret"))))
                .andExpect(content().string(not(containsString("private-db"))));
    }
    @Test void transactionAcquisitionFailureAlsoReturnsSanitized503() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new UnavailableFixture()).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/transaction-unavailable"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(content().string(not(containsString("synthetic-secret"))));
    }
}
