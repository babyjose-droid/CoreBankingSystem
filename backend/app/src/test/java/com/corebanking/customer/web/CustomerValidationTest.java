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
                new CustomerService.Address("1 Test Street", null, "Kochi", "32", "682001"), null, null, null, null);
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

    /** SEC-03: the applier reads date of birth, city and pincode from the sealed part, or from the clear part of older requests. */
    @Test
    void applier_reads_both_payload_shapes() {
        java.util.Map<String, Object> sealedNew = java.util.Map.of("dateOfBirth", "1990-05-15", "city", "Kochi", "pincode", "682001");
        java.util.Map<String, Object> clearNew = java.util.Map.of("ageBand", "36-45", "pincodeMasked", "682XXX");
        assertEquals("1990-05-15", CustomerService.Applier.sealedFirst(sealedNew, clearNew, "dateOfBirth"));
        assertEquals("682001", CustomerService.Applier.sealedFirst(sealedNew, clearNew, "pincode"));

        java.util.Map<String, Object> sealedOld = java.util.Map.of("firstName", "Anita");
        java.util.Map<String, Object> clearOld = java.util.Map.of("dateOfBirth", "1990-05-15", "city", "Kochi", "pincode", "682001");
        assertEquals("1990-05-15", CustomerService.Applier.sealedFirst(sealedOld, clearOld, "dateOfBirth"));
        assertEquals("Kochi", CustomerService.Applier.sealedFirst(sealedOld, clearOld, "city"));
        org.junit.jupiter.api.Assertions.assertNull(CustomerService.Applier.sealedFirst(sealedOld, java.util.Map.of(), "pincode"));   // no address
    }
}
