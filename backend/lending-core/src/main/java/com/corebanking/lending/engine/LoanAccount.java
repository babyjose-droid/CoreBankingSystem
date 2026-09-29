package com.corebanking.lending.engine;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.Rounding;
import com.corebanking.calc.ScheduleGenerator.Instalment;
import com.corebanking.lending.engine.Appropriation.Component;
import com.corebanking.lending.engine.Delinquency.AssetClass;
import com.corebanking.ledger.TransactionLot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One loan's financial state and every event that changes it. Pure and deterministic: each operation returns the
 * posting lots it produced, and the state can be snapshotted before an operation so a reversal restores it exactly
 * (US-058). The EOD steps, the API and the previews all go through this class (ADR-006).
 *
 * <p>Conventions: interest accrues daily at the EOD of day d for the night d→d+1, so a period (prev due, due]
 * accrues on days prev..due-1. On the due date the demand is raised for the scheduled (rupee-rounded) interest and
 * the difference to the accrued paise is trued up.
 */
public final class LoanAccount {

    public enum Status { ACTIVE, CLOSED, CANCELLED, FROZEN, WRITTEN_OFF }
    public enum PrepaymentMode { REDUCE_EMI, REDUCE_TENURE }

    /** Product and account parameters fixed at booking (accounts keep their product version, US-043). */
    public record Params(String loanNo, String branch, String supplierState, String recipientState, BigDecimal ratePercent,
                         BigDecimal penalRatePercent, DayCount dayCount, Rounding rounding, List<Component> sequence,
                         Appropriation.Mode mode, int coolingOffDays, BigDecimal securedPortion, LoanPostings.GlMap gl,
                         List<FeeRule> fees) {
        public Params {
            Objects.requireNonNull(loanNo);
            Objects.requireNonNull(branch);
            dayCount = dayCount == null ? DayCount.ACTUAL_365 : dayCount;
            rounding = rounding == null ? Rounding.RUPEE_HALF_UP : rounding;
            sequence = sequence == null ? Appropriation.DEFAULT_SEQUENCE : List.copyOf(sequence);
            mode = mode == null ? Appropriation.Mode.BY_DEMAND : mode;
            securedPortion = securedPortion == null ? BigDecimal.ZERO : securedPortion;
            gl = gl == null ? LoanPostings.GlMap.starter() : gl;
            fees = fees == null ? List.of() : List.copyOf(fees);
        }
    }

    public record DemandRow(int instalmentNo, LocalDate dueDate, BigDecimal principalDue, BigDecimal interestDue,
                            BigDecimal principalPaid, BigDecimal interestPaid) {
        public BigDecimal principalUnpaid() { return principalDue.subtract(principalPaid); }
        public BigDecimal interestUnpaid() { return interestDue.subtract(interestPaid); }
        boolean unpaid() { return principalUnpaid().signum() > 0 || interestUnpaid().signum() > 0; }
    }

    public record ChargeRow(String id, String code, String name, Component kind, LocalDate date, BigDecimal amount,
                            BigDecimal paid, BigDecimal waived) {
        public BigDecimal unpaid() { return amount.subtract(paid).subtract(waived); }
    }

    /** Complete state, serialisable as JSON; stored before every transaction so it can be reversed. */
    public record Snapshot(Status status, LocalDate disbursedOn, BigDecimal disbursedAmount, BigDecimal principalOutstanding,
                           List<Instalment> futureSchedule, List<DemandRow> demands, List<ChargeRow> charges,
                           BigDecimal accruedNotDemanded, BigDecimal carriedInterest, LocalDate lastAccrualDate,
                           BigDecimal excess, AssetClass assetClass, LocalDate npaSince, int dpd, BigDecimal suspense,
                           BigDecimal provisionHeld, int chargeSeq) {}

    public record Result(List<TransactionLot> lots, String summary) {}

    private final Params p;
    private Status status;
    private LocalDate disbursedOn;
    private BigDecimal disbursedAmount;
    private BigDecimal principalOutstanding;
    private List<Instalment> future;
    private List<DemandRow> demands;
    private List<ChargeRow> charges;
    private BigDecimal accruedNotDemanded;
    private BigDecimal carriedInterest;
    private LocalDate lastAccrualDate;
    private BigDecimal excess;
    private AssetClass assetClass;
    private LocalDate npaSince;
    private int dpd;
    private BigDecimal suspense;
    private BigDecimal provisionHeld;
    private int chargeSeq;

