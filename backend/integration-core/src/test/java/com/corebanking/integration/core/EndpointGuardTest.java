package com.corebanking.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SSRF guard for tenant-supplied URLs (US-121). Addresses are literals: no DNS is used in these tests. */
class EndpointGuardTest {

    static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void accepts_a_public_https_endpoint() {
        assertEquals("https://hooks.los.example.com/corebanking/events?x=1",
                EndpointGuard.checkUrl("https://hooks.los.example.com/corebanking/events?x=1", EndpointGuard.DEFAULT_PORTS).toString());
        EndpointGuard.checkUrl("https://hooks.example.com:8443/in", EndpointGuard.DEFAULT_PORTS);
    }

    @Test
    void refuses_anything_but_https() {
        for (String bad : List.of("http://hooks.example.com/in", "ftp://hooks.example.com/in", "file:///etc/passwd",
                "gopher://hooks.example.com/", "//hooks.example.com/in", "hooks.example.com/in", "javascript:alert(1)")) {
            assertThrows(EndpointGuard.BlockedException.class, () -> EndpointGuard.checkUrl(bad, EndpointGuard.DEFAULT_PORTS), bad);
        }
    }

    @Test
    void refuses_ip_literals_in_every_spelling_and_internal_names() {
        for (String bad : List.of("https://127.0.0.1/in", "https://10.0.0.5/in", "https://169.254.169.254/latest/meta-data",
                "https://[::1]/in", "https://[fd00::1]/in", "https://2130706433/in", "https://0x7f000001/in", "https://0177.0.0.1/in",
                "https://127.1/in", "https://localhost/in", "https://intranet/in", "https://keycloak.local/in",
                "https://db.internal/in", "https://api.svc.cluster.local/in", "https://app.localhost/in", "https://8.8.8.8/in")) {
            assertThrows(EndpointGuard.BlockedException.class, () -> EndpointGuard.checkUrl(bad, EndpointGuard.DEFAULT_PORTS), bad);
        }
    }

    @Test
    void refuses_credentials_fragments_odd_ports_and_odd_characters() {
        for (String bad : List.of("https://user:pw@hooks.example.com/in", "https://hooks.example.com@evil.example.org/in",
                "https://hooks.example.com/in#frag", "https://hooks.example.com:22/in", "https://hooks.example.com:6379/in",
                "https://hooks.example.com/in\r\nHost: evil", "https://hooks.example.com/a b", "https://hooks.example.com\\@evil.example.org/",
                "https://h\u00f6oks.example.com/in", "", "   ", "https://" + "a".repeat(2000) + ".example.com/")) {
            assertThrows(EndpointGuard.BlockedException.class, () -> EndpointGuard.checkUrl(bad, EndpointGuard.DEFAULT_PORTS), bad);
        }
    }

    @Test
    void private_loopback_link_local_and_reserved_addresses_are_not_public() {
        for (String a : List.of("127.0.0.1", "127.8.9.1", "10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "100.127.255.254", "0.0.0.0", "0.1.2.3", "192.0.0.8", "192.0.2.1", "198.18.0.1", "198.19.255.1",
                "198.51.100.7", "203.0.113.9", "224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255",
                "::1", "::", "fe80::1", "fec0::1", "fc00::1", "fd12:3456:789a::1", "ff02::1", "2001:db8::1",
                "::ffff:10.0.0.1", "::ffff:127.0.0.1", "64:ff9b::a00:1", "2002:a00:1::1", "2002:7f00:1::1", "::10.0.0.1")) {
            assertFalse(EndpointGuard.isPublic(ip(a)), a);
        }
    }

    @Test
    void ordinary_internet_addresses_are_public() {
        for (String a : List.of("8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "192.0.1.1",
                "198.17.255.255", "198.20.0.1", "223.255.255.254", "2606:4700:4700::1111", "2001:4860:4860::8888",
                "64:ff9b::808:808", "2002:808:808::1")) {
            assertTrue(EndpointGuard.isPublic(ip(a)), a);
        }
    }

    @Test
    void send_time_check_refuses_a_name_that_now_points_inside() {
        // registered while public, re-pointed at the metadata address later: caught when resolved before sending
        EndpointGuard.Resolver rebound = host -> new InetAddress[] {ip("169.254.169.254")};
        assertThrows(EndpointGuard.BlockedException.class, () -> EndpointGuard.resolvePublic("hooks.example.com", rebound));
        // one public and one private answer: refused, the client could pick either
        EndpointGuard.Resolver mixed = host -> new InetAddress[] {ip("8.8.8.8"), ip("10.0.0.1")};
        assertThrows(EndpointGuard.BlockedException.class, () -> EndpointGuard.resolvePublic("hooks.example.com", mixed));
        EndpointGuard.Resolver good = host -> new InetAddress[] {ip("8.8.8.8"), ip("2606:4700:4700::1111")};
        assertEquals(2, EndpointGuard.resolvePublic("hooks.example.com", good).size());
    }

    @Test
    void a_name_that_does_not_resolve_is_refused() {
        EndpointGuard.Resolver none = host -> {
            throw new UnknownHostException(host);
        };
        assertThrows(EndpointGuard.BlockedException.class, () -> EndpointGuard.resolvePublic("gone.example.com", none));
        EndpointGuard.Resolver empty = host -> new InetAddress[0];
        assertThrows(EndpointGuard.BlockedException.class, () -> EndpointGuard.resolvePublic("gone.example.com", empty));
    }
}
