package com.corebanking.lending.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LoanPartyServiceTest {

    @Test
    void a_stressed_loan_needs_two_checkers_for_a_release() {
        assertEquals("RELEASE", LoanPartyService.actionFor("STANDARD"));
        for (String c : new String[] {"SMA0", "SMA1", "SMA2", "SUBSTANDARD", "DOUBTFUL1", "DOUBTFUL2", "DOUBTFUL3", "LOSS"}) {
            assertEquals("RELEASE_STRESSED", LoanPartyService.actionFor(c), c);
        }
    }
}