    private LoanAccount(Params p, Snapshot s) {
        this.p = p;
        restore(s);
    }

    public static LoanAccount restore(Params p, Snapshot s) {
        return new LoanAccount(p, s);
    }

    public void restore(Snapshot s) {
        status = s.status();
        disbursedOn = s.disbursedOn();
        disbursedAmount = s.disbursedAmount();
        principalOutstanding = s.principalOutstanding();
        future = new ArrayList<>(s.futureSchedule());
        demands = new ArrayList<>(s.demands());
        charges = new ArrayList<>(s.charges());
        accruedNotDemanded = s.accruedNotDemanded();
        carriedInterest = s.carriedInterest();
        lastAccrualDate = s.lastAccrualDate();
        excess = s.excess();
        assetClass = s.assetClass();
        npaSince = s.npaSince();
        dpd = s.dpd();
        suspense = s.suspense();
        provisionHeld = s.provisionHeld();
        chargeSeq = s.chargeSeq();
    }

    public Snapshot snapshot() {
        return new Snapshot(status, disbursedOn, disbursedAmount, principalOutstanding, List.copyOf(future), List.copyOf(demands),
                List.copyOf(charges), accruedNotDemanded, carriedInterest, lastAccrualDate, excess, assetClass, npaSince, dpd,
                suspense, provisionHeld, chargeSeq);
    }

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    // ------------------------------------------------------------------------------------------------ booking
    /**
     * Books and disburses a loan. Fees with event DISBURSEMENT are either deducted from the payout or charged to
     * the account (collected with the first dues).
     */
    public static Book disburse(Params p, LoanTerms terms, LocalDate businessDate) {
        List<Instalment> schedule = ScheduleBuilder.build(terms);
        LoanAccount a = new LoanAccount(p, new Snapshot(Status.ACTIVE, terms.disbursalDate(), terms.principal(), terms.principal(),
                schedule, List.of(), List.of(), ZERO, ZERO, terms.disbursalDate().minusDays(1), ZERO, AssetClass.STANDARD,
                null, 0, ZERO, ZERO, 0));
        LoanPostings post = a.postings(businessDate);
        List<FeeRule.Charge> deducted = new ArrayList<>();
        List<TransactionLot> lots = new ArrayList<>();
        List<FeeRule.Charge> charged = new ArrayList<>();
        for (FeeRule f : p.fees()) {
            if (f.event() != FeeRule.Event.DISBURSEMENT) continue;
            FeeRule.Charge c = f.compute(terms.principal(), p.supplierState(), p.recipientState(), Rounding.PAISE_HALF_UP);
            if (c.total().signum() == 0) continue;
            if (f.deductFromDisbursal()) deducted.add(c); else charged.add(c);
        }
        lots.add(post.disbursement(terms.principal(), deducted, terms.disbursalDate()));
        for (FeeRule.Charge c : charged) {
            a.charges.add(new ChargeRow("C" + (++a.chargeSeq), c.code(), c.name(), Component.FEE, terms.disbursalDate(), c.total(), ZERO, ZERO));
            lots.add(post.feeCharge(c, terms.disbursalDate()));
        }
        BigDecimal net = terms.principal().subtract(deducted.stream().map(FeeRule.Charge::total).reduce(ZERO, BigDecimal::add));
        return new Book(a, schedule, net, deducted, new Result(lots, "Disbursed " + terms.principal() + ", net " + net));
    }

    public record Book(LoanAccount account, List<Instalment> schedule, BigDecimal netDisbursal, List<FeeRule.Charge> deductedFees,
                       Result result) {}

    private LoanPostings postings(LocalDate businessDate) {
        return new LoanPostings(p.gl(), p.branch(), p.loanNo(), businessDate);
    }

    private void requireActive() {
        if (status == Status.FROZEN) throw new IllegalStateException("account is frozen");
        if (status != Status.ACTIVE) throw new IllegalStateException("account is " + status);
    }

    // ------------------------------------------------------------------------------------------------ EOD
    /**
     * End of day for {@code day}: raise demands falling due, accrue the night's interest, charge penal on overdue
     * amounts, adjust any advance, and re-classify. Returns all lots in posting order.
     */
    public Result endOfDay(LocalDate day, Provisioning.Rates rates) {
        return endOfDay(day, rates, day);
    }

