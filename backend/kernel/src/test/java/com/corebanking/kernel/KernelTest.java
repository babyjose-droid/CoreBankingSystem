package com.corebanking.kernel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KernelTest {

    static BigDecimal bd(String s) { return new BigDecimal(s); }

    // ---- maker-checker ------------------------------------------------------------------------
    @Test void approval_rules_most_specific_wins() {
        var p = new ApprovalPolicy(ApprovalPolicy.starterRules());
        assertEquals(1, p.checkersRequired("CUSTOMER", "CREATE", null));
        assertEquals(1, p.checkersRequired("VOUCHER", "CREATE", bd("999999.99")));
        assertEquals(2, p.checkersRequired("VOUCHER", "CREATE", bd("1000000")));
        assertEquals(1, p.checkersRequired("VOUCHER", "REVERSE", bd("5000000")));
        assertEquals(1, new ApprovalPolicy(List.of()).checkersRequired("X", "Y", null));   // safe default
    }

    @Test void maker_cannot_approve_or_reject_own_request() {
        assertThrows(ApprovalPolicy.ApprovalException.class, () -> ApprovalPolicy.approve("alice", Set.of(), "ALICE", 1));
        assertThrows(ApprovalPolicy.ApprovalException.class, () -> ApprovalPolicy.reject("alice", "alice", "no"));
        assertThrows(ApprovalPolicy.ApprovalException.class, () -> ApprovalPolicy.reject("alice", "bob", " "));
        ApprovalPolicy.reject("alice", "bob", "wrong GL");
    }

    @Test void two_checker_rule_needs_two_distinct_people() {
        assertEquals(ApprovalPolicy.Outcome.NEED_MORE_APPROVALS, ApprovalPolicy.approve("alice", Set.of(), "bob", 2));
        assertThrows(ApprovalPolicy.ApprovalException.class, () -> ApprovalPolicy.approve("alice", Set.of("bob"), "bob", 2));
        assertEquals(ApprovalPolicy.Outcome.APPLY, ApprovalPolicy.approve("alice", Set.of("bob"), "carol", 2));
        assertEquals(ApprovalPolicy.Outcome.APPLY, ApprovalPolicy.approve("alice", Set.of(), "bob", 0));
    }

    @Test void field_diff_reports_changed_paths_only() {
        var before = Map.<String, Object>of("name", "Kochi", "state", "32", "addr", Map.of("pin", "682001", "city", "Kochi"), "rate", bd("18.0"));
        var after = Map.<String, Object>of("name", "Kochi Main", "state", "32", "addr", Map.of("pin", "682011", "city", "Kochi"), "rate", bd("18"));
        var d = FieldDiff.between(before, after);
        assertEquals(List.of("addr.pin", "name"), d.stream().map(FieldDiff.Change::path).toList());
        assertEquals(3, FieldDiff.between(null, Map.of("a", 1, "b", 2, "c", 3)).size());
    }

    // ---- masking and crypto ------------------------------------------------------------------
    @Test void masking() {
        assertEquals("XXXXX1234X", Masking.pan("abcde1234f"));
        assertEquals("XXXXXX3210", Masking.mobile("+91 98765 43210".substring(4)));
        assertEquals("XXXX XXXX 9012", Masking.aadhaar("1234 5678 9012"));
        assertEquals("j*******@example.com", Masking.email("jane.doe@example.com"));
        assertEquals("XXXXXXXXXX0017", Masking.account("10010000000017"));
        assertEquals("XXXXXXXXXX", Masking.pan("bad"));
    }

    static byte[] key(int seed) {
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) k[i] = (byte) (seed * 31 + i);
        return k;
    }

    @Test void pii_cipher_roundtrip_and_tamper_detection() {
        var c = new PiiCipher(7, key(1), key(2));
        byte[] ct = c.encrypt("ABCDE1234F", "customer.pan");
        assertEquals("ABCDE1234F", c.decrypt(ct, "customer.pan"));
        assertFalse(new String(ct, StandardCharsets.ISO_8859_1).contains("ABCDE1234F"));
        assertNotEquals(new String(ct, StandardCharsets.ISO_8859_1), new String(c.encrypt("ABCDE1234F", "customer.pan"), StandardCharsets.ISO_8859_1)); // random IV
        assertThrows(IllegalArgumentException.class, () -> c.decrypt(ct, "customer.mobile"));   // moved column
        byte[] tampered = ct.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> c.decrypt(tampered, "customer.pan"));
        var other = new PiiCipher(8, key(3), key(2));
        assertThrows(IllegalArgumentException.class, () -> other.decrypt(ct, "customer.pan"));
    }

    @Test void blind_index_is_deterministic_normalised_and_namespaced() {
        var c = new PiiCipher(1, key(1), key(2));
        assertArrayEquals(c.blindIndex("PAN", "abcde1234f "), c.blindIndex("PAN", "ABCDE1234F"));
        assertFalse(java.util.Arrays.equals(c.blindIndex("PAN", "9876543210"), c.blindIndex("MOBILE", "9876543210")));
        var otherTenant = new PiiCipher(1, key(1), key(9));
        assertFalse(java.util.Arrays.equals(c.blindIndex("PAN", "ABCDE1234F"), otherTenant.blindIndex("PAN", "ABCDE1234F")));
    }

    @Test void dedupe_normalisation() {
        assertEquals("RAHUL K SHARMA", Dedupe.normaliseName("Mr. Rahul", " K.", "Sharma "));
        assertEquals("JOSE PEREZ", Dedupe.normaliseName("Dr José", null, "Pérez"));
        assertEquals("ANITA NAIR|1990-05-15", Dedupe.nameDobKey("Smt. Anita", "", "NAIR", LocalDate.of(1990, 5, 15)));
        assertEquals("9876543210", Dedupe.normaliseMobile("+91-98765 43210"));
        assertEquals("9876543210", Dedupe.normaliseMobile("09876543210"));
        assertThrows(IllegalArgumentException.class, () -> Dedupe.normaliseMobile("12345"));
        assertEquals("ABCDE1234F", Dedupe.normalisePan(" abcde1234f"));
        assertEquals(Dedupe.Strength.EXACT, Dedupe.strength(Dedupe.Rule.PAN));
    }

    // ---- calendar and tax --------------------------------------------------------------------
    @Test void bank_calendar_second_and_fourth_saturday() {
        var cal = BusinessCalendar.bank(List.of(LocalDate.of(2026, 10, 2)));      // Gandhi Jayanti (Fri)
        assertFalse(cal.isWorkingDay(LocalDate.of(2026, 10, 10)));                  // 2nd Saturday
        assertTrue(cal.isWorkingDay(LocalDate.of(2026, 10, 3)));                    // 1st Saturday
        assertFalse(cal.isWorkingDay(LocalDate.of(2026, 10, 24)));                  // 4th Saturday
        assertEquals(LocalDate.of(2026, 10, 3), cal.nextWorkingDay(LocalDate.of(2026, 10, 1)));
        assertEquals(LocalDate.of(2026, 10, 12), cal.nextWorkingDay(LocalDate.of(2026, 10, 9)));
    }

    @Test void holiday_modes() {
        var cal = BusinessCalendar.nbfc(List.of(LocalDate.of(2026, 5, 29)));        // Friday holiday
        LocalDate sun = LocalDate.of(2026, 5, 31);
        assertEquals(LocalDate.of(2026, 6, 1), cal.adjust(sun, BusinessCalendar.HolidayMode.NEXT_DAY));
        assertEquals(LocalDate.of(2026, 5, 30), cal.adjust(sun, BusinessCalendar.HolidayMode.PREVIOUS_DAY));
        assertEquals(LocalDate.of(2026, 5, 30), cal.adjust(sun, BusinessCalendar.HolidayMode.MODIFIED_FOLLOWING));
        assertEquals(sun, cal.adjust(sun, BusinessCalendar.HolidayMode.NONE));
        var noOff = new BusinessCalendar(EnumSet.noneOf(DayOfWeek.class), false, List.of());
        assertTrue(noOff.isWorkingDay(sun));
    }

    @Test void tax_rate_chosen_by_value_date_and_no_overlaps() {
        var t = new TaxRateTable(List.of(
                new TaxRateTable.TaxRate("GST18", "GST", bd("18"), LocalDate.of(2017, 7, 1), null),
                new TaxRateTable.TaxRate("TDS194A", "TDS", bd("10"), LocalDate.of(2020, 4, 1), LocalDate.of(2026, 3, 31)),
                new TaxRateTable.TaxRate("TDS194A", "TDS", bd("7.5"), LocalDate.of(2026, 4, 1), null)));
        assertEquals(0, bd("10").compareTo(t.rate("TDS194A", LocalDate.of(2026, 3, 31))));
        assertEquals(0, bd("7.5").compareTo(t.rate("TDS194A", LocalDate.of(2026, 4, 1))));
        assertThrows(IllegalArgumentException.class, () -> t.rate("GST18", LocalDate.of(2017, 6, 30)));
        assertThrows(IllegalArgumentException.class, () -> new TaxRateTable(List.of(
                new TaxRateTable.TaxRate("X", "GST", bd("5"), LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1)),
                new TaxRateTable.TaxRate("X", "GST", bd("12"), LocalDate.of(2021, 1, 1), null))));
    }

    // ---- EOD engine --------------------------------------------------------------------------
    static final EodEngine.Context CTX = new EodEngine.Context(1, LocalDate.of(2026, 6, 30), "demo-nbfc");

    static EodEngine.ItemStep accrual(Map<String, Integer> booked, Set<String> failing) {
        return new EodEngine.ItemStep() {
            public String name() { return "Interest accrual"; }
            public List<String> items(EodEngine.Context c) {
                List<String> l = new ArrayList<>();
                for (int i = 1; i <= 100; i++) l.add("L" + i);
                return l;
            }
            public void process(EodEngine.Context c, String item) {
                if (failing.contains(item)) throw new IllegalStateException("bookingStartDate is null");
                booked.merge(item, 1, Integer::sum);
            }
        };
    }

    @Test void one_bad_account_does_not_stop_eod() {
        Map<String, Integer> booked = new ConcurrentHashMap<>();
        AtomicInteger dateAdvanced = new AtomicInteger();
        EodEngine.TaskStep advance = new EodEngine.TaskStep() {
            public String name() { return "Advance business date"; }
            public void run(EodEngine.Context c) { dateAdvanced.incrementAndGet(); }
        };
        var store = new InMemoryEodStore();
        var status = new EodEngine(List.of(accrual(booked, Set.of("L42", "L77")), advance), store, 4).run(CTX);
        assertEquals(EodEngine.RunStatus.COMPLETED_WITH_EXCEPTIONS, status);
        assertEquals(98, booked.size());
        assertEquals(2, store.exceptions.size());
        assertEquals(EodEngine.StepStatus.COMPLETED_WITH_EXCEPTIONS, store.steps.get(1));
        assertEquals(1, dateAdvanced.get());
        assertTrue(store.exceptions.stream().allMatch(e -> e.error().contains("bookingStartDate")));
    }

    @Test void systemic_failure_fails_step_and_stops_run() {
        Map<String, Integer> booked = new ConcurrentHashMap<>();
        Set<String> half = new java.util.HashSet<>();
        for (int i = 1; i <= 60; i++) half.add("L" + i);
        EodEngine.ItemStep strict = new EodEngine.ItemStep() {
            final EodEngine.ItemStep inner = accrual(booked, half);
            public String name() { return inner.name(); }
            public List<String> items(EodEngine.Context c) throws Exception { return inner.items(c); }
            public void process(EodEngine.Context c, String i) throws Exception { inner.process(c, i); }
            public double maxFailureRatio() { return 0.05; }
        };
        AtomicInteger advanced = new AtomicInteger();
        EodEngine.TaskStep advance = new EodEngine.TaskStep() {
            public String name() { return "Advance business date"; }
            public void run(EodEngine.Context c) { advanced.incrementAndGet(); }
        };
        var store = new InMemoryEodStore();
        assertEquals(EodEngine.RunStatus.FAILED, new EodEngine(List.of(strict, advance), store, 3).run(CTX));
        assertEquals(0, advanced.get());
        assertEquals(EodEngine.RunStatus.FAILED, store.runStatus);
    }

    @Test void gate_failure_stops_run_and_restart_is_idempotent() {
        Map<String, Integer> booked = new ConcurrentHashMap<>();
        AtomicInteger gateCalls = new AtomicInteger();
        EodEngine.TaskStep gate = new EodEngine.TaskStep() {
            public String name() { return "Trial balance gate"; }
            public void run(EodEngine.Context c) {
                if (gateCalls.incrementAndGet() == 1) throw new IllegalStateException("trial balance out by 0.0100");
            }
        };
        var store = new InMemoryEodStore();
        var engine = new EodEngine(List.of(accrual(booked, Set.of()), gate), store, 4);
        assertEquals(EodEngine.RunStatus.FAILED, engine.run(CTX));
        assertEquals(100, booked.size());
        assertEquals(EodEngine.RunStatus.COMPLETED, engine.run(CTX));            // restart
        assertTrue(booked.values().stream().allMatch(v -> v == 1), "no account booked twice");
        assertEquals(2, gateCalls.get());
    }

    @Test void restart_inside_a_step_skips_checkpointed_items() {
        Map<String, Integer> booked = new ConcurrentHashMap<>();
        var store = new InMemoryEodStore();
        for (int i = 1; i <= 50; i++) store.checkpoint(1, 1, "L" + i);      // crashed half-way last time
        new EodEngine(List.of(accrual(booked, Set.of())), store, 2).run(CTX);
        assertEquals(50, booked.size());
        assertEquals(100, store.counts.get(1)[0]);
    }

    @Test void partitions_cover_all_items_once() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) items.add("A" + i);
        var parts = EodEngine.partition(items, 4);
        assertEquals(4, parts.size());
        assertEquals(10, parts.stream().mapToInt(List::size).sum());
        assertEquals(1, EodEngine.partition(List.of("x"), 8).size());
    }
}
