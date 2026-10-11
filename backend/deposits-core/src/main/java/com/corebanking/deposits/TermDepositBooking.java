package com.corebanking.deposits;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * One term deposit on a product: whether it may be booked, and its figures. The preview shown before booking, the
 * booking itself and (later) the day-end all take the rate, the maturity date and the interest periods from here,
 * so they cannot disagree.
 */
public final class TermDepositBooking {

    /** Add-on that only a depositor of the rule set's senior-citizen age may be given. */
    public static final String SENIOR_CITIZEN = "SENIOR_CITIZEN";

    /**
     * @param start       the date interest starts: the funding date (for a preview, the business date)
     * @param addOns      codes of the product's add-ons claimed for this deposit
     * @param nominees    number of nominees named
     * @param individual  the first-named depositor is an individual
     * @param dateOfBirth of the first-named depositor; null when not an individual
     * @param panOnFile   a PAN is recorded for the first-named depositor
     */
    public record Request(BigDecimal amount, Tenure tenure, LocalDate start, Set<String> addOns, int nominees,
                          boolean individual, LocalDate dateOfBirth, boolean panOnFile) {
        public Request {
            addOns = addOns == null ? Set.of() : Set.copyOf(addOns);
        }
    }

    /** Tax expected to be deducted from one interest credit, on the interest of this deposit alone. */
    public record TaxLine(int period, LocalDate on, int taxYearStarting, BigDecimal interest, BigDecimal tax) {}

    /**
     * @param maturityValue what is repaid at maturity before tax: the principal, plus all interest when cumulative
     * @param tax           empty when the product does not deduct tax
     */
    public record Quote(DepositRateTable.ResolvedRate rate, LocalDate maturity, TermDeposit.Terms terms,
                        List<TermDeposit.Period> periods, BigDecimal totalInterest, BigDecimal maturityValue,
                        BigDecimal annualisedYield, List<TaxLine> tax, BigDecimal totalTax) {}

    private TermDepositBooking() {}

    /** Why the deposit cannot be booked; empty when it can. */
    public static List<String> problems(DepositRules rules, TermDepositProduct.Settings product, Request r) {
        Set<String> out = new LinkedHashSet<>();
        resolve(rules, product, r, out);
        return new ArrayList<>(out);
    }

    /**
     * The figures of the deposit.
     *
     * @throws DepositRefusal when the product or the rule set does not allow it, with every reason
     */
    public static Quote quote(DepositRules rules, TermDepositProduct.Settings product, Request r) {
        Set<String> problems = new LinkedHashSet<>();
        DepositRateTable.ResolvedRate rate = resolve(rules, product, r, problems);
        if (!problems.isEmpty() || rate == null) throw new DepositRefusal(new ArrayList<>(problems));
        LocalDate maturity = r.tenure().addTo(r.start());
        TermDeposit.Terms terms = new TermDeposit.Terms(r.amount(), rate.ratePercent(), r.start(), maturity,
                product.interestPeriodMonths(), product.cumulative(), product.dayCount(), product.rounding());
        List<TermDeposit.Period> periods = TermDeposit.periods(terms);
        List<TaxLine> tax = product.tdsApplicable() ? projectTax(rules, product, r, periods) : List.of();
        BigDecimal totalTax = tax.stream().map(TaxLine::tax).reduce(product.rounding().apply(BigDecimal.ZERO), BigDecimal::add);
        return new Quote(rate, maturity, terms, periods, TermDeposit.totalInterest(terms), TermDeposit.maturityValue(terms),
                TermDeposit.annualisedYield(terms), tax, totalTax);
    }

