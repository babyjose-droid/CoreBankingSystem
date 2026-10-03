package com.corebanking.integration.core.provider;

import com.corebanking.integration.core.Lifecycle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Collecting a loan due through a payment gateway (US-073): a payment link or order the borrower pays. */
public interface CollectionGateway {

    /**
     * An order to create. The payer's name, mobile and e-mail are what a gateway needs to open its page; they are
     * passed through and never stored by us in clear.
     *
     * @param methods payment methods to offer (UPI, CARD, NETBANKING), passed through to the gateway; empty = all
     */
    record Order(String reference, BigDecimal amount, List<String> methods, String description, String payerName,
                 String payerMobile, String payerEmail, Instant expiresAt, String returnUrl) {
        public Order {
            methods = methods == null ? List.of() : List.copyOf(methods);
        }

        @Override
        public String toString() {
            return "Order[" + reference + ", " + amount + "]";
        }
    }

    record Created(String providerRef, String paymentUrl, Instant expiresAt) {}

    /** @param status CREATED (not paid yet), PAID, FAILED or EXPIRED */
    record Payment(Lifecycle.CollectionOrder status, String providerPaymentId, BigDecimal amount, String method,
                   Instant paidAt, String utr) {}

    Created create(Order order);

    /** The gateway's current view of an order; used by polling when no webhook arrived. */
    Payment status(String reference, String providerRef);
}
