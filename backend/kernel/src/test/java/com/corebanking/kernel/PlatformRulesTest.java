package com.corebanking.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.kernel.KeycloakAdmin.HttpCall;
import com.corebanking.kernel.KeycloakAdmin.Session;
import com.corebanking.kernel.PostingWindow.Booking;
import com.corebanking.kernel.PostingWindow.DayStatus;
import com.corebanking.kernel.PostingWindow.Decision;
import com.corebanking.kernel.PostingWindow.Kind;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Posting cut-off (US-111), identity-provider admin calls (US-027), usage metering (US-004), report periods (US-113). */
class PlatformRulesTest {

    // ------------------------------------------------------------------------------------------------ posting cut-off
    private static final LocalDate FRI = LocalDate.of(2026, 10, 9);
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    @Test
    void before_the_cut_off_everything_posts_at_once() {
        for (Kind k : Kind.values()) {
            assertEquals(Decision.POST_NOW, PostingWindow.decide(DayStatus.OPEN, k, false), k.name());
            assertEquals(Decision.POST_NOW, PostingWindow.decide(DayStatus.OPEN, k, true), k.name());
        }
    }

    @Test
    void after_the_cut_off_only_customer_facing_receipts_are_accepted_for_the_next_date() {
        for (DayStatus s : List.of(DayStatus.EOD_RUNNING, DayStatus.EOD_FAILED)) {
            assertEquals(Decision.DEFER_TO_NEXT_BUSINESS_DATE, PostingWindow.decide(s, Kind.REPAYMENT, true), s.name());
            assertEquals(Decision.REFUSE, PostingWindow.decide(s, Kind.REPAYMENT, false), "staff back-office repayment, " + s);
            for (Kind k : Kind.values()) {
                if (k == Kind.REPAYMENT) continue;
                assertEquals(Decision.REFUSE, PostingWindow.decide(s, k, true), k + " " + s);
                assertEquals(Decision.REFUSE, PostingWindow.decide(s, k, false), k + " " + s);
            }
        }
        assertEquals(Decision.REFUSE, PostingWindow.decide(DayStatus.CLOSED, Kind.REPAYMENT, true));
    }

