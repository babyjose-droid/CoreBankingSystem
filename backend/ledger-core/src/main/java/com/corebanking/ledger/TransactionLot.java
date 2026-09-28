package com.corebanking.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A balanced set of postings, committed atomically. Invariants (also enforced by the database, see
 * V3__ledger.sql):
 * <ol>
 *   <li>At least two lines.</li>
 *   <li>Σ DR = Σ CR per currency.</li>
 *   <li>Σ DR = Σ CR per branch per currency — the builder adds inter-branch (IBR) legs automatically
 *       so no module ever writes them by hand.</li>
 *   <li>Lots are immutable. Corrections are new lots ({@link #reversal}).</li>
 * </ol>
 */
public final class TransactionLot {

    private final UUID id;
    private final String type;
    private final LocalDate businessDate;
    private final LocalDate valueDate;
    private final String reference;
    private final UUID reverses;
    private final List<PostingLine> lines;

    private TransactionLot(UUID id, String type, LocalDate businessDate, LocalDate valueDate, String reference,
                           UUID reverses, List<PostingLine> lines) {
        this.id = id;
        this.type = type;
        this.businessDate = businessDate;
        this.valueDate = valueDate;
        this.reference = reference;
        this.reverses = reverses;
        this.lines = Collections.unmodifiableList(lines);
    }

    public UUID id() { return id; }
    public String type() { return type; }
    public LocalDate businessDate() { return businessDate; }
    public LocalDate valueDate() { return valueDate; }
    public String reference() { return reference; }
    public UUID reverses() { return reverses; }
    public List<PostingLine> lines() { return lines; }

    /** Builds the exact mirror of {@code original}: same legs, sides swapped, today's business date. */
    public static TransactionLot reversal(TransactionLot original, LocalDate businessDate, String reason) {
        List<PostingLine> mirrored = new ArrayList<>();
        for (PostingLine l : original.lines) mirrored.add(l.reversed());
        return new TransactionLot(UUID.randomUUID(), "REVERSAL", businessDate, original.valueDate,
                reason, original.id, mirrored);
    }

    /**
     * Rebuilds a lot that was already posted (read back from the ledger), e.g. to reverse it. The lines must
     * already include their inter-branch legs, so the lot must balance per branch and currency as stored.
     */
    public static TransactionLot restore(UUID id, String type, LocalDate businessDate, LocalDate valueDate,
                                         String reference, UUID reverses, List<PostingLine> lines) {
        if (lines.size() < 2) throw new LedgerException("a lot needs at least two lines");
        Map<String, BigDecimal> net = new TreeMap<>();
        for (PostingLine l : lines) net.merge(l.branch() + "|" + l.currency(), l.signed(), BigDecimal::add);
        net.forEach((k, v) -> {
            if (v.signum() != 0) throw new LedgerException("stored lot " + id + " does not balance for " + k);
        });
        return new TransactionLot(Objects.requireNonNull(id), type, businessDate, valueDate, reference, reverses,
                new ArrayList<>(lines));
    }

    public BigDecimal totalDebits() {
        BigDecimal t = BigDecimal.ZERO;
        for (PostingLine l : lines) if (l.side() == PostingLine.Side.DR) t = t.add(l.amount());
        return t;
    }

    public static Builder builder(String type, LocalDate businessDate) {
        return new Builder(type, businessDate);
    }

    public static final class Builder {
        private final String type;
        private final LocalDate businessDate;
        private LocalDate valueDate;
        private String reference;
        private String interBranchGl = "IBR";
        private final List<PostingLine> lines = new ArrayList<>();

        private Builder(String type, LocalDate businessDate) {
            this.type = Objects.requireNonNull(type);
            this.businessDate = Objects.requireNonNull(businessDate);
        }

        public Builder valueDate(LocalDate d) { this.valueDate = d; return this; }
        public Builder reference(String r) { this.reference = r; return this; }
        public Builder interBranchGl(String gl) { this.interBranchGl = gl; return this; }
        public Builder line(PostingLine l) { lines.add(l); return this; }

        public Builder debit(String branch, String gl, String account, BigDecimal amt, String narration) {
            return line(new PostingLine(branch, gl, account, PostingLine.Side.DR, amt, "INR", narration));
        }

        public Builder credit(String branch, String gl, String account, BigDecimal amt, String narration) {
            return line(new PostingLine(branch, gl, account, PostingLine.Side.CR, amt, "INR", narration));
        }

        public TransactionLot build() {
            if (lines.size() < 2) throw new LedgerException("a lot needs at least two lines");
            // 1. balanced per currency
            Map<String, BigDecimal> byCcy = new TreeMap<>();
            for (PostingLine l : lines) byCcy.merge(l.currency(), l.signed(), BigDecimal::add);
            byCcy.forEach((ccy, net) -> {
                if (net.signum() != 0) throw new LedgerException("lot not balanced in " + ccy + ": net " + net);
            });
            // 2. balanced per branch per currency: add IBR legs
            Map<String, BigDecimal> byBranch = new TreeMap<>();
            for (PostingLine l : lines) byBranch.merge(l.branch() + "|" + l.currency(), l.signed(), BigDecimal::add);
            List<PostingLine> all = new ArrayList<>(lines);
            byBranch.forEach((key, net) -> {
                if (net.signum() == 0) return;
                String[] k = key.split("\\|");
                PostingLine.Side side = net.signum() > 0 ? PostingLine.Side.CR : PostingLine.Side.DR;
                all.add(new PostingLine(k[0], interBranchGl, interBranchGl, side, net.abs(), k[1], "Inter-branch"));
            });
            return new TransactionLot(UUID.randomUUID(), type, businessDate,
                    valueDate == null ? businessDate : valueDate, reference, null, all);
        }
    }
}
