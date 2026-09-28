package com.corebanking.kernel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Effective-dated tax rates (US-017). The rate is chosen by the transaction's value date, never "today", so a
 * back-dated fee uses the rate that applied then. Periods for one code may not overlap.
 */
public final class TaxRateTable {

    public record TaxRate(String code, String taxType, BigDecimal ratePercent, LocalDate from, LocalDate to) {
        public TaxRate {
            if (to != null && to.isBefore(from)) throw new IllegalArgumentException("effectiveTo before effectiveFrom");
            if (ratePercent.signum() < 0 || ratePercent.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("rate must be 0..100");
            }
        }

        boolean covers(LocalDate d) {
            return !d.isBefore(from) && (to == null || !d.isAfter(to));
        }

        boolean overlaps(TaxRate o) {
            LocalDate thisEnd = to == null ? LocalDate.MAX : to;
            LocalDate otherEnd = o.to == null ? LocalDate.MAX : o.to;
            return !from.isAfter(otherEnd) && !o.from.isAfter(thisEnd);
        }
    }

    private final List<TaxRate> rates;

    public TaxRateTable(List<TaxRate> rates) {
        List<TaxRate> sorted = new ArrayList<>(rates);
        sorted.sort(Comparator.comparing(TaxRate::code).thenComparing(TaxRate::from));
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                TaxRate a = sorted.get(i), b = sorted.get(j);
                if (a.code().equals(b.code()) && a.overlaps(b)) {
                    throw new IllegalArgumentException("overlapping periods for tax code " + a.code());
                }
            }
        }
        this.rates = List.copyOf(sorted);
    }

    public Optional<TaxRate> find(String code, LocalDate valueDate) {
        return rates.stream().filter(r -> r.code().equals(code) && r.covers(valueDate)).findFirst();
    }

    public BigDecimal rate(String code, LocalDate valueDate) {
        return find(code, valueDate).map(TaxRate::ratePercent)
                .orElseThrow(() -> new IllegalArgumentException("no " + code + " rate effective on " + valueDate));
    }
}