    /**
     * As {@link #endOfDay(LocalDate, Provisioning.Rates)}, posting on {@code postingDate} with value date {@code day}.
     * Used to replay days after a back-dated reversal: the books for those days are closed, so the entries go into
     * today's books with their original value dates.
     */
    public Result endOfDay(LocalDate day, Provisioning.Rates rates, LocalDate postingDate) {
        if (status != Status.ACTIVE && status != Status.FROZEN) return new Result(List.of(), "not active");
        if (!day.isAfter(lastAccrualDate)) return new Result(List.of(), "already processed for " + day);
        LoanPostings post = postings(postingDate);
        List<TransactionLot> lots = new ArrayList<>();
        boolean npa = assetClass.isNpa();

        // 1. demands due today
        while (!future.isEmpty() && !future.get(0).dueDate().isAfter(day)) {
            Instalment due = future.remove(0);
            BigDecimal interestDue = due.interest().add(carriedInterest);
            BigDecimal trueUp = DailyCharges.trueUp(interestDue, accruedNotDemanded);
            if (trueUp.signum() != 0) {
                lots.add(post.accrual(trueUp, npa, day));
                if (npa) suspense = suspense.add(trueUp);
            }
            demands.add(new DemandRow(due.number(), due.dueDate(), due.principal(), interestDue, ZERO, ZERO));
            accruedNotDemanded = ZERO;
            carriedInterest = ZERO;
        }
        // 2. apply any advance (excess) against dues
        if (excess.signum() > 0 && !dues(day).isEmpty()) {
            Appropriation.Result split = Appropriation.allocate(dues(day), excess, p.sequence(), p.mode());
            if (!split.allocations().isEmpty()) {
                apply(split, npa);
                lots.add(post.excessAdjustment(split));
                if (npa) lots.addAll(realiseFromSuspense(post, split, day));
                excess = split.excess();
            }
        }
        // 3. accrue the night's interest on the scheduled balance ("schedule balance" method: delay is compensated by
        //    penal charges, not extra interest), until the last instalment is demanded
        if (!future.isEmpty() && principalOutstanding.signum() > 0) {
            BigDecimal i = DailyCharges.interestForDay(future.get(0).openingBalance(), p.ratePercent(), day, p.dayCount());
            if (i.signum() > 0) {
                lots.add(post.accrual(i, npa, day));
                accruedNotDemanded = accruedNotDemanded.add(i);
                if (npa) suspense = suspense.add(i);
            }
        }
        // 4. penal charge on overdue principal + interest (non-compounding: base excludes charges)
        BigDecimal overdue = demands.stream().filter(d -> d.dueDate().isBefore(day))
                .map(d -> d.principalUnpaid().add(d.interestUnpaid())).reduce(ZERO, BigDecimal::add);
        BigDecimal penal = DailyCharges.penalForDay(overdue, p.penalRatePercent(), day, p.dayCount());
        if (penal.signum() > 0) {
            addPenal(penal, day);
            lots.add(post.penal(penal, npa, day));
            if (npa) suspense = suspense.add(penal);
        }
        // 5. classification and income recognition
        lots.addAll(classify(day, post));
        // 6. provisioning
        if (rates != null) {
            BigDecimal required = Provisioning.required(principalOutstanding, p.securedPortion(), assetClass, rates);
            BigDecimal delta = required.subtract(provisionHeld);
            if (delta.signum() != 0) {
                lots.add(post.provision(delta));
                provisionHeld = required;
            }
        }
        lastAccrualDate = day;
        return new Result(lots, "EOD " + day + " dpd " + dpd + " " + assetClass);
    }

    private void addPenal(BigDecimal amount, LocalDate day) {
        for (int i = 0; i < charges.size(); i++) {
            ChargeRow c = charges.get(i);
            if (c.kind() == Component.PENAL && c.date().getYear() == day.getYear() && c.date().getMonth() == day.getMonth()
                    && c.unpaid().signum() >= 0 && c.paid().signum() == 0) {
                charges.set(i, new ChargeRow(c.id(), c.code(), c.name(), c.kind(), c.date(), c.amount().add(amount), c.paid(), c.waived()));
                return;
            }
        }
        charges.add(new ChargeRow("P" + (++chargeSeq), "PENAL", "Penal charges", Component.PENAL, day, amount, ZERO, ZERO));
    }

