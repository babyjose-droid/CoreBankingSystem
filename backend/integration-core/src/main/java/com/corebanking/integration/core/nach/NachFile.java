package com.corebanking.integration.core.nach;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The content of NACH debit files, independent of any bank's layout. */
public final class NachFile {

    /**
     * @param fileRef         our reference of the presentation file (a response names the file it answers)
     * @param utilityCode     the lender's NACH utility (user) code
     * @param sponsorBankCode the sponsor bank's code
     * @param settlementDate  the date the debits are to be settled
     */
    public record Header(String fileRef, String utilityCode, String sponsorBankCode, LocalDate settlementDate) {}

    /**
     * One debit to present.
     *
     * @param seq     position in the file, from 1
     * @param itemRef our reference of this presentation; the response must echo it
     * @param userRef what the borrower's bank statement shows (the loan number)
     */
    public record Debit(int seq, String itemRef, String umrn, String accountNumber, String ifsc, String accountType,
                        String holderName, BigDecimal amount, String userRef) {
        @Override
        public String toString() {
            return "Debit[" + seq + ", " + itemRef + ", " + amount + "]";       // never the account number
        }
    }

    public record Presentation(Header header, List<Debit> items) {
        public Presentation {
            items = List.copyOf(items);
        }

        public BigDecimal total() {
            return items.stream().map(Debit::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /**
     * The bank's answer for one debit.
     *
     * @param success    true: debited; false: returned (bounced)
     * @param returnCode the NPCI return reason code when bounced
     * @param bankRef    the bank's reference of the debit
     */
    public record Outcome(int seq, String itemRef, String umrn, BigDecimal amount, boolean success, String returnCode,
                          String bankRef) {}

    public record Response(Header header, List<Outcome> items) {
        public Response {
            items = List.copyOf(items);
        }

        public BigDecimal total() {
            return items.stream().map(Outcome::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public long successCount() {
            return items.stream().filter(Outcome::success).count();
        }

        public BigDecimal successTotal() {
            return items.stream().filter(Outcome::success).map(Outcome::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** The file is not in the layout, or its control totals do not match its rows. */
    public static final class FormatException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final int line;

        public FormatException(int line, String message) {
            super(line > 0 ? "line " + line + ": " + message : message);
            this.line = line;
        }

        public int line() {
            return line;
        }
    }

    private NachFile() {}
}
