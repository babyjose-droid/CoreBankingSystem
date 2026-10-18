package com.corebanking.deposits;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Charges on savings and current accounts (P4-4), from the product's schedule of charges, with GST on top. Pure.
 *
 * <ul>
 *   <li><b>Minimum balance.</b> The month's average of the daily closing balances is compared with the product's
 *       minimum. The charge is a percentage of the shortfall, capped, so it is in proportion to the shortfall (RBI
 *       instructions of 20-Nov-2014 on penal charges for not keeping a minimum balance, from a summary; unverified,
 *       open item P4-20). The service charges only for a month whose shortfall follows a month that was short and
 *       was notified, so the depositor has a month to restore the balance.</li>
 *   <li><b>Closure.</b> A fixed charge when an account is closed after a free period from opening (no charge
 *       within 14 days by convention) and before a limit (none after it).</li>
 *   <li><b>Service charges.</b> Named fixed charges (a duplicate statement, say) levied by staff.</li>
 * </ul>
 * A charge never takes the balance below zero: {@link #fitTo} cuts it to what the account holds (the GST inside the
 * smaller amount is worked out again).
 */
public final class DepositCharges {

    public static final String MIN_BALANCE = "MIN_BALANCE";
    public static final String CLOSURE = "CLOSURE";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_CHARGE = new BigDecimal("1000000");

    /** A named fixed charge of the product. */
    public record Service(String code, String name, BigDecimal amount) {}

    /**
     * The product's schedule.
     *
     * @param gstRatePercent             GST on every charge (18 by default)
     * @param minBalancePercent          percentage of the shortfall charged; null: no minimum balance charge
     * @param minBalanceMax              the most charged for one month
     * @param closureAmount              charged on closure; null: none
     * @param closureFreeWithinDays      no closure charge when the account is closed within this many days of opening
     * @param closureWithinDays          no closure charge after this many days; null: no limit
     */
    public record Schedule(BigDecimal gstRatePercent, BigDecimal minBalancePercent, BigDecimal minBalanceMax, BigDecimal closureAmount,
                           Integer closureFreeWithinDays, Integer closureWithinDays, List<Service> service) {
        public Schedule {
            service = service == null ? List.of() : List.copyOf(service);
        }

        public static Schedule none() {
            return new Schedule(BigDecimal.valueOf(18), null, null, null, null, null, List.of());
        }

        public Optional<Service> service(String code) {
            return service.stream().filter(s -> s.code().equals(code)).findFirst();
        }
    }

    /** GST on a charge: CGST and SGST (half each) when supplied within the state, IGST otherwise. */
    public record Tax(BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {
        public BigDecimal total() {
            return cgst.add(sgst).add(igst);
        }
    }

    /** One charge with its tax, as posted and invoiced. */
    public record Charge(String code, String description, BigDecimal taxable, BigDecimal gstRatePercent, Tax tax,
                         String supplierState, String placeOfSupply) {
        public BigDecimal total() {
            return taxable.add(tax.total());
        }
    }

    private DepositCharges() {}

    /** Why a schedule cannot be used; empty when it can. */
    public static List<String> problems(Schedule s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        if (s.gstRatePercent() == null || s.gstRatePercent().signum() < 0 || s.gstRatePercent().compareTo(HUNDRED) > 0) {
            out.add("the GST rate on charges is from 0 to 100 percent");
        }
        if ((s.minBalancePercent() == null) != (s.minBalanceMax() == null)) {
            out.add("a minimum balance charge needs both its percentage of the shortfall and its most for a month");
        } else if (s.minBalancePercent() != null) {
            if (s.minBalancePercent().signum() <= 0 || s.minBalancePercent().compareTo(HUNDRED) > 0) {
                out.add("the minimum balance charge is more than 0 and at most 100 percent of the shortfall");
            }
            if (!money(s.minBalanceMax(), false)) out.add("the most charged for a month's shortfall is an amount above zero");
        }
        if (s.closureAmount() != null) {
            if (!money(s.closureAmount(), false)) out.add("the closure charge is an amount above zero");
            if (s.closureFreeWithinDays() == null || s.closureFreeWithinDays() < 0 || s.closureFreeWithinDays() > 3660) {
                out.add("the closure charge needs its free period (0 to 3660 days)");
            }
            if (s.closureWithinDays() != null && s.closureFreeWithinDays() != null && s.closureWithinDays() <= s.closureFreeWithinDays()) {
                out.add("the closure charge ends after its free period");
            }
        } else if (s.closureFreeWithinDays() != null || s.closureWithinDays() != null) {
            out.add("a free period and a limit go with a closure charge");
        }
        Set<String> codes = new HashSet<>();
        for (Service x : s.service()) {
            if (x.code() == null || !x.code().matches("[A-Z][A-Z0-9_]{2,29}") || MIN_BALANCE.equals(x.code()) || CLOSURE.equals(x.code())) {
                out.add("a service charge code is 3 to 30 capitals, digits or _ (not MIN_BALANCE or CLOSURE)");
            } else if (!codes.add(x.code())) {
                out.add("service charge " + x.code() + " is listed twice");
            }
            if (x.name() == null || x.name().isBlank() || x.name().length() > 100) out.add("each service charge has a name of at most 100 characters");
            if (!money(x.amount(), false)) out.add("service charge " + x.code() + ": the amount is above zero");
        }
        if (s.service().size() > 30) out.add("at most 30 service charges");
        return out;
    }

    private static boolean money(BigDecimal v, boolean zero) {
        return v != null && (zero ? v.signum() >= 0 : v.signum() > 0) && v.compareTo(MAX_CHARGE) <= 0 && v.stripTrailingZeros().scale() <= 2;
    }

    /** A charge of {@code taxable} with GST at {@code rate}, by place of supply (the branch's state and the depositor's). */
    public static Charge charge(String code, String description, BigDecimal taxable, BigDecimal rate, String supplierState, String placeOfSupply) {
        BigDecimal base = taxable.setScale(2, RoundingMode.HALF_UP);
        BigDecimal gst = base.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        return new Charge(code, description, base, rate, split(gst, supplierState, placeOfSupply), supplierState,
                placeOfSupply == null ? supplierState : placeOfSupply);
    }

    static Tax split(BigDecimal gst, String supplierState, String placeOfSupply) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        if (gst.signum() == 0) return new Tax(zero, zero, zero);
        if (placeOfSupply != null && !placeOfSupply.equals(supplierState)) return new Tax(zero, zero, gst);
        BigDecimal half = gst.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        return new Tax(half, gst.subtract(half), zero);
    }