    private List<TransactionLot> classify(LocalDate day, LoanPostings post) {
        List<TransactionLot> lots = new ArrayList<>();
        LocalDate oldest = demands.stream().filter(DemandRow::unpaid).map(DemandRow::dueDate).min(LocalDate::compareTo)
                .orElse(charges.stream().filter(c -> c.kind() == Component.FEE && c.unpaid().signum() > 0)
                        .map(ChargeRow::date).min(LocalDate::compareTo).orElse(null));
        dpd = Delinquency.dpd(day, oldest);
        boolean arrears = oldest != null && !oldest.isAfter(day);
        AssetClass before = assetClass;
        Delinquency.Status st = Delinquency.classify(day, dpd, assetClass, npaSince, arrears);
        assetClass = st.assetClass();
        npaSince = st.npaSince();
        if (!before.isNpa() && assetClass.isNpa()) lots.addAll(onBecomingNpa(post));
        if (before.isNpa() && !assetClass.isNpa() && suspense.signum() > 0) {
            lots.add(post.suspenseToIncome(suspense));      // upgraded: remaining suspense (not-yet-due accrual) is income again
            suspense = ZERO;
        }
        return lots;
    }

    /** Borrower-level NPA (US-037): the service applies the worst class of the borrower's loans to each loan. */
    public Result applyBorrowerClass(AssetClass borrowerClass, LocalDate borrowerNpaSince, LocalDate day) {
        if (!borrowerClass.isNpa() || assetClass.isNpa()) return new Result(List.of(), "no change");
        assetClass = borrowerClass;
        npaSince = borrowerNpaSince;
        return new Result(onBecomingNpa(postings(day)), "NPA by borrower");
    }

    private List<TransactionLot> onBecomingNpa(LoanPostings post) {
        BigDecimal unpaidInterest = demands.stream().map(DemandRow::interestUnpaid).reduce(ZERO, BigDecimal::add).add(accruedNotDemanded);
        BigDecimal unpaidPenal = charges.stream().filter(c -> c.kind() == Component.PENAL).map(ChargeRow::unpaid).reduce(ZERO, BigDecimal::add);
        BigDecimal move = unpaidInterest.add(unpaidPenal).subtract(suspense);
        if (move.signum() <= 0) return List.of();
        suspense = suspense.add(move);
        BigDecimal interestPart = unpaidInterest.min(move);
        return List.of(post.npaIncomeReversal(interestPart, move.subtract(interestPart)));
    }

    // ------------------------------------------------------------------------------------------------ receipts
    private List<Appropriation.Due> dues(LocalDate asOf) {
        List<Appropriation.Due> dues = new ArrayList<>();
        for (DemandRow d : demands) {
            if (d.dueDate().isAfter(asOf)) continue;
            String ref = "D" + d.instalmentNo();
            if (d.interestUnpaid().signum() > 0) dues.add(new Appropriation.Due(ref, d.dueDate(), Component.INTEREST, d.interestUnpaid()));
            if (d.principalUnpaid().signum() > 0) dues.add(new Appropriation.Due(ref, d.dueDate(), Component.PRINCIPAL, d.principalUnpaid()));
        }
        for (ChargeRow c : charges) {
            if (c.unpaid().signum() > 0) dues.add(new Appropriation.Due(c.id(), c.date(), c.kind(), c.unpaid()));
        }
        return dues;
    }

    private void apply(Appropriation.Result split, boolean npa) {
        for (Appropriation.Allocation a : split.allocations()) {
            if (a.component() == Component.INTEREST || a.component() == Component.PRINCIPAL) {
                int n = Integer.parseInt(a.ref().substring(1));
                for (int i = 0; i < demands.size(); i++) {
                    DemandRow d = demands.get(i);
                    if (d.instalmentNo() != n) continue;
                    demands.set(i, a.component() == Component.INTEREST
                            ? new DemandRow(d.instalmentNo(), d.dueDate(), d.principalDue(), d.interestDue(), d.principalPaid(), d.interestPaid().add(a.amount()))
                            : new DemandRow(d.instalmentNo(), d.dueDate(), d.principalDue(), d.interestDue(), d.principalPaid().add(a.amount()), d.interestPaid()));
                }
                if (a.component() == Component.PRINCIPAL) principalOutstanding = principalOutstanding.subtract(a.amount());
            } else {
                for (int i = 0; i < charges.size(); i++) {
                    ChargeRow c = charges.get(i);
                    if (!c.id().equals(a.ref())) continue;
                    charges.set(i, new ChargeRow(c.id(), c.code(), c.name(), c.kind(), c.date(), c.amount(), c.paid().add(a.amount()), c.waived()));
                }
            }
        }
    }

