package com.corebanking.customer.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.corebanking.platform.ApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CustomerValidationTest {

    static CustomerService.Input person(LocalDate dob, String pan, String mobile) {
        return new CustomerService.Input("INDIVIDUAL", "Anita", null, "CLAUDE-TEST", dob, "FEMALE", pan, mobile, null, "HO",
                new CustomerService.Address("1 Test Street", null, "Kochi", "32", "682001"), null, null);
    }

    @Test
    void valid_customer_passes() {
        assertDoesNotThrow(() -> CustomerService.validate(person(LocalDate.of(1990, 5, 15), "ABCDE1234F", "9876543210")));
    }

    @Test
    void minors_bad_pan_and_bad_mobile_are_rejected() {
        assertThrows(ApiException.class, () -> CustomerService.validate(person(LocalDate.now().minusYears(17), null, "9876543210")));
        assertThrows(IllegalArgumentException.class, () -> CustomerService.validate(person(LocalDate.of(1990, 1, 1), "ABCD1234F", "9876543210")));
        assertThrows(IllegalArgumentException.class, () -> CustomerService.validate(person(LocalDate.of(1990, 1, 1), null, "5876543210")));
    }

    @Test
    void display_name_skips_blank_parts() {
        assertEquals("Anita CLAUDE-TEST", CustomerService.displayName(person(LocalDate.of(1990, 1, 1), null, "9876543210")));
    }
}