    /**
     * The charge cut to what the account can pay: unchanged when {@code available} covers it, otherwise the available
     * amount taken as tax-inclusive (taxable = available x 100 / (100 + rate)); empty when nothing is available.
     */
    public static Optional<Charge> fitTo(Charge c, BigDecimal available) {
        if (available == null || available.signum() <= 0) return Optional.empty();
        if (available.compareTo(c.total()) >= 0) return Optional.of(c);
        BigDecimal gross = available.setScale(2, RoundingMode.DOWN);
        BigDecimal taxable = gross.multiply(HUNDRED).divide(HUNDRED.add(c.gstRatePercent()), 2, RoundingMode.DOWN);
        if (taxable.signum() <= 0) return Optional.empty();
        return Optional.of(new Charge(c.code(), c.description(), taxable, c.gstRatePercent(),
                split(gross.subtract(taxable), c.supplierState(), c.placeOfSupply()), c.supplierState(), c.placeOfSupply()));
    }

    /** The average of the daily closing balances from {@code from} to {@code to}, both included, to the paisa. */
    public static BigDecimal averageBalance(LocalDate from, LocalDate to, Function<LocalDate, BigDecimal> closing) {
        if (to.isBefore(from)) throw new IllegalArgumentException("the period ends before it starts");
        BigDecimal sum = BigDecimal.ZERO;
        long days = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            sum = sum.add(closing.apply(d));
            days++;
        }
        return sum.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
    }

    /** The shortfall of an average against the minimum; zero when there is none. */
    public static BigDecimal shortfall(BigDecimal minimum, BigDecimal average) {
        return minimum == null ? BigDecimal.ZERO : minimum.subtract(average).max(BigDecimal.ZERO);
    }

    /** The minimum balance charge (before GST) for a shortfall; empty when the schedule has none or there is no shortfall. */
    public static Optional<BigDecimal> minimumBalanceCharge(Schedule s, BigDecimal shortfall) {
        if (s == null || s.minBalancePercent() == null || shortfall.signum() <= 0) return Optional.empty();
        BigDecimal c = shortfall.multiply(s.minBalancePercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP).min(s.minBalanceMax());
        return c.signum() > 0 ? Optional.of(c) : Optional.empty();
    }

    /** The closure charge (before GST) for an account opened on {@code openedOn} and closed on {@code closeOn}. */
    public static Optional<BigDecimal> closureCharge(Schedule s, LocalDate openedOn, LocalDate closeOn) {
        if (s == null || s.closureAmount() == null) return Optional.empty();
        long days = ChronoUnit.DAYS.between(openedOn, closeOn);
        if (days < s.closureFreeWithinDays()) return Optional.empty();
        if (s.closureWithinDays() != null && days > s.closureWithinDays()) return Optional.empty();
        return Optional.of(s.closureAmount());
    }
}
