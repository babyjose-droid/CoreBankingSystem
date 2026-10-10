package com.corebanking.platform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Which addresses are the lender's own staff: those on a domain listed in the tenant property
 * {@code mail.internal-domains} (comma separated, e.g. {@code example-nbfc.in}). Files (reports, which can hold
 * personal data) are e-mailed only to these; with no domain listed nothing is attached to anyone.
 */
public final class InternalRecipients {

    public static final String PROPERTY = "mail.internal-domains";

    private InternalRecipients() {}

    public record Split(List<String> internal, int skipped) {}

    public static Set<String> domains(JdbcTemplate jdbc) {
        List<String> v = jdbc.queryForList("SELECT value FROM platform.system_property WHERE key = ?", String.class, PROPERTY);
        if (v.isEmpty()) return Set.of();
        return Arrays.stream(v.get(0).split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The recipients on an internal domain (exact domain match, case-insensitive), and how many were left out. */
    public static Split split(List<String> recipients, Set<String> domains) {
        List<String> in = new ArrayList<>();
        int out = 0;
        for (String raw : recipients) {
            String r = raw == null ? "" : raw.trim();
            int at = r.lastIndexOf('@');
            String domain = at < 0 ? "" : r.substring(at + 1).toLowerCase(Locale.ROOT);
            if (domains.contains(domain)) in.add(r); else out++;
        }
        return new Split(List.copyOf(in), out);
    }

    /** The domain of an address, lower case ("" when there is no '@'). */
    public static String domainOf(String address) {
        String r = address == null ? "" : address.trim();
        int at = r.lastIndexOf('@');
        return at < 0 ? "" : r.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * The addresses whose domain is not internal, as their distinct domains (never the full address: it can be
     * personal data), in first-seen order. Used to refuse a schedule that would e-mail files outside the lender.
     */
    public static List<String> externalDomains(List<String> recipients, Set<String> domains) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String r : recipients) {
            String d = domainOf(r);
            if (!domains.contains(d)) out.add(d);
        }
        return List.copyOf(out);
    }
}
