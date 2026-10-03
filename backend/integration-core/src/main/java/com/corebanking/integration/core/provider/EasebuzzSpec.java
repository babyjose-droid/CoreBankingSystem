package com.corebanking.integration.core.provider;

import java.util.List;

/**
 * <b>UNVERIFIED-AGAINST-PROVIDER.</b> Everything specific to Easebuzz lives in this class and in the two adapters
 * that use it ({@link EasebuzzCollectionGateway}, {@link EasebuzzPayoutGateway}).
 * <p>
 * These endpoint paths, field names and hash sequences were written from recollection of Easebuzz's public
 * integration documentation. They have <b>not</b> been checked against the provider's current documentation, and
 * no request built from them has ever been sent to an Easebuzz sandbox or production system: there are no
 * credentials yet (decision D-09). Before this adapter is enabled for any tenant, every constant here must be
 * confirmed against Easebuzz's current documentation and exercised in their sandbox, and the adapter contract
 * tests must be re-run against recorded sandbox responses. Until then the adapter is off unless the deployment
 * lists EASEBUZZ in {@code corebanking.integration.providers-enabled}.
 * <p>
 * Deliberately not implemented, because the details are not known with enough confidence to guess:
 * payout status enquiry, beneficiary validation, payout callbacks (their signature scheme), refunds and
 * settlement reports. The adapters refuse those calls instead of inventing a request.
 */
public final class EasebuzzSpec {

    public static final String CODE = "EASEBUZZ";

    // ---- payment gateway (collections) -- TO BE CONFIRMED ---------------------------------------------------
    public static final String PG_BASE_TEST = "https://testpay.easebuzz.in";
    public static final String PG_BASE_PRODUCTION = "https://pay.easebuzz.in";
    public static final String PG_INITIATE_PATH = "/payment/initiateLink";
    /** The payment page is {@code <base> + PG_PAY_PATH + <access key returned by initiate>}. */
    public static final String PG_PAY_PATH = "/pay/";
    public static final String PG_STATUS_URL_TEST = "https://testdashboard.easebuzz.in/transaction/v2.1/retrieve";
    public static final String PG_STATUS_URL_PRODUCTION = "https://dashboard.easebuzz.in/transaction/v2.1/retrieve";

    /** Request hash: SHA-512, lower-case hex, of these fields joined with '|'. */
    public static final List<String> PG_REQUEST_HASH_SEQUENCE = List.of("key", "txnid", "amount", "productinfo", "firstname",
            "email", "udf1", "udf2", "udf3", "udf4", "udf5", "udf6", "udf7", "udf8", "udf9", "udf10", "salt");
    /** Response / callback hash: SHA-512, lower-case hex, of these fields joined with '|'. */
    public static final List<String> PG_RESPONSE_HASH_SEQUENCE = List.of("salt", "status", "udf10", "udf9", "udf8", "udf7",
            "udf6", "udf5", "udf4", "udf3", "udf2", "udf1", "email", "firstname", "productinfo", "amount", "txnid", "key");
    /** Status enquiry hash. */
    public static final List<String> PG_STATUS_HASH_SEQUENCE = List.of("key", "txnid", "salt");

    public static final String PG_FIELD_STATUS = "status";
    public static final String PG_STATUS_SUCCESS = "success";
    public static final String PG_FIELD_PAYMENT_ID = "easepayid";
    public static final String PG_FIELD_TXN_ID = "txnid";
    public static final String PG_FIELD_AMOUNT = "amount";
    public static final String PG_FIELD_MODE = "mode";
    public static final String PG_FIELD_BANK_REF = "bank_ref_num";
    public static final String PG_FIELD_HASH = "hash";

    // ---- payouts ("Wire") -- TO BE CONFIRMED ----------------------------------------------------------------
    public static final String WIRE_BASE_PRODUCTION = "https://wire.easebuzz.in";
    /** No sandbox host is recorded here: it must come from the provider. The adapter requires the setting. */
    public static final String WIRE_QUICK_TRANSFER_PATH = "/api/v1/quick_transfers/initiate/";
    public static final String WIRE_AUTH_HEADER = "Authorization";
    public static final String WIRE_KEY_HEADER = "WIRE-API-KEY";
    /** Authorization hash: SHA-512, lower-case hex, of these fields joined with '|'. */
    public static final List<String> WIRE_HASH_SEQUENCE = List.of("key", "account_number", "ifsc", "upi_handle",
            "unique_request_number", "amount", "salt");

    private EasebuzzSpec() {}
}
