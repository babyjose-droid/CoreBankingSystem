package com.corebanking.deposits;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** The two rule sets as seeded by tenant migration V28 (deposits_foundation_test.sql checks the same values). */
final class Fixtures {

    private Fixtures() {}

    static BigDecimal bd(String s) { return new BigDecimal(s); }

    static DepositRules bank() {
        Map<String, BigDecimal> v = new HashMap<>();
        v.put(DepositRules.DEMAND_DEPOSITS_ALLOWED, bd("1"));
        v.put(DepositRules.INSURED, bd("1"));
        v.put(DepositRules.NOMINEES_MAX, bd("4"));
        v.put(DepositRules.TD_PREMATURE_MANDATORY_UP_TO, bd("10000000"));
        v.put(DepositRules.SAVINGS_UNIFORM_RATE_UP_TO, bd("100000"));
        v.put(DepositRules.CASA_REVIEW_AFTER_MONTHS, bd("12"));
        v.put(DepositRules.CASA_INOPERATIVE_AFTER_MONTHS, bd("24"));
        v.put(DepositRules.UNCLAIMED_AFTER_YEARS, bd("10"));
        v.put(DepositRules.TDS_RATE, bd("10"));
        v.put(DepositRules.TDS_NO_PAN_RATE, bd("20"));
        v.put(DepositRules.TDS_THRESHOLD, bd("50000"));
        v.put(DepositRules.TDS_THRESHOLD_SENIOR, bd("100000"));
        v.put(DepositRules.TDS_ON_SAVINGS, bd("0"));
        v.put(DepositRules.SENIOR_CITIZEN_AGE, bd("60"));
        return new DepositRules(DepositRules.Kind.BANK, v);
    }

    static DepositRules nbfc() {
        Map<String, BigDecimal> v = new HashMap<>();
        v.put(DepositRules.DEMAND_DEPOSITS_ALLOWED, bd("0"));
        v.put(DepositRules.INSURED, bd("0"));
        v.put(DepositRules.NOMINEES_MAX, bd("1"));
        v.put(DepositRules.TD_MIN_TENURE_MONTHS, bd("12"));
        v.put(DepositRules.TD_MAX_TENURE_MONTHS, bd("60"));
        v.put(DepositRules.TD_MAX_RATE, bd("12.5"));
        v.put(DepositRules.TD_MIN_REST_MONTHS, bd("1"));
        v.put(DepositRules.TD_LOCK_IN_MONTHS, bd("3"));
        v.put(DepositRules.TD_NO_INTEREST_BEFORE_MONTHS, bd("6"));
        v.put(DepositRules.TD_PREMATURE_RATE_CUT, bd("2"));
        v.put(DepositRules.TD_PREMATURE_CUT_FROM_MINIMUM, bd("3"));
        v.put(DepositRules.TD_TINY_DEPOSIT_MAX, bd("10000"));
        v.put(DepositRules.TD_EMERGENCY_MAX_PERCENT, bd("50"));
        v.put(DepositRules.TD_EMERGENCY_MAX_AMOUNT, bd("500000"));
        v.put(DepositRules.TD_MATURITY_NOTICE_DAYS, bd("14"));
        v.put(DepositRules.TD_LOAN_MAX_PERCENT, bd("75"));
        v.put(DepositRules.TD_LOAN_SPREAD, bd("2"));
        v.put(DepositRules.TD_DEPOSITS_TO_NOF, bd("1.5"));
        v.put(DepositRules.TD_RENEWAL_NEEDS_CONSENT, bd("1"));
        v.put(DepositRules.TDS_RATE, bd("10"));
        v.put(DepositRules.TDS_NO_PAN_RATE, bd("20"));
        v.put(DepositRules.TDS_THRESHOLD, bd("10000"));
        v.put(DepositRules.TDS_THRESHOLD_SENIOR, bd("10000"));
        v.put(DepositRules.SENIOR_CITIZEN_AGE, bd("60"));
        return new DepositRules(DepositRules.Kind.NBFC_DEPOSIT, v);
    }
}
