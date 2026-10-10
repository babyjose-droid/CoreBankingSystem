package com.corebanking.deposits;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A deposit rate card: the full annual rate for an amount step and a tenure step, plus named add-ons (for example
 * SENIOR_CITIZEN +0.50). Each step is given by where it starts ("from Rs 0", "from Rs 5,00,000"; "from 12 months",
 * "from 24 months") and runs until the next one, so the card has no gaps between steps by construction.
 *
 * <p>The rate is what the cell says. It is never a base rate plus a slab rate: the reference system adds the two
 * and showed 14.5% from 7% and 7.5% (docs/golden-values.md G-10, decision D-16).
 */
public final class DepositRateTable {

    public record Cell(BigDecimal fromAmount, Tenure fromTenure, BigDecimal ratePercent) {
        public Cell {
            if (fromAmount.signum() < 0) throw new IllegalArgumentException("fromAmount cannot be negative");
            if (ratePercent.signum() < 0) throw new IllegalArgumentException("a deposit rate cannot be negative");
        }
    }

    public record AddOn(String code, BigDecimal percent) {}

    /** A looked-up rate with its parts, stored on the deposit so the figure can be explained later. */
    public record ResolvedRate(BigDecimal cardRatePercent, BigDecimal fromAmount, Tenure fromTenure,
                               List<AddOn> addOns, BigDecimal ratePercent) {}

    private final TreeSet<BigDecimal> amountSteps = new TreeSet<>();
    private final TreeSet<Tenure> tenureSteps = new TreeSet<>();
    private final Map<String, BigDecimal> rates = new TreeMap<>();
    private final Map<String, AddOn> addOns = new TreeMap<>();

    public DepositRateTable(Collection<Cell> cells, Collection<AddOn> addOns) {
        for (Cell c : cells) {
            // Two tenures that sort alike ("31 days" and "1 month") would be one step with two rates.
            Tenure step = tenureSteps.ceiling(c.fromTenure());
            if (step != null && step.compareTo(c.fromTenure()) == 0 && !step.equals(c.fromTenure())) {
                throw new IllegalArgumentException("tenure steps " + step + " and " + c.fromTenure() + " cannot both be used");
            }
            if (rates.put(key(c.fromAmount(), c.fromTenure()), c.ratePercent()) != null) {
                throw new IllegalArgumentException("two rates for amount from " + c.fromAmount().toPlainString()
                        + " and tenure from " + c.fromTenure());
            }
            amountSteps.add(c.fromAmount().stripTrailingZeros());
            tenureSteps.add(c.fromTenure());
        }
        for (AddOn a : addOns) {
            if (this.addOns.put(a.code(), a) != null) throw new IllegalArgumentException("add-on " + a.code() + " is listed twice");
        }
    }

    private static String key(BigDecimal amount, Tenure tenure) {
        return amount.stripTrailingZeros().toPlainString() + "|" + tenure.months() + "|" + tenure.days();
    }

    /**
     * What stops a product with these limits from being offered: a product may be approved only when every amount
     * and tenure it allows has a rate (the reference system refused bookings for tenures its product allowed).
     */
    public List<String> coverageProblems(BigDecimal minAmount, Tenure minTenure) {
        List<String> out = new ArrayList<>();
        if (rates.isEmpty()) {
            out.add("the rate table has no rates");
            return out;
        }
        if (amountSteps.first().compareTo(minAmount) > 0) {
            out.add("no rate for amounts below " + amountSteps.first().toPlainString()
                    + "; the product accepts deposits from " + minAmount.stripTrailingZeros().toPlainString());
        }
        if (tenureSteps.first().compareTo(minTenure) > 0) {
            out.add("no rate for tenures shorter than " + tenureSteps.first() + "; the product accepts " + minTenure);
        }
        for (BigDecimal a : amountSteps) {
            for (Tenure t : tenureSteps) {
                if (!rates.containsKey(key(a, t))) {
                    out.add("no rate for amount from " + a.toPlainString() + " and tenure from " + t);
                }
            }
        }
        return out;
    }

    /**
     * The rate for a deposit of {@code amount} running from {@code start} to {@code maturity}, with the named
     * add-ons. The tenure step is the longest one the deposit has run for on its real dates.
     */
    public ResolvedRate resolve(BigDecimal amount, LocalDate start, LocalDate maturity, Collection<String> addOnCodes) {
        BigDecimal fromAmount = amountSteps.floor(amount.stripTrailingZeros());
        Tenure fromTenure = null;
        for (Tenure t : tenureSteps) {
            if (t.reachedBy(start, maturity)) fromTenure = t;
        }
        BigDecimal card = fromAmount == null || fromTenure == null ? null : rates.get(key(fromAmount, fromTenure));
        if (card == null) {
            throw new IllegalArgumentException("no deposit rate for " + amount.toPlainString() + " from " + start + " to " + maturity);
        }
        List<AddOn> applied = new ArrayList<>();
        BigDecimal total = card;
        for (String code : new TreeSet<>(addOnCodes)) {
            AddOn a = addOns.get(code);
            if (a == null) throw new IllegalArgumentException("the rate table has no add-on " + code);
            applied.add(a);
            total = total.add(a.percent());
        }
        return new ResolvedRate(card, fromAmount, fromTenure, List.copyOf(applied), total);
    }

    /** The lowest card rate, add-ons excluded (the "minimum rate" of NBFC Directions para 40). */
    public BigDecimal minimumRate() {
        return rates.values().stream().min(BigDecimal::compareTo)
                .orElseThrow(() -> new IllegalStateException("the rate table has no rates"));
    }
}
