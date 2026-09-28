package com.corebanking.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Issues account/customer/voucher numbers from the tenant's series (US-015). The sequence value is taken inside
 * the caller's transaction; a failed save leaves a gap but can never produce a duplicate or collide with another
 * family (prefixes are prefix-free, enforced by the database).
 */
@Service
public class NumberSeriesService {

    private final JdbcTemplate jdbc;

    public NumberSeriesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String next(NumberSeries.Family family) {
        record Series(String prefix, int width, String sequence) {}
        Series s = jdbc.queryForObject("SELECT prefix, width, sequence_name FROM platform.number_series WHERE family = ?",
                (rs, i) -> new Series(rs.getString(1), rs.getInt(2), rs.getString(3)), family.name());
        if (s == null || !s.sequence().matches("platform\\.seq_[a-z_]+")) throw new IllegalStateException("bad series " + family);
        Long value = jdbc.queryForObject("SELECT nextval('" + s.sequence() + "')", Long.class);
        return new NumberSeries(s.prefix(), s.width()).format(value == null ? 0 : value);
    }
}