    @Test
    void value_date_and_posting_date_are_explicit() {
        // open day: booked today; the value date may be today or earlier, never later
        assertEquals(new Booking(FRI, FRI), PostingWindow.booking(Decision.POST_NOW, FRI, MON, null));
        assertEquals(new Booking(FRI, FRI.minusDays(2)), PostingWindow.booking(Decision.POST_NOW, FRI, MON, FRI.minusDays(2)));
        assertThrows(IllegalArgumentException.class, () -> PostingWindow.booking(Decision.POST_NOW, FRI, MON, FRI.plusDays(1)));
        // after the cut-off: booked and valued on the next business date (here across a weekend)
        assertEquals(new Booking(MON, MON), PostingWindow.booking(Decision.DEFER_TO_NEXT_BUSINESS_DATE, FRI, MON, null));
        assertEquals(new Booking(MON, MON), PostingWindow.booking(Decision.DEFER_TO_NEXT_BUSINESS_DATE, FRI, MON, MON));
        // the date being closed cannot be asked for as value date
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PostingWindow.booking(Decision.DEFER_TO_NEXT_BUSINESS_DATE, FRI, MON, FRI));
        assertTrue(e.getMessage().contains("2026-10-12"));
        assertThrows(IllegalArgumentException.class, () -> PostingWindow.booking(Decision.DEFER_TO_NEXT_BUSINESS_DATE, FRI, FRI, null));
        assertThrows(IllegalArgumentException.class, () -> PostingWindow.booking(Decision.REFUSE, FRI, MON, null));
    }

    // ------------------------------------------------------------------------------------------------ JSON reader
    @Test
    void json_values_are_read() {
        Object v = MiniJson.parse(" {\"a\": [1, -2.50, 1e3, true, false, null], \"b\": {\"c\": \"x\\n\\\"y\\\" \\u0041\\/\"}, \"d\": {}, \"e\": []} ");
        Map<?, ?> o = MiniJson.object(v, "test");
        assertEquals(List.of("a", "b", "d", "e"), List.copyOf(o.keySet()));
        List<?> a = MiniJson.array(o.get("a"), "a");
        assertEquals(new BigDecimal("1"), a.get(0));
        assertEquals(new BigDecimal("-2.50"), a.get(1));
        assertEquals(0, new BigDecimal("1000").compareTo((BigDecimal) a.get(2)));
        assertEquals(Boolean.TRUE, a.get(3));
        assertEquals(Boolean.FALSE, a.get(4));
        assertNull(a.get(5));
        assertEquals("x\n\"y\" A/", MiniJson.string(MiniJson.object(o.get("b"), "b"), "c"));
        assertEquals("plain", MiniJson.parse("\"plain\""));
        assertNull(MiniJson.string(o, "a"));
        assertNull(MiniJson.whole(o, "missing"));
        assertNull(MiniJson.whole(MiniJson.object(MiniJson.parse("{\"n\": 1.5}"), "t"), "n"));
    }

    @Test
    void bad_json_is_refused() {
        for (String bad : List.of("", "{", "{\"a\":}", "{\"a\":1,}", "[1,]", "{a:1}", "\"unterminated", "01", "1.", "tru", "nul", "{} x", "[1 2]",
                "\"bad \\x escape\"", "\"\\u12\"", "\"tab\there\"", "{\"a\" 1}", "'single'", "-", "[" .repeat(40) + "]".repeat(40))) {
            assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(null));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object(MiniJson.parse("[]"), "x"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.array(MiniJson.parse("{}"), "x"));
    }

    // ------------------------------------------------------------------------------------------------ Keycloak admin
    private static final String BASE = "http://keycloak:8081";
    private static final String USER_ID = "8f1c2d3e-0000-4000-8000-00000000c1a0";

    @Test
    void token_request_is_a_client_credentials_form_post_to_the_tenant_realm() {
        HttpCall c = KeycloakAdmin.tokenRequest(BASE + "/", "claude-test", "corebanking-admin", "CLAUDE-TEST s3cr&t=/+ value");
        assertEquals("POST", c.method());
        assertEquals("http://keycloak:8081/realms/claude-test/protocol/openid-connect/token", c.url());
        assertEquals("application/x-www-form-urlencoded", c.headers().get("Content-Type"));
        assertEquals("grant_type=client_credentials&client_id=corebanking-admin&client_secret=CLAUDE-TEST+s3cr%26t%3D%2F%2B+value", c.body());
        // the secret never appears when the call is logged
        assertEquals("POST http://keycloak:8081/realms/claude-test/protocol/openid-connect/token", c.toString());
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.tokenRequest(BASE, "claude-test", "corebanking-admin", " "));
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.tokenRequest(BASE, "claude-test", null, "x"));
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.tokenRequest("", "claude-test", "a", "x"));
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.tokenRequest("ftp://kc", "claude-test", "a", "x"));
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.tokenRequest(BASE, "../master", "a", "x"));
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.tokenRequest(BASE, "Claude Test", "a", "x"));
    }

    @Test
    void admin_calls_are_built_for_the_realm_with_the_bearer_token() {
        HttpCall sessions = KeycloakAdmin.userSessions(BASE, "claude-test", USER_ID, "tok.en-1");
        assertEquals("GET", sessions.method());
        assertEquals(BASE + "/admin/realms/claude-test/users/" + USER_ID + "/sessions", sessions.url());
        assertEquals("Bearer tok.en-1", sessions.headers().get("Authorization"));
        assertNull(sessions.body());
        assertFalse(sessions.toString().contains("tok.en-1"));
        // a federated user id with odd characters stays one path segment
        assertEquals(BASE + "/admin/realms/claude-test/users/f%3Aldap%3Ajo%2F..%2Fx%20y/sessions",
                KeycloakAdmin.userSessions(BASE, "claude-test", "f:ldap:jo/../x y", "t").url());
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.userSessions(BASE, "claude-test", "..", "t"));
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.userSessions(BASE, "claude-test", "", "t"));

        HttpCall find = KeycloakAdmin.findUser(BASE, "claude-test", "maker.one@claude-test.example", "t");
        assertEquals(BASE + "/admin/realms/claude-test/users?username=maker.one%40claude-test.example&exact=true", find.url());
        for (String bad : List.of("a b", "a/b", "a?b", "a&exact=false", "a#b", "a%00", "", "a\\b")) {
            assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.findUser(BASE, "claude-test", bad, "t"), bad);
        }

        HttpCall delete = KeycloakAdmin.deleteSession(BASE, "claude-test", "4c9e0b1a-1111-4222-8333-444455556666", "t");
        assertEquals("DELETE", delete.method());
        assertEquals(BASE + "/admin/realms/claude-test/sessions/4c9e0b1a-1111-4222-8333-444455556666", delete.url());
        for (String bad : List.of("../users/x", "a/b", "short", "x".repeat(81), "id?x=1", "")) {
            assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.deleteSession(BASE, "claude-test", bad, "t"), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.deleteSession(BASE, "claude-test", "4c9e0b1a-1111", null));
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.userSessions(BASE, "claude-test", USER_ID, "two words"));
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.userSessions(BASE, "claude-test", USER_ID, "line\nbreak"));
    }

    @Test
    void token_reply_is_parsed() {
        KeycloakAdmin.AccessToken t = KeycloakAdmin.parseToken(
                "{\"access_token\":\"CLAUDE-TEST.eyJ.sig\",\"expires_in\":300,\"refresh_expires_in\":0,\"token_type\":\"Bearer\",\"not-before-policy\":0,\"scope\":\"profile\"}");
        assertEquals("CLAUDE-TEST.eyJ.sig", t.token());
        assertEquals(300, t.expiresInSeconds());
        assertFalse(t.toString().contains("eyJ"), "a token is never printed");
        assertEquals(0, KeycloakAdmin.parseToken("{\"access_token\":\"x\"}").expiresInSeconds());
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.parseToken("{\"error\":\"unauthorized_client\"}"));
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.parseToken("[]"));
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.parseToken("<html>502</html>"));
    }

    @Test
    void sessions_reply_is_parsed() {
        // Shape of Keycloak's UserSessionRepresentation.
        String json = """
                [{"id":"4c9e0b1a-1111-4222-8333-444455556666","username":"maker1","userId":"8f1c2d3e-0000-4000-8000-00000000c1a0",
                  "ipAddress":"10.1.2.3","start":1790917200000,"lastAccess":1790919000000,"rememberMe":false,
                  "clients":{"c7d1":"console","a001":"account"},"transientUser":false},
                 {"id":"5d0f1c2b-1111-4222-8333-444455556666","username":"maker1","userId":"8f1c2d3e-0000-4000-8000-00000000c1a0",
                  "ipAddress":"10.9.9.9","start":1790910000000,"lastAccess":0,"clients":{}},
                 {"username":"no-id"}, "not an object"]
                """;
        List<Session> s = KeycloakAdmin.parseSessions(json);
        assertEquals(2, s.size());
        assertEquals("4c9e0b1a-1111-4222-8333-444455556666", s.get(0).id());
        assertEquals("maker1", s.get(0).username());
        assertEquals("10.1.2.3", s.get(0).ipAddress());
        assertEquals(Instant.ofEpochMilli(1790917200000L), s.get(0).started());
        assertEquals(Instant.ofEpochMilli(1790919000000L), s.get(0).lastAccess());
        assertEquals(List.of("account", "console"), s.get(0).clients());
        assertNull(s.get(1).lastAccess());
        assertTrue(s.get(1).clients().isEmpty());
        assertTrue(KeycloakAdmin.parseSessions("[]").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> KeycloakAdmin.parseSessions("{\"error\":\"HTTP 403 Forbidden\"}"));
    }

    @Test
    void user_lookup_takes_only_the_exact_user_name() {
        String json = "[{\"id\":\"id-other\",\"username\":\"maker10\"},{\"id\":\"id-maker1\",\"username\":\"Maker1\",\"enabled\":true}]";
        assertEquals("id-maker1", KeycloakAdmin.parseUserId(json, "maker1"));
        assertNull(KeycloakAdmin.parseUserId(json, "maker"));
        assertNull(KeycloakAdmin.parseUserId("[]", "maker1"));
        assertNull(KeycloakAdmin.parseUserId("[{\"username\":\"maker1\"}]", "maker1"));
    }

    // ------------------------------------------------------------------------------------------------ usage metering
    @Test
    void api_calls_are_counted_in_memory_and_drained_once() {
        UsageMeter meter = new UsageMeter();
        LocalDate d1 = LocalDate.of(2026, 10, 2);
        for (int i = 0; i < 5; i++) meter.count("claude-test-a", d1);
        meter.count("claude-test-b", d1);
        meter.count("claude-test-a", d1.plusDays(1));
        assertEquals(7, meter.pending());
        Map<UsageMeter.Key, Long> drained = meter.drain();
        assertEquals(3, drained.size());
        assertEquals(Long.valueOf(5), drained.get(new UsageMeter.Key("claude-test-a", d1)));
        assertEquals(Long.valueOf(1), drained.get(new UsageMeter.Key("claude-test-b", d1)));
        assertEquals(0, meter.pending());
        assertTrue(meter.drain().isEmpty(), "a count is handed out once");
        // a failed flush puts the counts back
        drained.forEach(meter::add);
        assertEquals(7, meter.pending());
        meter.add(new UsageMeter.Key("claude-test-a", d1), 0);
        meter.add(new UsageMeter.Key(null, d1), 3);
        assertEquals(7, meter.pending());
    }

    @Test
    void no_call_is_lost_between_counting_threads_and_the_flush() throws Exception {
        UsageMeter meter = new UsageMeter();
        LocalDate day = LocalDate.of(2026, 10, 2);
        int threads = 8;
        int perThread = 20_000;
        long drained = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                String tenant = "claude-test-" + (t % 3);
                pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) meter.count(tenant, day);
                });
            }
            pool.shutdown();
            while (!pool.awaitTermination(1, TimeUnit.MILLISECONDS)) {
                drained += meter.drain().values().stream().mapToLong(Long::longValue).sum();
            }
        }
        drained += meter.drain().values().stream().mapToLong(Long::longValue).sum();
        assertEquals((long) threads * perThread, drained);
    }

    @Test
    void usage_query_ranges_and_months() {
        LocalDate today = LocalDate.of(2026, 10, 2);
        assertEquals(new UsageMeter.Range(LocalDate.of(2026, 9, 2), today), UsageMeter.range(null, null, today));
        assertEquals(new UsageMeter.Range(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), UsageMeter.range("2026-09-01", "2026-09-30", today));
        assertEquals(new UsageMeter.Range(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)), UsageMeter.range(" ", "2026-08-31", today));
        assertThrows(IllegalArgumentException.class, () -> UsageMeter.range("2026-10-02", "2026-10-01", today));
        assertThrows(IllegalArgumentException.class, () -> UsageMeter.range("2025-01-01", "2026-10-01", today));
        assertThrows(IllegalArgumentException.class, () -> UsageMeter.range("01-09-2026", null, today));
        assertEquals(YearMonth.of(2026, 9), UsageMeter.month(null, today));
        assertEquals(YearMonth.of(2026, 3), UsageMeter.month("2026-03", today));
        assertEquals(YearMonth.of(2025, 12), UsageMeter.month("", LocalDate.of(2026, 1, 15)));
        assertThrows(IllegalArgumentException.class, () -> UsageMeter.month("2026-13", today));
        assertThrows(IllegalArgumentException.class, () -> UsageMeter.month("March", today));
    }

    // ------------------------------------------------------------------------------------------------ report periods
    @Test
    void scheduled_report_periods_follow_the_business_date() {
        LocalDate bd = LocalDate.of(2026, 3, 1);
        Set<String> range = Set.of("from", "to");
        assertEquals(Map.of("from", "2026-02-01", "to", "2026-02-28"), ReportPeriod.resolve("PREVIOUS_MONTH", bd, range));
        assertEquals(Map.of("from", "2026-03-01", "to", "2026-03-01"), ReportPeriod.resolve("MONTH_TO_DATE", bd, range));
        assertEquals(Map.of("from", "2026-02-28", "to", "2026-02-28"), ReportPeriod.resolve("PREVIOUS_DAY", bd, range));
        assertEquals(Map.of("from", "2026-03-01", "to", "2026-03-01"), ReportPeriod.resolve(null, bd, range));
        // a report with an as-at date gets the end of the period; one without date parameters gets nothing
        assertEquals(Map.of("asOf", "2026-02-28"), ReportPeriod.resolve("PREVIOUS_MONTH", bd, Set.of("asOf", "branch")));
        assertTrue(ReportPeriod.resolve("PREVIOUS_MONTH", bd, Set.of()).isEmpty());
        assertEquals(Map.of("from", "2025-12-01", "to", "2025-12-31"), ReportPeriod.resolve("PREVIOUS_MONTH", LocalDate.of(2026, 1, 31), range));
        assertThrows(IllegalArgumentException.class, () -> ReportPeriod.resolve("LAST_YEAR", bd, range));
    }
}