    private List<TransactionLot> realiseFromSuspense(LoanPostings post, Appropriation.Result split, LocalDate day) {
        BigDecimal realised = split.total(Component.INTEREST).add(split.total(Component.PENAL)).min(suspense);
        if (realised.signum() <= 0) return List.of();
        suspense = suspense.subtract(realised);
        return List.of(post.suspenseToIncome(realised));
    }

    /** Receipt against dues (EMI, overdue, charges). Anything above the dues is kept as an advance (US-055). */
    public Result pay(BigDecimal amount, LocalDate valueDate, LocalDate businessDate, String narration) {
        requireActive();
        boolean npa = assetClass.isNpa();
        Appropriation.Result split = Appropriation.allocate(dues(businessDate), amount, p.sequence(), p.mode());
        apply(split, npa);
        excess = excess.add(split.excess());
        LoanPostings post = postings(businessDate);
        List<TransactionLot> lots = new ArrayList<>();
        BigDecimal realised = split.total(Component.INTEREST).add(split.total(Component.PENAL)).min(suspense);
        // the repayment lot moves realised NPA interest/penal from suspense to income itself
        lots.add(post.repayment(amount, split, npa && realised.signum() > 0, valueDate, narration));
        if (npa) suspense = suspense.subtract(realised);
        lots.addAll(classify(businessDate, post));
        if (principalOutstanding.signum() == 0 && future.isEmpty() && dues(businessDate).isEmpty()) {
            if (provisionHeld.signum() > 0) {
                lots.add(post.provision(provisionHeld.negate()));
                provisionHeld = ZERO;
            }
            status = Status.CLOSED;               // fully repaid; any advance stays payable to the borrower
        }
        return new Result(lots, "Received " + amount + (split.excess().signum() > 0 ? ", advance " + split.excess() : "")
                + (status == Status.CLOSED ? "; loan closed" : ""));
    }

    /**
     * Part-prepayment of principal (US-055). Dues must be clear. The rest of the schedule is rebuilt from today:
     * REDUCE_EMI keeps the remaining instalment count, REDUCE_TENURE keeps the EMI. Interest already accrued on the
     * old balance is carried into the next demand.
     */
    public Result prepay(BigDecimal amount, PrepaymentMode mode, LocalDate businessDate) {
        requireActive();
        if (!dues(businessDate).isEmpty()) throw new IllegalStateException("clear overdue dues before a prepayment");
        if (amount.signum() <= 0 || amount.compareTo(principalOutstanding) >= 0) {
            throw new IllegalArgumentException("prepayment must be less than the principal outstanding; use pre-closure");
        }
        if (future.isEmpty()) throw new IllegalStateException("no future instalments");
        if (!future.get(0).dueDate().isAfter(businessDate)) throw new IllegalStateException("an instalment falls due today; prepay after end of day");
        List<TransactionLot> lots = new ArrayList<>();
        LoanPostings post = postings(businessDate);
        for (FeeRule f : p.fees()) {
            if (f.event() != FeeRule.Event.PART_PREPAYMENT) continue;
            FeeRule.Charge c = f.compute(amount, p.supplierState(), p.recipientState(), Rounding.PAISE_HALF_UP);
            if (c.total().signum() > 0) {
                charges.add(new ChargeRow("C" + (++chargeSeq), c.code(), c.name(), Component.FEE, businessDate, c.total(), ZERO, ZERO));
                lots.add(post.feeCharge(c, businessDate));
            }
        }
        lots.add(post.principalPrepayment(amount, businessDate));
        principalOutstanding = principalOutstanding.subtract(amount);
        carriedInterest = carriedInterest.add(accruedNotDemanded);
        BigDecimal emiBefore = future.get(0).instalment();
        LocalDate nextDue = future.get(0).dueDate();
        int remaining = future.size();
        LoanTerms t = new LoanTerms(principalOutstanding, p.ratePercent(), remaining, businessDate, nextDue,
                RepaymentMethod.EQUATED, 0, ZERO, p.dayCount(), p.rounding(), false);
        List<Instalment> rebuilt = ScheduleBuilder.build(t);
        if (mode == PrepaymentMode.REDUCE_TENURE) rebuilt = keepEmi(rebuilt, emiBefore, t);
        future = renumber(rebuilt, demands.size());
        return new Result(lots, "Prepaid " + amount + ", " + future.size() + " instalments left, next " + future.get(0).instalment());
    }

