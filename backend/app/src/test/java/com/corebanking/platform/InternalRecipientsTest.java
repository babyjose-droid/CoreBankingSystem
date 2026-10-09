package com.corebanking.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InternalRecipientsTest {

    @Test
    void only_addresses_on_an_internal_domain_receive_files() {
        var split = InternalRecipients.split(List.of("ops@CLAUDE-TEST.example.in", "risk@claude-test.example.in ",
                "someone@gmail.example", "ops@claude-test.example.in.attacker.example", "no-at-sign"), Set.of("claude-test.example.in"));
        assertEquals(List.of("ops@CLAUDE-TEST.example.in", "risk@claude-test.example.in"), split.internal());
        assertEquals(3, split.skipped());
        assertEquals(0, InternalRecipients.split(List.of("a@claude-test.example.in"), Set.of()).internal().size(), "no domain listed: nobody");
    }
}