    private static DepositRateTable.ResolvedRate resolve(DepositRules rules, TermDepositProduct.Settings p, Request r, Set<String> out) {
        boolean ok = true;
        if (r.amount() == null || r.amount().signum() <= 0) {
            out.add("the deposit amount must be above zero");
            ok = false;
        } else {
            if (r.amount().stripTrailingZeros().scale() > 2) {
                out.add("the deposit amount has at most two decimal places");
                ok = false;
            }
            if (r.amount().compareTo(p.minAmount()) < 0) {
                out.add("the amount " + TermDepositProduct.plain(r.amount()) + " is below the product's smallest deposit of "
                        + TermDepositProduct.plain(p.minAmount()));
                ok = false;
            }
            if (p.maxAmount() != null && r.amount().compareTo(p.maxAmount()) > 0) {
                out.add("the amount " + TermDepositProduct.plain(r.amount()) + " is above the product's largest deposit of "
                        + TermDepositProduct.plain(p.maxAmount()));
                ok = false;
            }
        }
        boolean dated = true;
        if (r.start() == null) {
            out.add("the start date is required");
            dated = false;
        }
        if (r.tenure() == null || r.tenure().compareTo(Tenure.ofDays(0)) == 0) {
            out.add("the tenure is required");
            dated = false;
        }
        if (!dated) return null;
        LocalDate maturity = r.tenure().addTo(r.start());
        if (!p.minTenure().reachedBy(r.start(), maturity)) {
            out.add("the tenure " + r.tenure() + " is shorter than the product's shortest tenure of " + p.minTenure());
            ok = false;
        }
        if (maturity.isAfter(p.maxTenure().addTo(r.start()))) {
            out.add("the tenure " + r.tenure() + " is longer than the product's longest tenure of " + p.maxTenure());
            ok = false;
        }
        Set<String> known = new TreeSet<>();
        for (DepositRateTable.AddOn a : p.addOns()) known.add(a.code());
        for (String code : new TreeSet<>(r.addOns())) {
            if (!known.contains(code)) {
                out.add("the product has no add-on " + code);
                ok = false;
            } else if (SENIOR_CITIZEN.equals(code)) {
                int age = rules.required(DepositRules.SENIOR_CITIZEN_AGE).intValueExact();
                if (!r.individual() || r.dateOfBirth() == null || Period.between(r.dateOfBirth(), r.start()).getYears() < age) {
                    out.add("the senior citizen add-on is for an individual aged " + age + " or more on the start date");
                    ok = false;
                }
            }
        }
        if (!ok) return null;
        DepositRateTable.ResolvedRate rate;
        try {
            rate = p.rateTable().resolve(r.amount(), r.start(), maturity, r.addOns());
        } catch (IllegalArgumentException e) {
            out.add(e.getMessage());
            return null;
        }
        if (rate.ratePercent().signum() < 0) {
            out.add("the rate with the add-ons claimed is below zero");
            return null;
        }
        List<DepositRules.Violation> broken = rules.checkTermDeposit(r.start(), maturity, rate.ratePercent(),
                p.interestPeriodMonths(), r.nominees());
        for (DepositRules.Violation v : broken) out.add(v.message());
        return broken.isEmpty() ? rate : null;
    }

    /**
     * Tax at each interest credit, taking the interest of this deposit alone and adding it up by tax year. The
     * depositor's other deposits and a declaration on file (Form 121) are not known here: the tax ledger of the
     * day-end has them, and what is deducted then can differ.
     */
    private static List<TaxLine> projectTax(DepositRules rules, TermDepositProduct.Settings p, Request r, List<TermDeposit.Period> periods) {
        List<TaxLine> out = new ArrayList<>();
        int age = rules.whole(DepositRules.SENIOR_CITIZEN_AGE).orElse(Integer.MAX_VALUE);
        int year = Integer.MIN_VALUE;
        TdsOnInterest.YearToDate ytd = TdsOnInterest.YearToDate.NONE;
        for (TermDeposit.Period period : periods) {
            int y = TdsOnInterest.taxYearStarting(period.to());
            if (y != year) {
                year = y;
                ytd = TdsOnInterest.YearToDate.NONE;
            }
            boolean senior = r.individual() && r.dateOfBirth() != null && TdsOnInterest.seniorInTaxYear(r.dateOfBirth(), period.to(), age);
            TdsOnInterest.Payee payee = new TdsOnInterest.Payee(senior, r.panOnFile(), false, false);
            BigDecimal tax = TdsOnInterest.deduct(rules, payee, ytd, period.interest(), p.rounding());
            out.add(new TaxLine(period.number(), period.to(), y, period.interest(), tax));
            ytd = new TdsOnInterest.YearToDate(ytd.interest().add(period.interest()), ytd.taxDeducted().add(tax));
        }
        return List.copyOf(out);
    }
}
