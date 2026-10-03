package com.corebanking.integration.core;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guard against server-side request forgery for URLs a tenant supplies (webhook endpoints, the generic SMS
 * gateway). Two checks, both needed:
 * <ol>
 *   <li>{@link #checkUrl}: when the URL is registered — {@code https} only, a public DNS name (no IP literal, no
 *       single-label or internal name), no credentials in the URL, an allowed port.</li>
 *   <li>{@link #resolvePublic}: every time a request is about to be sent — the name is resolved and <b>every</b>
 *       address must be public. A name that was public at registration can be re-pointed at an internal address
 *       later, so the registration check alone is not enough.</li>
 * </ol>
 * What this cannot close: the HTTP client resolves the name again when it connects, so a DNS answer that changes
 * between the check and the connection (DNS rebinding) is only bounded by the JVM's DNS cache, which serves the
 * checked answer for {@code networkaddress.cache.ttl} seconds. Keep that at 30 or more and add an egress network
 * policy that denies private ranges (docs/runbooks/integrations.md). Redirects are never followed by the sender.
 */
public final class EndpointGuard {

    /** Ports a tenant endpoint may use. */
    public static final Set<Integer> DEFAULT_PORTS = Set.of(443, 8443);

    private static final Pattern HOST = Pattern.compile(
            "^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z]$");
    private static final List<String> INTERNAL_SUFFIXES = List.of(
            ".localhost", ".local", ".localdomain", ".internal", ".intranet", ".lan", ".home", ".corp", ".svc",
            ".cluster.local", ".home.arpa", ".in-addr.arpa", ".ip6.arpa");

    /** The URL or the address it resolves to may not be called. The message is safe to show to the tenant. */
    public static final class BlockedException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public BlockedException(String message) {
            super(message);
        }
    }

    /** Resolves a host name to its addresses; {@link InetAddress#getAllByName} in production. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private EndpointGuard() {}

    /** Syntax check at registration. Returns the normalised URI. */
    public static URI checkUrl(String url, Set<Integer> allowedPorts) {
        if (url == null || url.isBlank()) throw new BlockedException("the URL is required");
        if (url.length() > 2000) throw new BlockedException("the URL is longer than 2000 characters");
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x20 || c >= 0x7F || c == '\\') throw new BlockedException("the URL contains a character that is not allowed");
        }
        URI u;
        try {
            u = new URI(url).normalize();
        } catch (URISyntaxException e) {
            throw new BlockedException("the URL is not valid");
        }
        if (!"https".equalsIgnoreCase(u.getScheme())) throw new BlockedException("the URL must use https");
        if (u.getRawUserInfo() != null || u.getRawAuthority() == null || u.getRawAuthority().contains("@")) {
            throw new BlockedException("the URL must not contain credentials");
        }
        if (u.getRawFragment() != null) throw new BlockedException("the URL must not contain a fragment");
        String host = u.getHost();
        if (host == null) throw new BlockedException("the URL needs a host name");
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (!HOST.matcher(host).matches()) {
            throw new BlockedException("the host must be a public DNS name (IP addresses and single-label names are not allowed)");
        }
        String dotted = "." + host;
        for (String suffix : INTERNAL_SUFFIXES) {
            if (dotted.endsWith(suffix)) throw new BlockedException("the host is an internal name");
        }
        int port = u.getPort() == -1 ? 443 : u.getPort();
        if (!allowedPorts.contains(port)) throw new BlockedException("port " + port + " is not allowed; use one of " + allowedPorts.stream().sorted().toList());
        return u;
    }

    /**
     * Send-time check: resolves the host and refuses unless every address is public.
     *
     * @return the addresses, for the log
     */
    public static List<InetAddress> resolvePublic(String host, Resolver resolver) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new BlockedException("the host does not resolve");
        }
        if (addresses == null || addresses.length == 0) throw new BlockedException("the host does not resolve");
        for (InetAddress a : addresses) {
            if (!isPublic(a)) throw new BlockedException("the host resolves to an address that is not public");
        }
        return List.of(addresses);
    }

    /** True for a globally routable unicast address. */
    public static boolean isPublic(InetAddress a) {
        if (a == null || a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) return publicV4(b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF);
        if (a instanceof Inet6Address) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            if ((first & 0xFE) == 0xFC) return false;                                   // fc00::/7 unique local
            if (first == 0xFE && (second & 0xC0) == 0x80) return false;                 // fe80::/10 link local
            if (first == 0xFE && (second & 0xC0) == 0xC0) return false;                 // fec0::/10 site local (deprecated)
            if (first == 0xFF) return false;                                            // multicast
            if (first == 0x20 && second == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) return false;   // 2001:db8::/32
            boolean zeroPrefix = true;
            for (int i = 0; i < 10; i++) zeroPrefix &= b[i] == 0;
            // ::/96 (IPv4-compatible) and ::ffff:0:0/96 (IPv4-mapped): judged by the embedded IPv4 address
            if (zeroPrefix && ((b[10] == 0 && b[11] == 0) || ((b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF))) {
                return publicV4(b[12] & 0xFF, b[13] & 0xFF, b[14] & 0xFF);
            }
            // 64:ff9b::/96 (NAT64) and 2002::/16 (6to4) embed an IPv4 address too
            if (first == 0x00 && second == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
                return publicV4(b[12] & 0xFF, b[13] & 0xFF, b[14] & 0xFF);
            }
            if (first == 0x20 && second == 0x02) return publicV4(b[2] & 0xFF, b[3] & 0xFF, b[4] & 0xFF);
            return (first & 0xE0) == 0x20;                                              // 2000::/3 global unicast
        }
        return false;
    }

    private static boolean publicV4(int a, int b, int c) {
        if (a == 0 || a == 10 || a == 127) return false;                    // "this network", private, loopback
        if (a == 100 && b >= 64 && b <= 127) return false;                  // 100.64.0.0/10 carrier-grade NAT
        if (a == 169 && b == 254) return false;                             // link local (cloud metadata lives here)
        if (a == 172 && b >= 16 && b <= 31) return false;                   // private
        if (a == 192 && b == 168) return false;                             // private
        if (a == 192 && b == 0 && (c == 0 || c == 2)) return false;         // IETF protocol assignments, TEST-NET-1
        if (a == 192 && b == 88 && c == 99) return false;                   // 6to4 relay anycast
        if (a == 198 && (b == 18 || b == 19)) return false;                 // benchmarking
        if (a == 198 && b == 51 && c == 100) return false;                  // TEST-NET-2
        if (a == 203 && b == 0 && c == 113) return false;                   // TEST-NET-3
        return a < 224;                                                     // 224/4 multicast, 240/4 reserved, broadcast
    }
}
