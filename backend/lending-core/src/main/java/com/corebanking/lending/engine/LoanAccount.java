package com.corebanking.lending.engine;

import com.corebanking.calc.DayCount;
import com.corebanking.calc.EmiCalculator;
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

    /**
     * A demand raised. On restructuring, unpaid principal is rescheduled into the new schedule
     * ({@code principalRescheduled}) and unpaid interest may be capitalised ({@code interestCapitalised}); neither is
     * a payment, and the demand keeps its original figures.
     */
    public record DemandRow(int instalmentNo, LocalDate dueDate, BigDecimal principalDue, BigDecimal interestDue,
                            BigDecimal principalPaid, BigDecimal interestPaid, BigDecimal principalRescheduled,
                            BigDecimal interestCapitalised) {
        public DemandRow {
            principalRescheduled = principalRescheduled == null ? BigDecimal.ZERO : principalRescheduled;   // state stored before P2-3
            interestCapitalised = interestCapitalised == null ? BigDecimal.ZERO : interestCapitalised;
        }

        public DemandRow(int instalmentNo, LocalDate dueDate, BigDecimal principalDue, BigDecimal interestDue,
                         BigDecimal principalPaid, BigDecimal interestPaid) {
            this(instalmentNo, dueDate, principalDue, interestDue, principalPaid, interestPaid, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        public BigDecimal principalUnpaid() { return principalDue.subtract(principalPaid).subtract(principalRescheduled); }
        public BigDecimal interestUnpaid() { return interestDue.subtract(interestPaid).subtract(interestCapitalised); }
        boolean unpaid() { return principalUnpaid().signum() > 0 || interestUnpaid().signum() > 0; }

        DemandRow paid(Component c, BigDecimal amount) {
            return c == Component.INTEREST
                    ? new DemandRow(instalmentNo, dueDate, principalDue, interestDue, principalPaid, interestPaid.add(amount), principalRescheduled, interestCapitalised)
                    : new DemandRow(instalmentNo, dueDate, principalDue, interestDue, principalPaid.add(amount), interestPaid, principalRescheduled, interestCapitalised);
        }

        DemandRow restructured(BigDecimal principal, BigDecimal interest) {
            return new DemandRow(instalmentNo, dueDate, principalDue, interestDue, principalPaid, interestPaid,
                    principalRescheduled.add(principal), interestCapitalised.add(interest));
        }
    }

    public record ChargeRow(String id, String code, String name, Component kind, LocalDate date, BigDecimal amount,
                            BigDecimal paid, BigDecimal waived) {
        public BigDecimal unpaid() { return amount.subtract(paid).subtract(waived); }
    }

    /** One disbursement of the sanctioned amount (US-050). {@code interestDeducted}: broken-period interest taken upfront. */
    public record TrancheRow(int no, LocalDate date, BigDecimal amount, BigDecimal feesDeducted, BigDecimal interestDeducted,
                             BigDecimal net) {}

    /**
     * Complete state, serialisable as JSON; stored before every transaction so it can be reversed.
     *
     * @param ratePercent          current rate (changes on a rate amendment or restructure); null in state stored
     *                             before P2-3 means the booked rate
     * @param capitalisedSuspense  part of {@code suspense} that is interest capitalised on restructuring; realised
     *                             only as principal is repaid
     * @param restructure          set once the loan has been restructured
     * @param terms                the terms as sanctioned (method, frequency, interest basis); null in state stored
     *                             before P2-6 means a monthly equated loan
     * @param sanctioned           sanctioned amount; null (before P2-6) means the amount disbursed
     * @param tranches             disbursements made so far
     * @param preEmi               interest only on the amount drawn until the loan is fully disbursed
     * @param interestInAdvance    broken-period interest deducted at disbursal, not yet set against its demand
     * @param classFloor           asset class the account is held at or below by a manual override, until
     *                             {@code classFloorUntil} (inclusive); an override never upgrades
     */
    public record Snapshot(Status status, LocalDate disbursedOn, BigDecimal disbursedAmount, BigDecimal principalOutstanding,
                           List<Instalment> futureSchedule, List<DemandRow> demands, List<ChargeRow> charges,
                           BigDecimal accruedNotDemanded, BigDecimal carriedInterest, LocalDate lastAccrualDate,
                           BigDecimal excess, AssetClass assetClass, LocalDate npaSince, int dpd, BigDecimal suspense,
                           BigDecimal provisionHeld, int chargeSeq, BigDecimal ratePercent, BigDecimal capitalisedSuspense,
                           RestructureStatus restructure, LoanTerms terms, BigDecimal sanctioned, List<TrancheRow> tranches,
                           Boolean preEmi, BigDecimal interestInAdvance, AssetClass classFloor, LocalDate classFloorUntil) {}

    public record Result(List<TransactionLot> lots, String summary) {}

    /**
     * An amount as a summary shows it: no trailing zeros ("5000", not "5000.0000"). Callers pass amounts at whatever
     * scale they hold them — a receipt from a payment gateway arrives at the scale of its database column.
     */
    static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

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
    private BigDecimal rate;
    private BigDecimal capitalisedSuspense;
    private RestructureStatus restructure;
    private LoanTerms terms;
    private BigDecimal sanctioned;
    private List<TrancheRow> tranches;
    private boolean preEmi;
    private BigDecimal interestInAdvance;
    private AssetClass classFloor;
    private LocalDate classFloorUntil;

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
        rate = s.ratePercent() == null ? p.ratePercent() : s.ratePercent();
        capitalisedSuspense = s.capitalisedSuspense() == null ? BigDecimal.ZERO : s.capitalisedSuspense();
        restructure = s.restructure();
        terms = s.terms();
        sanctioned = s.sanctioned() == null ? s.disbursedAmount() : s.sanctioned();
        tranches = new ArrayList<>(s.tranches() == null ? List.<TrancheRow>of() : s.tranches());
        preEmi = Boolean.TRUE.equals(s.preEmi());
        interestInAdvance = s.interestInAdvance() == null ? BigDecimal.ZERO : s.interestInAdvance();
        classFloor = s.classFloor();
        classFloorUntil = s.classFloorUntil();
    }

    public Snapshot snapshot() {
        return new Snapshot(status, disbursedOn, disbursedAmount, principalOutstanding, List.copyOf(future), List.copyOf(demands),
                List.copyOf(charges), accruedNotDemanded, carriedInterest, lastAccrualDate, excess, assetClass, npaSince, dpd,
                suspense, provisionHeld, chargeSeq, rate, capitalisedSuspense, restructure, terms, sanctioned, List.copyOf(tranches),
                preEmi, interestInAdvance, classFloor, classFloorUntil);
    }

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    // ------------------------------------------------------------------------------------------------ booking
    /**
     * Books and disburses a loan in full. Fees with event DISBURSEMENT are either deducted from the payout or charged
     * to the account (collected with the first dues).
     */
    public static Book disburse(Params p, LoanTerms terms, LocalDate businessDate) {
        return open(p, terms, terms.principal(), false, businessDate);
    }

    /**
     * Books a loan and disburses its first tranche (US-050). {@code terms.principal()} is the sanctioned amount.
     * <ul>
     *   <li>Fees: DISBURSEMENT fees are charged once, here, on the sanctioned amount; EVERY_DISBURSEMENT fees on each
     *       tranche's amount. Either kind is deducted from the payout or charged to the account, as the rule says.</li>
     *   <li>Broken-period interest in mode DEDUCT_AT_DISBURSAL is computed on this tranche and deducted from it.</li>
     *   <li>Schedule: built on the amount drawn. With {@code preEmi} (equated loans) every instalment is interest only
     *       until the loan is fully drawn or the undrawn part is cancelled, and the EMIs then run for the full tenor;
     *       otherwise the instalments are recomputed on each tranche over the instalments left ("variable instalment").</li>
     * </ul>
     */
    public static Book open(Params p, LoanTerms terms, BigDecimal firstTranche, boolean preEmi, LocalDate businessDate) {
        Objects.requireNonNull(firstTranche, "firstTranche");
        if (firstTranche.signum() <= 0 || firstTranche.compareTo(terms.principal()) > 0) {
            throw new IllegalArgumentException("the first disbursement must be above zero and at most the sanctioned amount "
                    + terms.principal().toPlainString());
        }
        boolean partial = firstTranche.compareTo(terms.principal()) < 0;
        if (partial) requireTrancheable(terms);
        boolean pre = preEmi && partial && terms.method() == RepaymentMethod.EQUATED;
        LoanTerms drawn = !partial ? terms
                : new LoanTerms(firstTranche, terms.ratePercent(), terms.tenorMonths() + (pre ? 1 : 0), terms.disbursalDate(),
                        terms.firstDueDate(), terms.method(), terms.moratoriumMonths() + (pre ? 1 : 0), ZERO, terms.dayCount(),
                        terms.rounding(), terms.extraDayOnFirst(), terms.options());
        ScheduleBuilder.Plan plan = ScheduleBuilder.plan(drawn);
        List<Instalment> schedule = plan.schedule();
        BigDecimal advance = plan.bpiDeducted() ? plan.brokenPeriodInterest() : ZERO;
        LoanAccount a = new LoanAccount(p, new Snapshot(Status.ACTIVE, terms.disbursalDate(), firstTranche, firstTranche,
                schedule, List.of(), List.of(), ZERO, ZERO, terms.disbursalDate().minusDays(1), ZERO, AssetClass.STANDARD,
                null, 0, ZERO, ZERO, 0, plan.accrualRatePercent(), ZERO, null, terms, terms.principal(), List.of(), pre, advance,
                null, null));
        LoanPostings post = a.postings(businessDate);
        List<FeeRule.Charge> deducted = new ArrayList<>();
        List<TransactionLot> lots = new ArrayList<>();
        List<FeeRule.Charge> charged = new ArrayList<>();
        for (FeeRule f : p.fees()) {
            BigDecimal base;
            if (f.event() == FeeRule.Event.DISBURSEMENT) base = terms.principal();
            else if (f.event() == FeeRule.Event.EVERY_DISBURSEMENT) base = firstTranche;
            else continue;
            FeeRule.Charge c = f.compute(base, p.supplierState(), p.recipientState(), Rounding.PAISE_HALF_UP);
            if (c.total().signum() == 0) continue;
            if (f.deductFromDisbursal()) deducted.add(c); else charged.add(c);
        }
        lots.add(post.disbursement(firstTranche, deducted, advance, terms.disbursalDate()));
        for (FeeRule.Charge c : charged) {
            a.charges.add(new ChargeRow("C" + (++a.chargeSeq), c.code(), c.name(), Component.FEE, terms.disbursalDate(), c.total(), ZERO, ZERO));
            lots.add(post.feeCharge(c, terms.disbursalDate()));
        }
        BigDecimal fees = deducted.stream().map(FeeRule.Charge::total).reduce(ZERO, BigDecimal::add);
        BigDecimal net = firstTranche.subtract(fees).subtract(advance);
        a.tranches.add(new TrancheRow(1, terms.disbursalDate(), firstTranche, fees, advance, net));
        return new Book(a, schedule, net, deducted, new Result(lots, "Disbursed " + plain(firstTranche) + ", net " + plain(net)), advance, charged);
    }

    public record Book(LoanAccount account, List<Instalment> schedule, BigDecimal netDisbursal, List<FeeRule.Charge> deductedFees,
                       Result result, BigDecimal interestDeducted, List<FeeRule.Charge> chargedFees) {}

    /** Tranches re-schedule the loan on its method and current rate: only methods that can be rebuilt that way. */
    private static void requireTrancheable(LoanTerms t) {
        LoanTerms.Options o = t.options();
        boolean method = switch (t.method()) {
            case EQUATED, BULLET_TOTAL_INTEREST, BULLET_PERIODIC_INTEREST -> true;
            case FIXED_PRINCIPAL -> o.principalEvery() == 1;
            case STEP_EQUATED, STRUCTURED -> false;
        };
        if (!method || o.interestBasis() != LoanTerms.InterestBasis.DAILY_REDUCING || o.fixedInstalment() != null || t.balloon().signum() > 0) {
            throw new IllegalArgumentException("disbursement in tranches is available for equated, fixed-principal and bullet loans"
                    + " on the daily-reducing basis without a balloon");
        }
    }

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
            DemandRow row = new DemandRow(due.number(), due.dueDate(), due.principal(), interestDue, ZERO, ZERO);
            if (interestInAdvance.signum() > 0 && interestDue.signum() > 0) {     // broken-period interest deducted at disbursal
                BigDecimal used = interestInAdvance.min(interestDue);
                row = row.paid(Component.INTEREST, used);
                lots.add(post.advanceInterestAdjustment(used, day));
                interestInAdvance = interestInAdvance.subtract(used);
            }
            demands.add(row);
            accruedNotDemanded = ZERO;
            carriedInterest = ZERO;
            rollPreEmi(due.dueDate());
        }
        // 2. apply any advance (excess) against dues
        if (excess.signum() > 0 && !dues(day).isEmpty()) {
            Appropriation.Result split = Appropriation.allocate(dues(day), excess, p.sequence(), p.mode());
            if (!split.allocations().isEmpty()) {
                BigDecimal principalBefore = principalOutstanding;
                apply(split, npa);
                lots.add(post.excessAdjustment(split));
                if (npa) lots.addAll(realiseFromSuspense(post, split, day));
                lots.addAll(releaseCapitalised(post, split.total(Component.PRINCIPAL), principalBefore));
                excess = split.excess();
            }
        }
        // 3. accrue the night's interest on the scheduled balance ("schedule balance" method: delay is compensated by
        //    penal charges, not extra interest), until the last instalment is demanded
        if (!future.isEmpty() && principalOutstanding.signum() > 0) {
            BigDecimal i = DailyCharges.interestForDay(future.get(0).openingBalance(), rate, day, p.dayCount());
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
        // 5. classification and income recognition; a restructured account under monitoring that misses an instalment
        //    of its new schedule at day-end has not performed satisfactorily (RestructureStatus)
        if (restructure != null && restructure.underMonitoring() && !restructure.defaulted()
                && demands.stream().anyMatch(d -> d.unpaid() && d.dueDate().isAfter(restructure.restructuredOn()) && !d.dueDate().isAfter(day))) {
            restructure = restructure.withDefaulted();
        }
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
        // 7. the last instalment was met from an advance: the loan is fully repaid
        if (status == Status.ACTIVE && !demands.isEmpty() && future.isEmpty() && principalOutstanding.signum() == 0 && dues(day).isEmpty()) {
            if (provisionHeld.signum() > 0) {
                lots.add(post.provision(provisionHeld.negate()));
                provisionHeld = ZERO;
            }
            if (suspense.signum() > 0) {
                lots.add(post.suspenseToIncome(suspense));
                suspense = ZERO;
                capitalisedSuspense = ZERO;
            }
            status = Status.CLOSED;
            return new Result(lots, "EOD " + day + " fully repaid; loan closed");
        }
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
        boolean upgradeBlocked = restructure != null && !restructure.upgradeAllowed(day, principalOutstanding);
        boolean held = classFloor != null && classFloorUntil != null && !day.isAfter(classFloorUntil);
        Delinquency.Status st = Delinquency.classify(day, dpd, assetClass, npaSince, arrears, upgradeBlocked || held);
        assetClass = st.assetClass();
        npaSince = st.npaSince();
        if (held && assetClass.ordinal() < classFloor.ordinal()) {       // a manual override holds the class; it never upgrades
            assetClass = classFloor;
            if (npaSince == null) npaSince = day;
        }
        if (!before.isNpa() && assetClass.isNpa()) lots.addAll(onBecomingNpa(post));
        if (before.isNpa() && !assetClass.isNpa()) {
            if (restructure != null && restructure.underMonitoring()) restructure = restructure.withUpgradedOn(day);
            BigDecimal free = freeSuspense();
            if (free.signum() > 0) {
                lots.add(post.suspenseToIncome(free));      // upgraded: remaining suspense (not-yet-due accrual) is income again
                suspense = suspense.subtract(free);
            }
        }
        return lots;
    }

    /** Suspense other than interest capitalised on restructuring (which is realised only with principal). */
    private BigDecimal freeSuspense() {
        return suspense.subtract(capitalisedSuspense).max(ZERO);
    }

    /**
     * Capitalised interest held in suspense becomes income in proportion to the principal repaid; all of it once the
     * principal is fully repaid.
     */
    private List<TransactionLot> releaseCapitalised(LoanPostings post, BigDecimal principalPaid, BigDecimal principalBefore) {
        if (capitalisedSuspense.signum() == 0 || principalPaid.signum() <= 0 || principalBefore.signum() <= 0) return List.of();
        BigDecimal release = principalOutstanding.signum() == 0 ? capitalisedSuspense
                : capitalisedSuspense.multiply(principalPaid).divide(principalBefore, 2, java.math.RoundingMode.HALF_UP).min(capitalisedSuspense);
        if (release.signum() <= 0) return List.of();
        capitalisedSuspense = capitalisedSuspense.subtract(release);
        suspense = suspense.subtract(release);
        return List.of(post.suspenseToIncome(release));
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
        BigDecimal move = unpaidInterest.add(unpaidPenal).subtract(freeSuspense());
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
                    demands.set(i, d.paid(a.component(), a.amount()));
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
        BigDecimal realised = split.total(Component.INTEREST).add(split.total(Component.PENAL)).min(freeSuspense());
        if (realised.signum() <= 0) return List.of();
        suspense = suspense.subtract(realised);
        return List.of(post.suspenseToIncome(realised));
    }

    /** Receipt against dues (EMI, overdue, charges). Anything above the dues is kept as an advance (US-055). */
    public Result pay(BigDecimal amount, LocalDate valueDate, LocalDate businessDate, String narration) {
        requireActive();
        boolean npa = assetClass.isNpa();
        BigDecimal principalBefore = principalOutstanding;
        Appropriation.Result split = Appropriation.allocate(dues(businessDate), amount, p.sequence(), p.mode());
        apply(split, npa);
        excess = excess.add(split.excess());
        LoanPostings post = postings(businessDate);
        List<TransactionLot> lots = new ArrayList<>();
        BigDecimal realised = split.total(Component.INTEREST).add(split.total(Component.PENAL)).min(freeSuspense());
        // the repayment lot moves realised NPA interest/penal from suspense to income itself
        lots.add(post.repayment(amount, split, npa && realised.signum() > 0, valueDate, narration));
        if (npa) suspense = suspense.subtract(realised);
        lots.addAll(releaseCapitalised(post, split.total(Component.PRINCIPAL), principalBefore));
        lots.addAll(classify(businessDate, post));
        if (principalOutstanding.signum() == 0 && future.isEmpty() && dues(businessDate).isEmpty()) {
            if (provisionHeld.signum() > 0) {
                lots.add(post.provision(provisionHeld.negate()));
                provisionHeld = ZERO;
            }
            if (suspense.signum() > 0) {          // closed while still NPA (e.g. restructured, upgrade not yet due): all realised
                lots.add(post.suspenseToIncome(suspense));
                suspense = ZERO;
                capitalisedSuspense = ZERO;
            }
            status = Status.CLOSED;               // fully repaid; any advance stays payable to the borrower
        }
        return new Result(lots, "Received " + plain(amount) + (split.excess().signum() > 0 ? ", advance " + plain(split.excess()) : "")
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
        if (!fullyDrawn()) {
            throw new IllegalStateException("prepayment is not available until the loan is fully disbursed or the undrawn amount is cancelled");
        }
        boolean monthlyEmi = monthlyEmi();
        if (!monthlyEmi) checkPrepayable(mode);
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
        BigDecimal principalBefore = principalOutstanding;
        principalOutstanding = principalOutstanding.subtract(amount);
        lots.addAll(releaseCapitalised(post, amount, principalBefore));
        // accruedNotDemanded already contains any interest carried by an earlier event in this period: carry it once
        carriedInterest = accruedNotDemanded;
        BigDecimal emiBefore = currentEmi();     // not the next row: it may carry broken-period interest
        LocalDate nextDue = future.get(0).dueDate();
        int remaining = future.size();
        List<Instalment> rebuilt;
        if (monthlyEmi) {
            LoanTerms t = new LoanTerms(principalOutstanding, rate, remaining, businessDate, nextDue,
                    RepaymentMethod.EQUATED, 0, ZERO, p.dayCount(), p.rounding(), false);
            rebuilt = ScheduleBuilder.build(t);
            if (mode == PrepaymentMode.REDUCE_TENURE) rebuilt = keepEmi(rebuilt, emiBefore, t);
        } else {
            rebuilt = rebuildOnMethod(mode, businessDate, emiBefore);
        }
        future = renumber(rebuilt, demands.size());
        return new Result(lots, "Prepaid " + plain(amount) + ", " + future.size() + " instalments left, next " + plain(future.get(0).instalment()));
    }

    /** A monthly equated loan on the daily-reducing basis (every loan booked before P2-6). */
    private boolean monthlyEmi() {
        return terms == null || (terms.method() == RepaymentMethod.EQUATED && terms.frequency() == Frequency.MONTHLY
                && terms.options().interestBasis() == LoanTerms.InterestBasis.DAILY_REDUCING && terms.options().fixedInstalment() == null);
    }

    private boolean equatedFamily() {
        return terms == null || terms.method() == RepaymentMethod.EQUATED || terms.method() == RepaymentMethod.STEP_EQUATED;
    }

    /** Refusals are raised before anything is posted. */
    private void checkPrepayable(PrepaymentMode mode) {
        if (terms.method() == RepaymentMethod.STRUCTURED) {
            throw new IllegalStateException("part-prepayment is not available on a structured schedule: its rows are set by the lender");
        }
        if (terms.method() == RepaymentMethod.FIXED_PRINCIPAL && terms.options().principalEvery() > 1) {
            throw new IllegalStateException("part-prepayment is not available when principal and interest fall due at different intervals");
        }
        if (mode == PrepaymentMode.REDUCE_TENURE && (!equatedFamily()
                || terms.options().interestBasis() != LoanTerms.InterestBasis.DAILY_REDUCING || terms.options().fixedInstalment() != null)) {
            throw new IllegalStateException("REDUCE_TENURE applies to equated loans on the daily-reducing basis; use REDUCE_EMI");
        }
    }

    /**
     * The schedule after a prepayment, on the loan's own method and frequency, keeping its due dates. A step loan
     * continues as a plain equated loan; a flat-rate loan continues on its equivalent reducing rate.
     */
    private List<Instalment> rebuildOnMethod(PrepaymentMode mode, LocalDate from, BigDecimal emiBefore) {
        LocalDate nextDue = future.get(0).dueDate();
        int remaining = future.size();
        if (terms.method() == RepaymentMethod.BULLET_TOTAL_INTEREST) {
            return ScheduleBuilder.build(terms.rescheduled(principalOutstanding, rate, 1, from, future.get(remaining - 1).dueDate(),
                    terms.method(), 0));
        }
        if (!equatedFamily()) {
            return ScheduleBuilder.build(terms.rescheduled(principalOutstanding, rate, remaining, from, nextDue, terms.method(), 0));
        }
        LoanTerms t = terms.rescheduled(principalOutstanding, rate, remaining, from, nextDue, RepaymentMethod.EQUATED, 0);
        List<Instalment> rebuilt = ScheduleBuilder.build(t);
        return mode == PrepaymentMode.REDUCE_TENURE ? keepEmi(rebuilt, emiBefore, t) : rebuilt;
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
        BigDecimal advance = excess.add(interestInAdvance);
        BigDecimal total = futurePrincipal.add(overdue).add(accrued).add(ch).add(feeTotal).subtract(advance);
        return new Quote(futurePrincipal, overdue, accrued, ch, fee, advance, total.max(ZERO));
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
            lots.add(post.feeCharge(q.foreclosureFee(), businessDate, true));
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
        BigDecimal fromExcess = excess.add(interestInAdvance);
        excess = ZERO;
        interestInAdvance = ZERO;
        Appropriation.Result split = Appropriation.allocate(dues(businessDate), amount.add(fromExcess), p.sequence(), p.mode());
        apply(split, npa);
        if (fromExcess.signum() > 0) {
            Appropriation.Result adv = Appropriation.allocate(List.of(new Appropriation.Due("X", businessDate, Component.PRINCIPAL,
                    fromExcess)), fromExcess, p.sequence(), p.mode());
            lots.add(post.excessAdjustment(adv));
            split = new Appropriation.Result(reduce(split, fromExcess), split.excess());
        }
        BigDecimal realised = split.total(Component.INTEREST).add(split.total(Component.PENAL)).min(freeSuspense());
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
        capitalisedSuspense = ZERO;
        status = Status.CLOSED;
        return new Result(lots, "Pre-closed for " + plain(amount));
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
        BigDecimal advance = interestInAdvance;          // broken-period interest deducted at disbursal is given back
        interestInAdvance = ZERO;
        BigDecimal unpaidCharges = charges.stream().map(ChargeRow::unpaid).reduce(ZERO, BigDecimal::add);
        BigDecimal interest = due.add(advance).subtract(principalOutstanding).subtract(unpaidCharges);
        BigDecimal trueUp = interest.subtract(accruedNotDemanded);
        if (trueUp.signum() != 0) lots.add(post.accrual(trueUp, false, businessDate));
        demands.add(new DemandRow(demands.size() + 1, businessDate, principalOutstanding, interest, ZERO, ZERO));
        future.clear();
        accruedNotDemanded = ZERO;
        Appropriation.Result split = Appropriation.allocate(dues(businessDate), amount.add(advance), p.sequence(), p.mode());
        apply(split, false);
        if (advance.signum() > 0) {
            Appropriation.Result adv = Appropriation.allocate(List.of(new Appropriation.Due("X", businessDate, Component.PRINCIPAL,
                    advance)), advance, p.sequence(), p.mode());
            lots.add(post.excessAdjustment(adv));
            split = new Appropriation.Result(reduce(split, advance), split.excess());
        }
        lots.add(post.repayment(amount, split, false, businessDate, "Cancellation"));
        status = Status.CANCELLED;
        return new Result(lots, "Cancelled in cooling-off; received " + plain(amount));
    }

    /** Principal + interest for the days used + any charge not deducted at disbursal. */
    public BigDecimal cancellationAmount() {
        BigDecimal unpaidCharges = charges.stream().map(ChargeRow::unpaid).reduce(ZERO, BigDecimal::add);
        return principalOutstanding.add(accruedNotDemanded.setScale(0, java.math.RoundingMode.HALF_UP)).add(unpaidCharges)
                .subtract(interestInAdvance);
    }

    // ------------------------------------------------------------------------------------------------ charges
    public Result chargeFee(String feeCode, BigDecimal base, LocalDate businessDate) {
        requireActive();
        FeeRule rule = p.fees().stream().filter(f -> f.code().equals(feeCode)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("fee " + feeCode + " is not defined on the product"));
        FeeRule.Charge c = rule.compute(base, p.supplierState(), p.recipientState(), Rounding.PAISE_HALF_UP);
        charges.add(new ChargeRow("C" + (++chargeSeq), c.code(), c.name(), Component.FEE, businessDate, c.total(), ZERO, ZERO));
        return new Result(List.of(postings(businessDate).feeCharge(c, businessDate)), "Charged " + c.name() + " " + plain(c.total()));
    }

    /** Waives an unpaid charge (fee or penal) in full or part (US-057). */
    public Result waiveCharge(String chargeId, BigDecimal amount, LocalDate businessDate) {
        requireActive();
        for (int i = 0; i < charges.size(); i++) {
            ChargeRow c = charges.get(i);
            if (!c.id().equals(chargeId)) continue;
            if (amount.signum() <= 0 || amount.compareTo(c.unpaid()) > 0) throw new IllegalArgumentException("waiver must be 0 < amount <= " + c.unpaid());
            charges.set(i, new ChargeRow(c.id(), c.code(), c.name(), c.kind(), c.date(), c.amount(), c.paid(), c.waived().add(amount)));
            boolean foreclosure = false;                   // waived from the head the charge was booked to
            if (c.kind() == Component.FEE) {
                // a fee charged with GST: the waiver is a credit note, so the tax part comes off the output tax and only
                // the taxable part off income - while the credit note can still be declared (CGST Act s.34(2))
                FeeRule rule = p.fees().stream().filter(f -> f.code().equals(c.code())).findFirst().orElse(null);
                foreclosure = rule != null && rule.event() == FeeRule.Event.PRECLOSURE;
                if (rule != null && rule.gstRatePercent().signum() > 0 && Gst.creditNoteInTime(c.date(), businessDate)) {
                    Gst.Inclusive parts = Gst.unbundle(amount, rule.gstRatePercent(), p.supplierState(), p.recipientState());
                    return new Result(List.of(postings(businessDate).feeWaiver(c.id(), c.name(), amount, parts, foreclosure)),
                            "Waived " + plain(amount) + " of " + c.name() + " (credit note: taxable " + parts.taxable().toPlainString()
                                    + ", GST " + parts.tax().total().toPlainString() + ")");
                }
            }
            boolean npa = assetClass.isNpa() && c.kind() == Component.PENAL;
            if (npa) suspense = suspense.subtract(amount.min(freeSuspense()));
            return new Result(List.of(postings(businessDate).waiver(c.kind(), amount, npa, foreclosure)), "Waived " + plain(amount) + " of " + c.name());
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

    // ------------------------------------------------------------------------------------------------ amendments (P2-3)
    /** Engine limit on instalments over a loan's life (as {@link LoanTerms}). */
    private static final int MAX_INSTALMENTS = 480;

    /** Figures of the amendment on today's state, without changing it (the preview uses the code that applies). */
    public Amendment.Effect previewAmendment(Amendment a, LocalDate businessDate) {
        return computeAmendment(a, businessDate);
    }

    /**
     * Amends rate, tenure, EMI or due day (see {@link Amendment}). The future schedule is rebuilt from the principal
     * not yet demanded; demands already raised stay as they are. Interest accrued since the last due date (at the
     * old terms) is folded into the next instalment's interest, and the day's accrual continues at the new rate, so
     * the next demand equals what was accrued (no interest lost or counted twice). No money moves: no postings.
     */
    public Result amend(Amendment a, LocalDate businessDate) {
        Amendment.Effect e = computeAmendment(a, businessDate);
        future = new ArrayList<>(e.scheduleAfter());
        rate = e.rateAfter();
        carriedInterest = ZERO;              // now inside the next instalment's interest
        return new Result(List.of(), describe(e));
    }

    private static String describe(Amendment.Effect e) {
        StringBuilder s = new StringBuilder(e.kind().name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT));
        if (e.rateBefore().compareTo(e.rateAfter()) != 0) {
            s.append(": rate ").append(e.rateBefore().stripTrailingZeros().toPlainString()).append("% → ")
                    .append(e.rateAfter().stripTrailingZeros().toPlainString()).append('%');
        }
        s.append("; EMI ").append(e.emiBefore().toPlainString()).append(" → ").append(e.emiAfter().toPlainString())
                .append("; instalments left ").append(e.remainingBefore()).append(" → ").append(e.remainingAfter())
                .append("; maturity ").append(e.maturityAfter());
        if (e.brokenPeriodInterest().signum() != 0) s.append("; broken-period interest ").append(e.brokenPeriodInterest().toPlainString());
        return s.toString();
    }

    private Amendment.Effect computeAmendment(Amendment a, LocalDate businessDate) {
        requireActive();
        requireMonthlyEmi("amendments");
        if (future.isEmpty()) throw new IllegalStateException("no instalments left to amend");
        LocalDate start = lastAccrualDate.plusDays(1);
        LocalDate nextDue = future.get(0).dueDate();
        if (!nextDue.isAfter(start)) throw new IllegalStateException("an instalment falls due today; amend after end of day");
        BigDecimal balance = future.stream().map(Instalment::principal).reduce(ZERO, BigDecimal::add);
        if (balance.signum() <= 0) throw new IllegalStateException("no principal left to reschedule");
        int moratorium = 0;
        while (moratorium < future.size() - 1 && future.get(moratorium).principal().signum() == 0) moratorium++;
        int remainingBefore = future.size();
        BigDecimal emiBefore = currentEmi();
        LocalDate maturityBefore = future.get(future.size() - 1).dueDate();
        BigDecimal interestBefore = future.stream().map(Instalment::interest).reduce(ZERO, BigDecimal::add).add(carriedInterest);
        int anchorDay = anchorDay();
        BigDecimal newRate = rate;
        BigDecimal emi = null;
        Integer n = null;
        LocalDate firstDue = nextDue;
        LocalDate basisEnd = null;
        switch (a.kind()) {
            case RATE_CHANGE -> {
                newRate = a.newRatePercent();
                switch (a.rateOption()) {
                    case KEEP_TENURE_CHANGE_EMI -> n = remainingBefore;
                    case KEEP_EMI_CHANGE_TENURE -> emi = emiBefore;
                    case CHANGE_BOTH -> {
                        if (a.remainingInstalments() != null) n = a.remainingInstalments(); else emi = a.newEmi();
                    }
                }
            }
            case TENURE_CHANGE -> {
                if (a.remainingInstalments() == remainingBefore) throw new IllegalArgumentException("the tenure is already " + remainingBefore + " instalments");
                n = a.remainingInstalments();
            }
            case EMI_CHANGE -> {
                if (a.newEmi().compareTo(emiBefore) == 0) throw new IllegalArgumentException("the EMI is already " + emiBefore.toPlainString());
                emi = a.newEmi();
            }
            case DUE_DAY_CHANGE -> {
                if (a.newDueDay() == anchorDay) {
                    throw new IllegalArgumentException("instalments already fall due on day " + a.newDueDay());
                }
                anchorDay = a.newDueDay();
                // the new date is the first one on the new day not before the old date: the borrower gets the extra
                // days and pays their interest with the next instalment
                firstDue = dueOn(nextDue, anchorDay);
                if (firstDue.isBefore(nextDue)) firstDue = dueOn(nextDue.plusMonths(1), anchorDay);
                n = remainingBefore;
                emi = emiBefore;
                basisEnd = nextDue;          // the principal repaid is what it would have been on the old date
            }
            case MATURITY_CHANGE -> {
                long months = java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(nextDue),
                        java.time.YearMonth.from(a.newMaturityDate())) + 1;
                if (months < 1) throw new IllegalArgumentException("the new maturity date cannot be before the next due date " + nextDue);
                if (months > MAX_INSTALMENTS) throw new IllegalArgumentException("the new maturity date is more than " + MAX_INSTALMENTS + " instalments away");
                if (months == remainingBefore) {
                    throw new IllegalArgumentException("the loan already matures in " + java.time.YearMonth.from(maturityBefore));
                }
                n = (int) months;
            }
        }
        if (n != null && n <= moratorium) {
            throw new IllegalArgumentException("the new tenure must be longer than the " + moratorium + " moratorium instalment(s) left");
        }
        int maxTotal = a.maxTenureMonths() == null ? MAX_INSTALMENTS : a.maxTenureMonths();
        if (n != null && n > remainingBefore && demands.size() + n > maxTotal) {
            throw new IllegalArgumentException(n + " instalments take the loan to " + (demands.size() + n)
                    + " months, beyond the maximum tenure of " + maxTotal + " months");
        }
        if (emi == null) emi = EmiCalculator.pmt(balance, newRate, n - moratorium, p.rounding());
        requireNoNegativeAmortisation(emi, balance, newRate);
        List<Instalment> rows = rows(balance, newRate, emi, n, moratorium, firstDue, anchorDay, start, accruedNotDemanded, basisEnd,
                MAX_INSTALMENTS - demands.size());
        int remainingAfter = rows.size();
        if (remainingAfter > remainingBefore && demands.size() + remainingAfter > maxTotal) {
            throw new IllegalArgumentException("the new schedule needs " + remainingAfter + " more instalments, which takes the loan to "
                    + (demands.size() + remainingAfter) + " months, beyond the maximum tenure of " + maxTotal + " months");
        }
        LocalDate maturityAfter = rows.get(rows.size() - 1).dueDate();
        BigDecimal overdue = demands.stream().filter(d -> !d.dueDate().isAfter(businessDate))
                .map(d -> d.principalUnpaid().add(d.interestUnpaid())).reduce(ZERO, BigDecimal::add);
        // a market rate reset may lower the EMI of a borrower in arrears; any other concession is a restructure
        boolean concession = maturityAfter.isAfter(maturityBefore) || (a.kind() != Amendment.Kind.RATE_CHANGE && emi.compareTo(emiBefore) < 0);
        if (overdue.signum() > 0 && concession) {
            throw new IllegalStateException("the loan has " + overdue.toPlainString() + " overdue; extending the tenure or lowering the EMI"
                    + " of a borrower in arrears is a restructure (RBI prudential framework): collect the dues first or restructure");
        }
        BigDecimal broken = ZERO;
        if (basisEnd != null) {
            broken = rows.get(0).interest().subtract(p.rounding().apply(periodInterest(balance, newRate, start, basisEnd).add(accruedNotDemanded)));
        }
        BigDecimal interestAfter = rows.stream().map(Instalment::interest).reduce(ZERO, BigDecimal::add);
        return new Amendment.Effect(a.kind(), balance, accruedNotDemanded, rate, newRate, emiBefore, emi, remainingBefore, remainingAfter,
                nextDue, rows.get(0).dueDate(), maturityBefore, maturityAfter, interestBefore, interestAfter, broken,
                List.copyOf(future), List.copyOf(rows));
    }

    private void requireNoNegativeAmortisation(BigDecimal emi, BigDecimal balance, BigDecimal ratePercent) {
        BigDecimal monthly = balance.multiply(ratePercent).divide(BigDecimal.valueOf(1200), 2, java.math.RoundingMode.HALF_UP);
        if (emi.compareTo(monthly.add(new BigDecimal("0.01"))) < 0) {
            throw new IllegalArgumentException("the EMI of " + emi.toPlainString() + " must be at least the monthly interest of "
                    + monthly.toPlainString() + " plus 1 paisa of principal; otherwise the loan would negatively amortise");
        }
    }

    /** The regular instalment: a middle row (the first can carry broken-period interest, the last absorbs rounding). */
    public BigDecimal currentEmi() {
        if (future.isEmpty()) return ZERO;
        return future.size() >= 2 ? future.get(future.size() - 2).instalment() : future.get(0).instalment();
    }

    /**
     * Day of month instalments fall due on (31 = month end), read from the next two due dates (two consecutive
     * months are enough to see past February); the last demand stands in when fewer are left.
     */
    private int anchorDay() {
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < Math.min(2, future.size()); i++) dates.add(future.get(i).dueDate());
        if (dates.size() < 2 && !demands.isEmpty()) dates.add(demands.get(demands.size() - 1).dueDate());
        if (dates.isEmpty()) dates.add(disbursedOn);
        int day = 0;
        for (LocalDate d : dates) day = Math.max(day, d.getDayOfMonth() == d.lengthOfMonth() ? 31 : d.getDayOfMonth());
        return day;
    }

    /** {@code day} in the month of {@code month}, or the month's last day when it is shorter. */
    private static LocalDate dueOn(LocalDate month, int day) {
        return month.withDayOfMonth(Math.min(day, month.lengthOfMonth()));
    }

    private BigDecimal periodInterest(BigDecimal balance, BigDecimal ratePercent, LocalDate from, LocalDate to) {
        return balance.multiply(ratePercent.divide(BigDecimal.valueOf(100), java.math.MathContext.DECIMAL128), java.math.MathContext.DECIMAL128)
                .multiply(p.dayCount().yearFraction(from, to), java.math.MathContext.DECIMAL128);
    }

    /**
     * Equated rows from {@code start}, numbered after the demands raised. The first row's interest includes
     * {@code accrued} (interest accrued since the period began, at the old terms). With {@code n} null the number of
     * instalments follows from the EMI. {@code basisEnd}: the first row repays the principal it would have repaid had
     * it fallen due on that date (due-day change: the broken-period interest is added on top of the EMI).
     */
    private List<Instalment> rows(BigDecimal balance, BigDecimal ratePercent, BigDecimal emi, Integer n, int moratorium,
                                  LocalDate firstDue, int anchorDay, LocalDate start, BigDecimal accrued, LocalDate basisEnd,
                                  int cap) {
        List<Instalment> rows = new ArrayList<>();
        BigDecimal bal = balance;
        LocalDate prev = start;
        LocalDate periodStart = demands.isEmpty() ? disbursedOn : demands.get(demands.size() - 1).dueDate();
        for (int k = 1; bal.signum() > 0; k++) {
            if (k > cap) {
                throw new IllegalArgumentException("an EMI of " + emi.toPlainString() + " would need more than " + cap
                        + " instalments; choose a higher EMI or a tenure");
            }
            LocalDate due = k == 1 ? firstDue : dueOn(firstDue.plusMonths(k - 1L), anchorDay);
            BigDecimal exact = periodInterest(bal, ratePercent, prev, due);
            BigDecimal interest = p.rounding().apply(k == 1 ? exact.add(accrued) : exact);
            BigDecimal principal;
            if (k <= moratorium) {
                principal = ZERO;
            } else if (n != null && k == n) {
                principal = bal;
            } else {
                BigDecimal basis = k == 1 && basisEnd != null
                        ? p.rounding().apply(periodInterest(bal, ratePercent, prev, basisEnd).add(accrued)) : interest;
                principal = emi.subtract(basis);
                if (principal.signum() <= 0) {
                    throw new IllegalArgumentException("the EMI of " + emi.toPlainString() + " does not cover the interest of "
                            + basis.toPlainString() + " due on " + due + "; the loan would negatively amortise");
                }
                if (principal.compareTo(bal) >= 0 || (n == null && bal.subtract(principal).compareTo(BigDecimal.ONE) < 0)) principal = bal;
            }
            LocalDate from = k == 1 ? periodStart : prev;
            rows.add(new Instalment(demands.size() + k, due, p.dayCount().days(from, due), bal, interest, principal,
                    principal.add(interest), bal.subtract(principal)));
            bal = bal.subtract(principal);
            prev = due;
        }
        return rows;
    }

    // ------------------------------------------------------------------------------------------------ restructure (P2-3)
    /**
     * Figures of up to three restructuring options on today's state, without changing it. Each option is applied to
     * a copy by the same code as {@link #restructure}, then the state is restored.
     */
    public List<RestructureSimulation> simulateRestructure(List<RestructureTerms> options, LocalDate day) {
        if (options == null || options.isEmpty() || options.size() > 3) throw new IllegalArgumentException("give one to three options");
        Snapshot s = snapshot();
        List<RestructureSimulation> out = new ArrayList<>();
        try {
            for (RestructureTerms t : options) {
                out.add(doRestructure(t, day).simulation());
                restore(s);
            }
        } finally {
            restore(s);
        }
        return List.copyOf(out);
    }

    public RestructureSimulation simulateRestructure(RestructureTerms t, LocalDate day) {
        return simulateRestructure(List.of(t), day).get(0);
    }

    /**
     * Restructures the loan (RBI Prudential Framework for Resolution of Stressed Assets, 7-Jun-2019): overdue
     * principal is rescheduled, overdue interest capitalised or kept as arrears, a principal moratorium and a new rate
     * and tenure set. A standard account is downgraded to sub-standard; an NPA keeps its class. The account is flagged
     * restructured and can be upgraded only after the specified period (see {@link RestructureStatus}).
     */
    public Result restructure(RestructureTerms t, LocalDate day) {
        return doRestructure(t, day).result();
    }

    private record Restructured(RestructureSimulation simulation, Result result) {}

    private Restructured doRestructure(RestructureTerms t, LocalDate day) {
        requireActive();
        requireMonthlyEmi("restructures");
        LocalDate start = lastAccrualDate.plusDays(1);
        if (!future.isEmpty() && !future.get(0).dueDate().isAfter(start)) {
            throw new IllegalStateException("an instalment falls due today; restructure after end of day");
        }
        if (principalOutstanding.signum() <= 0) throw new IllegalStateException("no principal left to restructure");
        int maxTotal = t.maxTenureMonths() == null ? MAX_INSTALMENTS : t.maxTenureMonths();
        if (demands.size() + t.remainingInstalments() > maxTotal) {
            throw new IllegalArgumentException(t.remainingInstalments() + " new instalments take the loan to "
                    + (demands.size() + t.remainingInstalments()) + " months, beyond the maximum tenure of " + maxTotal + " months");
        }
        AssetClass classBefore = assetClass;
        BigDecimal principalBefore = principalOutstanding;
        BigDecimal rateBefore = rate;
        BigDecimal emiBefore = currentEmi();
        int remainingBefore = future.size();
        LocalDate maturityBefore = !future.isEmpty() ? future.get(future.size() - 1).dueDate()
                : demands.isEmpty() ? disbursedOn : demands.get(demands.size() - 1).dueDate();
        BigDecimal overduePrincipal = demands.stream().map(DemandRow::principalUnpaid).reduce(ZERO, BigDecimal::add);
        BigDecimal overdueInterest = demands.stream().map(DemandRow::interestUnpaid).reduce(ZERO, BigDecimal::add);
        BigDecimal interestBefore = future.stream().map(Instalment::interest).reduce(ZERO, BigDecimal::add)
                .add(carriedInterest).add(overdueInterest);
        List<BigDecimal> oldFlows = new ArrayList<>();
        for (int i = 0; i < future.size(); i++) oldFlows.add(future.get(i).instalment().add(i == 0 ? carriedInterest : ZERO));
        BigDecimal npvBefore = RestructureSimulation.npv(overduePrincipal.add(overdueInterest), oldFlows, rateBefore);

        LoanPostings post = postings(day);
        List<TransactionLot> lots = new ArrayList<>();
        // 1. a standard account becomes sub-standard on restructuring; an NPA keeps its class
        if (!assetClass.isNpa()) {
            assetClass = AssetClass.SUBSTANDARD;
            npaSince = day;
            lots.addAll(onBecomingNpa(post));
        }
        // 2. overdue principal back into the schedule; overdue interest capitalised or kept as arrears
        BigDecimal capitalised = ZERO;
        boolean capitalise = t.overdueInterest() == RestructureTerms.OverdueInterest.CAPITALISE;
        for (int i = 0; i < demands.size(); i++) {
            DemandRow d = demands.get(i);
            BigDecimal pr = d.principalUnpaid();
            BigDecimal in = capitalise ? d.interestUnpaid() : ZERO;
            if (pr.signum() > 0 || in.signum() > 0) demands.set(i, d.restructured(pr, in));
            capitalised = capitalised.add(in);
        }
        if (capitalised.signum() > 0) {
            lots.add(post.interestCapitalisation(capitalised));
            capitalisedSuspense = capitalisedSuspense.add(capitalised.min(freeSuspense()));
            principalOutstanding = principalOutstanding.add(capitalised);
        }
        // 3. the new schedule over all principal outstanding
        BigDecimal balance = principalOutstanding;
        BigDecimal newRate = t.newRatePercent() == null ? rate : t.newRatePercent();
        int anchorDay = anchorDay();
        LocalDate firstDue;
        if (!future.isEmpty()) {
            firstDue = future.get(0).dueDate();
        } else {
            firstDue = dueOn(start, anchorDay);
            if (!firstDue.isAfter(start)) firstDue = dueOn(start.plusMonths(1), anchorDay);
        }
        int m = t.principalMoratoriumMonths();
        BigDecimal emi = EmiCalculator.pmt(balance, newRate, t.remainingInstalments() - m, p.rounding());
        List<Instalment> rows = rows(balance, newRate, emi, t.remainingInstalments(), m, firstDue, anchorDay, start, accruedNotDemanded,
                null, MAX_INSTALMENTS - demands.size());
        future = new ArrayList<>(rows);
        rate = newRate;
        carriedInterest = ZERO;
        // 4. flag and specified period: one year from the later of the first interest and first principal payment
        LocalDate firstPaymentDue = rows.get(Math.min(m, rows.size() - 1)).dueDate();
        restructure = new RestructureStatus(day, restructure == null ? 1 : restructure.count() + 1, classBefore, balance,
                firstPaymentDue, firstPaymentDue.plusYears(1), false, null);
        lots.addAll(classify(day, post));      // DPD from any arrears kept; the upgrade is blocked

        BigDecimal arrearsKept = overdueInterest.subtract(capitalised);
        BigDecimal npvAfter = RestructureSimulation.npv(arrearsKept, rows.stream().map(Instalment::instalment).toList(), rateBefore);
        BigDecimal interestAfter = rows.stream().map(Instalment::interest).reduce(ZERO, BigDecimal::add).add(arrearsKept);
        RestructureSimulation sim = new RestructureSimulation(t, classBefore, assetClass, principalBefore, overduePrincipal,
                overdueInterest, capitalised, arrearsKept, balance, rateBefore, newRate, emiBefore, emi, remainingBefore, rows.size(),
                maturityBefore, rows.get(rows.size() - 1).dueDate(), interestBefore, interestAfter, npvBefore, npvAfter,
                npvBefore.subtract(npvAfter), restructure.specifiedPeriodMinEnd(), List.copyOf(rows));
        String summary = "Restructured: " + classBefore + " → " + assetClass + ", principal " + balance.toPlainString()
                + (capitalised.signum() > 0 ? " (interest capitalised " + capitalised.toPlainString() + ")" : "")
                + ", rate " + newRate.stripTrailingZeros().toPlainString() + "%, " + rows.size() + " instalments"
                + (m > 0 ? " incl. " + m + " months' principal moratorium" : "") + ", EMI " + emi.toPlainString()
                + "; upgrade not before " + restructure.specifiedPeriodMinEnd();
        return new Restructured(sim, new Result(List.copyOf(lots), summary));
    }

    // ------------------------------------------------------------------------------------------------ tranches (P2-6, US-050)
    /** What a tranche does: the payout, the fees and the schedule after it. */
    public record TrancheEffect(int trancheNo, BigDecimal amount, List<FeeRule.Charge> deductedFees, List<FeeRule.Charge> chargedFees,
                                BigDecimal interestDeducted, BigDecimal netDisbursal, BigDecimal disbursedAfter, BigDecimal undrawnAfter,
                                boolean fullyDrawn, BigDecimal instalmentAfter, List<Instalment> scheduleAfter) {}

    private record Drawn(TrancheEffect effect, Result result) {}

    /**
     * Disburses a further tranche of the sanctioned amount. EVERY_DISBURSEMENT fees are charged on the tranche. The
     * schedule is rebuilt from today on the new balance: interest accrued so far on the old balance is carried into
     * the next demand. The last tranche of a pre-EMI loan starts the EMIs, for the full tenor, on the next due date.
     * No tranche is paid out while the account has unpaid dues or is NPA.
     */
    public Result drawTranche(BigDecimal amount, LocalDate businessDate) {
        return doDraw(amount, businessDate).result();
    }

    /** Figures of a tranche on today's state without changing it: the same code as {@link #drawTranche}. */
    public TrancheEffect simulateTranche(BigDecimal amount, LocalDate businessDate) {
        Snapshot s = snapshot();
        try {
            return doDraw(amount, businessDate).effect();
        } finally {
            restore(s);
        }
    }

    private Drawn doDraw(BigDecimal amount, LocalDate businessDate) {
        requireActive();
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("the disbursement amount must be positive");
        BigDecimal undrawn = undrawn();
        if (undrawn.signum() == 0) throw new IllegalStateException("the loan is fully disbursed");
        if (amount.compareTo(undrawn) > 0) {
            throw new IllegalArgumentException("at most " + undrawn.toPlainString() + " of the sanctioned amount remains to be disbursed");
        }
        if (future.isEmpty()) throw new IllegalStateException("no instalments are left: the loan has matured");
        LocalDate start = lastAccrualDate.plusDays(1);
        if (!future.get(0).dueDate().isAfter(start)) throw new IllegalStateException("an instalment falls due today; disburse after end of day");
        if (!dues(businessDate).isEmpty() || assetClass.isNpa()) {
            throw new IllegalStateException("no further disbursement while the account has unpaid dues or is NPA");
        }
        try {
            requireTrancheable(baseTerms());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage());
        }
        LoanPostings post = postings(businessDate);
        List<TransactionLot> lots = new ArrayList<>();
        List<FeeRule.Charge> deducted = new ArrayList<>();
        List<FeeRule.Charge> charged = new ArrayList<>();
        for (FeeRule f : p.fees()) {
            if (f.event() != FeeRule.Event.EVERY_DISBURSEMENT) continue;
            FeeRule.Charge c = f.compute(amount, p.supplierState(), p.recipientState(), Rounding.PAISE_HALF_UP);
            if (c.total().signum() == 0) continue;
            if (f.deductFromDisbursal()) deducted.add(c); else charged.add(c);
        }
        lots.add(post.disbursement(amount, deducted, ZERO, businessDate));
        for (FeeRule.Charge c : charged) {
            charges.add(new ChargeRow("C" + (++chargeSeq), c.code(), c.name(), Component.FEE, businessDate, c.total(), ZERO, ZERO));
            lots.add(post.feeCharge(c, businessDate));
        }
        principalOutstanding = principalOutstanding.add(amount);
        disbursedAmount = disbursedAmount.add(amount);
        BigDecimal fees = deducted.stream().map(FeeRule.Charge::total).reduce(ZERO, BigDecimal::add);
        BigDecimal net = amount.subtract(fees);
        int no = tranches.size() + 1;
        tranches.add(new TrancheRow(no, businessDate, amount, fees, ZERO, net));
        reschedule(start);
        TrancheEffect effect = new TrancheEffect(no, amount, List.copyOf(deducted), List.copyOf(charged), ZERO, net, disbursedAmount,
                undrawn(), fullyDrawn(), currentEmi(), List.copyOf(future));
        return new Drawn(effect, new Result(List.copyOf(lots), "Tranche " + no + ": disbursed " + amount.toPlainString() + ", net "
                + net.toPlainString() + "; " + (fullyDrawn() ? "fully disbursed" : "undrawn " + undrawn().toPlainString())));
    }

    /**
     * Cancels the part of the sanctioned amount not drawn: the sanctioned amount becomes the amount disbursed. A
     * pre-EMI loan starts its EMIs. Nothing is posted (an undrawn commitment is not on the balance sheet).
     */
    public Result cancelUndrawn(LocalDate businessDate) {
        requireActive();
        BigDecimal undrawn = undrawn();
        if (undrawn.signum() == 0) throw new IllegalStateException("nothing is undrawn");
        applySanction(disbursedAmount);
        return new Result(List.of(), "Undrawn " + undrawn.toPlainString() + " cancelled; sanctioned amount is now " + sanctioned.toPlainString());
    }

    /** Before/after of a change to the sanctioned amount (US-059). */
    public record SanctionEffect(BigDecimal sanctionedBefore, BigDecimal sanctionedAfter, BigDecimal disbursed, BigDecimal undrawnAfter,
                                 boolean topUp) {}

    /**
     * Checks a change of the sanctioned amount and gives its figures, without changing anything.
     * <ul>
     *   <li>A reduction may only take away undrawn amount: never below what is disbursed.</li>
     *   <li>An increase (top-up in the same account) needs a STANDARD account with no unpaid dues that is not a
     *       restructured account under monitoring - new money to a borrower in arrears would be evergreening - and
     *       a method that can be re-scheduled on a tranche. The extra amount is then disbursed as a tranche.</li>
     * </ul>
     */
    public SanctionEffect previewSanctionChange(BigDecimal newAmount, LocalDate businessDate) {
        requireActive();
        if (newAmount == null || newAmount.signum() <= 0) throw new IllegalArgumentException("the sanctioned amount must be positive");
        if (newAmount.compareTo(sanctioned) == 0) throw new IllegalArgumentException("the sanctioned amount is already " + sanctioned.toPlainString());
        if (newAmount.compareTo(disbursedAmount) < 0) {
            throw new IllegalArgumentException("the sanctioned amount cannot go below the " + disbursedAmount.toPlainString()
                    + " already disbursed; only the undrawn amount can be reduced");
        }
        boolean topUp = newAmount.compareTo(sanctioned) > 0;
        if (topUp) {
            if (!dues(businessDate).isEmpty() || assetClass != AssetClass.STANDARD || (restructure != null && restructure.underMonitoring())) {
                throw new IllegalStateException("a top-up needs a standard account with no unpaid dues that is not under"
                        + " post-restructuring monitoring; this account is " + assetClass + " with dues of "
                        + overdueAmount(businessDate).toPlainString());
            }
            if (future.isEmpty()) throw new IllegalStateException("no instalments are left: the loan has matured");
            try {
                requireTrancheable(baseTerms());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("a top-up is disbursed as a tranche: " + e.getMessage());
            }
        }
        return new SanctionEffect(sanctioned, newAmount, disbursedAmount, newAmount.subtract(disbursedAmount), topUp);
    }

    /** Applies {@link #previewSanctionChange}. No money moves: a top-up is paid out by {@link #drawTranche}. */
    public Result changeSanction(BigDecimal newAmount, LocalDate businessDate) {
        SanctionEffect e = previewSanctionChange(newAmount, businessDate);
        if (e.topUp()) {
            preEmi = false;                 // a top-up of a running loan is re-scheduled over the instalments left
            sanctioned = newAmount;
        } else {
            applySanction(newAmount);
        }
        return new Result(List.of(), "Sanctioned amount " + e.sanctionedBefore().toPlainString() + " → " + e.sanctionedAfter().toPlainString()
                + "; undrawn " + e.undrawnAfter().toPlainString());
    }

    private void applySanction(BigDecimal newAmount) {
        boolean startEmi = preEmi && newAmount.compareTo(disbursedAmount) == 0;
        if (startEmi) {
            if (future.isEmpty()) throw new IllegalStateException("no instalments are left");
            LocalDate start = lastAccrualDate.plusDays(1);
            if (!future.get(0).dueDate().isAfter(start)) throw new IllegalStateException("an instalment falls due today; try after end of day");
            sanctioned = newAmount;
            reschedule(start);
        } else {
            sanctioned = newAmount;
        }
    }

    /** Terms to re-schedule on: as sanctioned, or a monthly equated loan for state stored before P2-6. */
    private LoanTerms baseTerms() {
        if (terms != null) return terms;
        int periods = Math.min(MAX_INSTALMENTS, Math.max(1, demands.size() + future.size()));
        return new LoanTerms(disbursedAmount, rate, periods, disbursedOn, null, RepaymentMethod.EQUATED, 0, ZERO, p.dayCount(),
                p.rounding(), false);
    }

    private int leadingInterestOnly() {
        int n = 0;
        while (n < future.size() - 1 && future.get(n).principal().signum() == 0) n++;
        return n;
    }

    /**
     * Rebuilds the future schedule from {@code from} on the principal outstanding, keeping the due dates. Interest
     * accrued so far in the period is carried into the next demand.
     */
    private void reschedule(LocalDate from) {
        LoanTerms base = baseTerms();
        carriedInterest = accruedNotDemanded;
        LocalDate nextDue = future.get(0).dueDate();
        int remaining = future.size();
        LoanTerms t;
        if (base.method() == RepaymentMethod.BULLET_TOTAL_INTEREST) {
            t = base.rescheduled(principalOutstanding, rate, 1, from, future.get(remaining - 1).dueDate(), base.method(), 0);
        } else if (base.method() == RepaymentMethod.EQUATED) {
            int interestOnly = leadingInterestOnly();
            if (preEmi && fullyDrawn()) {            // the EMIs start: full tenor from the next due date
                remaining = base.tenorMonths();
                interestOnly = base.moratoriumMonths();
                preEmi = false;
            }
            t = base.rescheduled(principalOutstanding, rate, remaining, from, nextDue, base.method(), interestOnly);
        } else {
            t = base.rescheduled(principalOutstanding, rate, remaining, from, nextDue, base.method(), 0);
        }
        future = renumber(ScheduleBuilder.build(t), demands.size());
    }

    /**
     * Pre-EMI: while the loan is not fully drawn each instalment is interest only. When one has been demanded the
     * EMI schedule moves one period out, so there is always one more interest-only instalment ahead.
     */
    private void rollPreEmi(LocalDate from) {
        if (!preEmi || fullyDrawn() || future.isEmpty() || terms == null) return;
        int needed = terms.moratoriumMonths() + 1;
        if (leadingInterestOnly() >= needed) return;
        BigDecimal balance = future.stream().map(Instalment::principal).reduce(ZERO, BigDecimal::add);
        if (balance.signum() <= 0) return;
        LoanTerms t = terms.rescheduled(balance, rate, terms.tenorMonths() + 1, from, future.get(0).dueDate(), terms.method(), needed);
        future = renumber(ScheduleBuilder.build(t), demands.size());
    }

    private void requireMonthlyEmi(String what) {
        if (!fullyDrawn()) {
            throw new IllegalStateException(what + " need the loan fully disbursed: disburse or cancel the undrawn " + undrawn().toPlainString());
        }
        if (!monthlyEmi()) {
            throw new IllegalStateException(what + " apply to monthly equated (EMI) loans on the daily-reducing basis; this loan is "
                    + terms.method() + ", " + terms.frequency() + ", " + terms.options().interestBasis());
        }
    }

    // ------------------------------------------------------------------------------------------------ asset class override (US-059)
    /**
     * Manual override of the asset class ("NPA parameters"). Under the IRACP norms an account is upgraded only when
     * its arrears are cleared, so an override can never make the class better: it may <b>downgrade</b> the account to
     * an NPA class (for example on evidence of fraud, erosion of security or a supervisor's direction) or <b>hold</b>
     * it at its present NPA class, until {@code until} (inclusive). While it holds, the day-end classification can
     * make the class worse but not better; after it expires the normal rules apply again (upgrade only at zero
     * arrears). LOSS is permanent. A downgrade from a performing class reverses unrealised income into suspense.
     */
    public Result overrideAssetClass(AssetClass floor, LocalDate until, LocalDate businessDate) {
        requireActive();
        Objects.requireNonNull(floor, "floor");
        if (!floor.isNpa()) throw new IllegalArgumentException("an override sets an NPA class: SUBSTANDARD, DOUBTFUL1, DOUBTFUL2, DOUBTFUL3 or LOSS");
        if (until == null || !until.isAfter(businessDate)) throw new IllegalArgumentException("the override needs an expiry date after " + businessDate);
        if (floor.ordinal() < assetClass.ordinal()) {
            throw new IllegalStateException("the account is " + assetClass + "; an override may only downgrade or hold the asset class,"
                    + " never upgrade it (IRACP: an NPA is upgraded only when its arrears are cleared)");
        }
        boolean heldNow = classFloor != null && classFloorUntil != null && !businessDate.isAfter(classFloorUntil);
        if (heldNow && (floor.ordinal() < classFloor.ordinal() || until.isBefore(classFloorUntil))) {
            throw new IllegalStateException("the account is held at " + classFloor + " until " + classFloorUntil
                    + "; a new override cannot be weaker or expire earlier");
        }
        AssetClass before = assetClass;
        List<TransactionLot> lots = new ArrayList<>();
        if (floor.ordinal() > assetClass.ordinal()) {
            assetClass = floor;
            if (npaSince == null) npaSince = businessDate;
            if (!before.isNpa()) lots.addAll(onBecomingNpa(postings(businessDate)));
        }
        classFloor = floor;
        classFloorUntil = until;
        return new Result(List.copyOf(lots), (before == floor ? "Asset class held at " + floor : "Asset class " + before + " → " + floor)
                + " by override until " + until);
    }

    /**
     * Ends a manual override before its expiry ("un-mark"). Refused while the account has unpaid dues: under the
     * IRACP norms an NPA is upgraded only when all arrears of interest and principal are paid, and an un-mark must not
     * be a way around that. Nothing is posted and the class does not change here: the next day-end classifies the
     * account by the normal rules (which upgrade it, release the suspense and write back the provision). LOSS stays.
     */
    public Result releaseAssetClassOverride(LocalDate businessDate) {
        requireActive();
        boolean heldNow = classFloor != null && classFloorUntil != null && !businessDate.isAfter(classFloorUntil);
        if (!heldNow) throw new IllegalStateException("the account has no asset-class override in force");
        if (classFloor == AssetClass.LOSS) throw new IllegalStateException("a LOSS classification is permanent and cannot be un-marked");
        if (!dues(businessDate).isEmpty()) {
            throw new IllegalStateException("the override cannot be released while dues of " + overdueAmount(businessDate).toPlainString()
                    + " are unpaid (IRACP: an NPA is upgraded only when its arrears are cleared)");
        }
        AssetClass was = classFloor;
        classFloor = null;
        classFloorUntil = null;
        return new Result(List.of(), "Asset-class override (" + was + ") released; the account is classified by the normal rules"
                + " from the next day-end");
    }

    // ------------------------------------------------------------------------------------------------ simulations (US-060)
    /**
     * Runs {@code action} on the loan as it will stand at the start of {@code onDate} - the day-ends up to the day
     * before are run first (demands, accrual, penal charges, classification) - and then puts the state back.
     * Nothing is posted: the lots are thrown away.
     */
    public <T> T dryRun(LocalDate onDate, Provisioning.Rates rates, java.util.function.Function<LoanAccount, T> action) {
        Snapshot s = snapshot();
        try {
            for (LocalDate d = lastAccrualDate.plusDays(1); d.isBefore(onDate); d = d.plusDays(1)) endOfDay(d, rates);
            return action.apply(this);
        } finally {
            restore(s);
        }
    }

    /** What a receipt would be appropriated to. */
    public record ReceiptSimulation(LocalDate onDate, BigDecimal amount, List<Appropriation.Allocation> allocations, BigDecimal principal,
                                    BigDecimal interest, BigDecimal fees, BigDecimal penal, BigDecimal advance, BigDecimal duesBefore,
                                    BigDecimal duesAfter, BigDecimal principalOutstandingAfter, int dpdAfter, AssetClass assetClassAfter,
                                    Status statusAfter) {}

    /** A receipt of {@code amount} on {@code onDate} (today or later), by the code that posts it; nothing changes. */
    public ReceiptSimulation simulateReceipt(BigDecimal amount, LocalDate onDate, Provisioning.Rates rates) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        return dryRun(onDate, rates, a -> {
            a.requireActive();
            BigDecimal before = a.overdueAmount(onDate);
            Appropriation.Result split = Appropriation.allocate(a.dues(onDate), amount, a.p.sequence(), a.p.mode());
            a.pay(amount, onDate, onDate, "Simulation");
            return new ReceiptSimulation(onDate, amount, split.allocations(), split.total(Component.PRINCIPAL),
                    split.total(Component.INTEREST), split.total(Component.FEE), split.total(Component.PENAL), split.excess(), before,
                    a.overdueAmount(onDate), a.principalOutstanding, a.dpd, a.assetClass, a.status);
        });
    }

    /** What a part-prepayment would do. */
    public record PrepaymentSimulation(LocalDate onDate, BigDecimal amount, PrepaymentMode mode, BigDecimal feeCharged,
                                       BigDecimal principalOutstandingAfter, BigDecimal instalmentBefore, BigDecimal instalmentAfter,
                                       int remainingBefore, int remainingAfter, List<Instalment> scheduleAfter) {}

    public PrepaymentSimulation simulatePrepayment(BigDecimal amount, PrepaymentMode mode, LocalDate onDate, Provisioning.Rates rates) {
        if (amount == null) throw new IllegalArgumentException("amount is required");
        return dryRun(onDate, rates, a -> {
            BigDecimal emiBefore = a.currentEmi();
            int remainingBefore = a.future.size();
            BigDecimal chargesBefore = a.charges.stream().map(ChargeRow::amount).reduce(ZERO, BigDecimal::add);
            a.prepay(amount, mode, onDate);
            BigDecimal fee = a.charges.stream().map(ChargeRow::amount).reduce(ZERO, BigDecimal::add).subtract(chargesBefore);
            return new PrepaymentSimulation(onDate, amount, mode, fee, a.principalOutstanding, emiBefore, a.currentEmi(), remainingBefore,
                    a.future.size(), List.copyOf(a.future));
        });
    }

    /** Pre-closure amount on {@code onDate} (today or later). */
    public Quote simulatePreclosure(LocalDate onDate, Provisioning.Rates rates) {
        return dryRun(onDate, rates, a -> {
            a.requireActive();
            return a.preclosureQuote(onDate);
        });
    }

    // ------------------------------------------------------------------------------------------------ views
    public LoanTerms terms() { return terms; }
    public BigDecimal sanctioned() { return sanctioned; }
    public BigDecimal disbursedAmount() { return disbursedAmount; }
    public BigDecimal undrawn() { return sanctioned.subtract(disbursedAmount).max(ZERO); }
    public boolean fullyDrawn() { return undrawn().signum() == 0; }
    public boolean preEmi() { return preEmi; }
    public List<TrancheRow> tranches() { return List.copyOf(tranches); }
    public BigDecimal interestInAdvance() { return interestInAdvance; }
    public AssetClass classFloor() { return classFloor; }
    public LocalDate classFloorUntil() { return classFloorUntil; }
    public Status status() { return status; }
    public BigDecimal ratePercent() { return rate; }
    public RestructureStatus restructureStatus() { return restructure; }
    public BigDecimal capitalisedSuspense() { return capitalisedSuspense; }
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
