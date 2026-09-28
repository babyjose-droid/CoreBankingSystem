package com.corebanking.platform;

/**
 * Applies an approved change. Each module registers one bean per entity type it owns (CUSTOMER, VOUCHER …).
 * Runs inside the approving transaction: if it throws, the approval is not recorded.
 */
public interface ApprovalApplier {

    /** Entity type handled, e.g. "VOUCHER". */
    String entityType();

    /**
     * Makes the change.
     *
     * @return a reference to what was created or changed (customer id, voucher number …), stored on the request
     */
    String apply(ApprovalRequest request);
}