    private List<Instalment> keepEmi(List<Instalment> base, BigDecimal emi, LoanTerms t) {
        List<Instalment> rows = new ArrayList<>();
        BigDecimal bal = t.principal();
        LocalDate prev = t.disbursalDate();
        for (int n = 1; bal.signum() > 0 && n <= base.size(); n++) {
            LocalDate due = ScheduleBuilder.dueDate(t, n);
            BigDecimal interest = t.rounding().apply(bal.multiply(t.ratePercent().divide(BigDecimal.valueOf(100), java.math.MathContext.DECIMAL128))
                    .multiply(t.dayCount().yearFraction(prev, due)));
            BigDecimal principal = emi.subtract(interest).min(bal);
            if (principal.signum() <= 0) throw new IllegalStateException("EMI does not cover interest");
            if (bal.subtract(principal).compareTo(BigDecimal.ONE) < 0) principal = bal;   // no tiny tail instalment
            rows.add(new Instalment(n, due, t.dayCount().days(prev, due), bal, interest, principal, principal.add(interest), bal.subtract(principal)));
            bal = bal.subtract(principal);
            prev = due;
        }
        return rows;
    }

    private static List<Instalment> renumber(List<Instalment> rows, int alreadyDemanded) {
        List<Instalment> out = new ArrayList<>();
        for (Instalment i : rows) {
            out.add(new Instalment(alreadyDemanded + i.number(), i.dueDate(), i.days(), i.openingBalance(), i.interest(),
                    i.principal(), i.instalment(), i.closingBalance()));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ closure
    /** Pre-closure amount on {@code day} (US-056). Quote and posting use the same numbers. */
    public record Quote(BigDecimal principal, BigDecimal overdueDues, BigDecimal accruedInterest, BigDecimal charges,
                        FeeRule.Charge foreclosureFee, BigDecimal excess, BigDecimal total) {}

    public Quote preclosureQuote(LocalDate day) {
        BigDecimal overdue = demands.stream().map(d -> d.principalUnpaid().add(d.interestUnpaid())).reduce(ZERO, BigDecimal::add);
        BigDecimal overduePrincipal = demands.stream().map(DemandRow::principalUnpaid).reduce(ZERO, BigDecimal::add);
        BigDecimal futurePrincipal = principalOutstanding.subtract(overduePrincipal);
        BigDecimal accrued = accruedNotDemanded.setScale(0, java.math.RoundingMode.HALF_UP);   // includes any carried interest
        BigDecimal ch = charges.stream().map(ChargeRow::unpaid).reduce(ZERO, BigDecimal::add);
        FeeRule.Charge fee = null;
        for (FeeRule f : p.fees()) {
            if (f.event() == FeeRule.Event.PRECLOSURE) fee = f.compute(futurePrincipal, p.supplierState(), p.recipientState(), Rounding.PAISE_HALF_UP);
        }
        BigDecimal feeTotal = fee == null ? ZERO : fee.total();
        BigDecimal total = futurePrincipal.add(overdue).add(accrued).add(ch).add(feeTotal).subtract(excess);
        return new Quote(futurePrincipal, overdue, accrued, ch, fee, excess, total.max(ZERO));
    }

    public Result preclose(BigDecimal amount, LocalDate businessDate) {
        requireActive();
        Quote q = preclosureQuote(businessDate);
        if (amount.compareTo(q.total()) != 0) throw new IllegalArgumentException("pre-closure amount must be " + q.total());
        LoanPostings post = postings(businessDate);
        List<TransactionLot> lots = new ArrayList<>();
        boolean npa = assetClass.isNpa();
        if (q.foreclosureFee() != null && q.foreclosureFee().total().signum() > 0) {
            charges.add(new ChargeRow("C" + (++chargeSeq), q.foreclosureFee().code(), q.foreclosureFee().name(), Component.FEE,
                    businessDate, q.foreclosureFee().total(), ZERO, ZERO));
            lots.add(post.feeCharge(q.foreclosureFee(), businessDate));
        }
        // final broken-period demand: accrued interest (true-up to whole rupees) and all remaining principal
        BigDecimal trueUp = q.accruedInterest().subtract(accruedNotDemanded);
        if (trueUp.signum() != 0) lots.add(post.accrual(trueUp, npa, businessDate));
        if (npa) suspense = suspense.add(trueUp);
        int n = demands.size() + 1;
        demands.add(new DemandRow(n, businessDate, q.principal(), q.accruedInterest(), ZERO, ZERO));
        future.clear();
        accruedNotDemanded = ZERO;
        carriedInterest = ZERO;
        BigDecimal fromExcess = excess;
        excess = ZERO;
        Appropriation.Result split = Appropriation.allocate(dues(businessDate), amount.add(fromExcess), p.sequence(), p.mode());
        apply(split, npa);
        if (fromExcess.signum() > 0) {
            Appropriation.Result adv = Appropriation.allocate(List.of(new Appropriation.Due("X", businessDate, Component.PRINCIPAL,
                    fromExcess)), fromExcess, p.sequence(), p.mode());
            lots.add(post.excessAdjustment(adv));
            split = new Appropriation.Result(reduce(split, fromExcess), split.excess());
        }
        BigDecimal realised = split.total(Component.INTEREST).add(split.total(Component.PENAL)).min(suspense);
        lots.add(post.repayment(amount, split, npa && realised.signum() > 0, businessDate, "Pre-closure"));
        suspense = suspense.subtract(realised.max(ZERO));
        if (principalOutstanding.signum() != 0 || !dues(businessDate).isEmpty()) {
            throw new IllegalStateException("pre-closure left a balance; quote and posting disagree");
        }
        if (provisionHeld.signum() > 0) {
            lots.add(post.provision(provisionHeld.negate()));
            provisionHeld = ZERO;
        }
        if (suspense.signum() > 0) {
            lots.add(post.suspenseToIncome(suspense));
            suspense = ZERO;
        }
        status = Status.CLOSED;
        return new Result(lots, "Pre-closed for " + amount);
    }

    /** The part of an allocation funded by the advance is removed from the principal credited by the bank receipt. */
    private static List<Appropriation.Allocation> reduce(Appropriation.Result split, BigDecimal fromExcess) {
        List<Appropriation.Allocation> out = new ArrayList<>();
        BigDecimal left = fromExcess;
        for (Appropriation.Allocation a : split.allocations()) {
            if (a.component() == Component.PRINCIPAL && left.signum() > 0) {
                BigDecimal cut = left.min(a.amount());
                left = left.subtract(cut);
                if (a.amount().subtract(cut).signum() > 0) out.add(new Appropriation.Allocation(a.ref(), a.dueDate(), a.component(), a.amount().subtract(cut)));
            } else {
                out.add(a);
            }
        }
        return out;
    }

    /** Cooling-off exit (US-053): principal plus interest accrued for the days used; disclosed fees are retained. */
    public Result cancel(BigDecimal amount, LocalDate businessDate) {
        requireActive();
        if (!Cancellation.withinCoolingOff(disbursedOn, p.coolingOffDays(), businessDate)) {
            throw new IllegalStateException("the cooling-off period ended on " + disbursedOn.plusDays(p.coolingOffDays()));
        }
        BigDecimal due = cancellationAmount();
        if (amount.compareTo(due) != 0) throw new IllegalArgumentException("cancellation amount must be " + due);
        LoanPostings post = postings(businessDate);
        List<TransactionLot> lots = new ArrayList<>();
        BigDecimal unpaidCharges = charges.stream().map(ChargeRow::unpaid).reduce(ZERO, BigDecimal::add);
        BigDecimal interest = due.subtract(principalOutstanding).subtract(unpaidCharges);
        BigDecimal trueUp = interest.subtract(accruedNotDemanded);
        if (trueUp.signum() != 0) lots.add(post.accrual(trueUp, false, businessDate));
        demands.add(new DemandRow(demands.size() + 1, businessDate, principalOutstanding, interest, ZERO, ZERO));
        future.clear();
        accruedNotDemanded = ZERO;
        Appropriation.Result split = Appropriation.allocate(dues(businessDate), amount, p.sequence(), p.mode());
        apply(split, false);
        lots.add(post.repayment(amount, split, false, businessDate, "Cancellation"));
        status = Status.CANCELLED;
        return new Result(lots, "Cancelled in cooling-off; received " + amount);
    }

    /** Principal + interest for the days used + any charge not deducted at disbursal. */
    public BigDecimal cancellationAmount() {
        BigDecimal unpaidCharges = charges.stream().map(ChargeRow::unpaid).reduce(ZERO, BigDecimal::add);
        return principalOutstanding.add(accruedNotDemanded.setScale(0, java.math.RoundingMode.HALF_UP)).add(unpaidCharges);
    }

    // ------------------------------------------------------------------------------------------------ charges
    public Result chargeFee(String feeCode, BigDecimal base, LocalDate businessDate) {
        requireActive();
        FeeRule rule = p.fees().stream().filter(f -> f.code().equals(feeCode)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("fee " + feeCode + " is not defined on the product"));
        FeeRule.Charge c = rule.compute(base, p.supplierState(), p.recipientState(), Rounding.PAISE_HALF_UP);
        charges.add(new ChargeRow("C" + (++chargeSeq), c.code(), c.name(), Component.FEE, businessDate, c.total(), ZERO, ZERO));
        return new Result(List.of(postings(businessDate).feeCharge(c, businessDate)), "Charged " + c.name() + " " + c.total());
    }

    /** Waives an unpaid charge (fee or penal) in full or part (US-057). */
    public Result waiveCharge(String chargeId, BigDecimal amount, LocalDate businessDate) {
        requireActive();
        for (int i = 0; i < charges.size(); i++) {
            ChargeRow c = charges.get(i);
            if (!c.id().equals(chargeId)) continue;
            if (amount.signum() <= 0 || amount.compareTo(c.unpaid()) > 0) throw new IllegalArgumentException("waiver must be 0 < amount <= " + c.unpaid());
            charges.set(i, new ChargeRow(c.id(), c.code(), c.name(), c.kind(), c.date(), c.amount(), c.paid(), c.waived().add(amount)));
            boolean npa = assetClass.isNpa() && c.kind() == Component.PENAL;
            if (npa) suspense = suspense.subtract(amount.min(suspense));
            return new Result(List.of(postings(businessDate).waiver(c.kind(), amount, npa)), "Waived " + amount + " of " + c.name());
        }
        throw new IllegalArgumentException("charge " + chargeId + " not found");
    }

    public void freeze() {
        requireActive();
        status = Status.FROZEN;
    }

    public void unfreeze() {
        if (status != Status.FROZEN) throw new IllegalStateException("account is not frozen");
        status = Status.ACTIVE;
    }

    // ------------------------------------------------------------------------------------------------ views
    public Status status() { return status; }
    public BigDecimal principalOutstanding() { return principalOutstanding; }
    public List<DemandRow> demands() { return List.copyOf(demands); }
    public List<ChargeRow> charges() { return List.copyOf(charges); }
    public List<Instalment> futureSchedule() { return List.copyOf(future); }
    public BigDecimal accruedNotDemanded() { return accruedNotDemanded; }
    public BigDecimal excess() { return excess; }
    public AssetClass assetClass() { return assetClass; }
    public LocalDate npaSince() { return npaSince; }
    public int dpd() { return dpd; }
    public BigDecimal suspense() { return suspense; }
    public BigDecimal provisionHeld() { return provisionHeld; }
    public Params params() { return p; }

    public BigDecimal overdueAmount(LocalDate asOf) {
        return dues(asOf).stream().map(Appropriation.Due::outstanding).reduce(ZERO, BigDecimal::add);
    }

    public LocalDate lastAccrualDate() { return lastAccrualDate; }

    public LocalDate nextDueDate() {
        return future.isEmpty() ? null : future.get(0).dueDate();
    }
}
