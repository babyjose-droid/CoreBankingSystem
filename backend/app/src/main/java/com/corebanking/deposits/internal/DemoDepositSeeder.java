package com.corebanking.deposits.internal;

import com.corebanking.platform.DemoTenantSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * LOCAL DEVELOPMENT ONLY (corebanking.bootstrap.demo-tenant=true): deposit products for the demo tenants that take
 * deposits, so an account can be opened and a deposit booked straight away. A bank gets a savings, a current, two
 * fixed deposit and a recurring deposit product; a deposit-taking NBFC gets two fixed deposit products and a recurring
 * deposit inside its rule set (12 to 60 months, under the rate ceiling). A tenant that takes no deposits gets nothing. The rates, the premature penalty
 * (1 point) and the minimum period (7 days) are demo figures, not regulatory values and not anyone's rate card. A
 * real tenant's products go through maker-checker.
 */
@Component
@ConditionalOnProperty(name = "corebanking.bootstrap.demo-tenant", havingValue = "true")
class DemoDepositSeeder implements DemoTenantSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDepositSeeder.class);

    static final String BANK_SAVINGS = """
            INSERT INTO deposits.product (code, name, family, product_type, day_count, min_balance, interest_bands, band_mode, interest_posting_months,
                                          cheque_facility, tds_applicable, gl_liability, gl_interest_expense, effective_from, updated_by)
            SELECT 'SB01', 'Demo savings account (demo data)', 'CASA', 'SAVINGS', 'ACTUAL_365', 1000,
                   '[{"fromBalance": 0, "ratePercent": 2.75}, {"fromBalance": 100000, "ratePercent": 3.25}]'::jsonb, 'INCREMENTAL', 3, true, false,
                   '2401', '5108', (SELECT business_date FROM platform.business_day WHERE id = 1), 'bootstrap'
             WHERE NOT EXISTS (SELECT 1 FROM deposits.product WHERE code = 'SB01')
            """;

    static final String BANK_CURRENT = """
            INSERT INTO deposits.product (code, name, family, product_type, min_balance, cheque_facility, tds_applicable, gl_liability, effective_from, updated_by)
            SELECT 'CA01', 'Demo current account (demo data)', 'CASA', 'CURRENT', 10000, true, false, '2402',
                   (SELECT business_date FROM platform.business_day WHERE id = 1), 'bootstrap'
             WHERE NOT EXISTS (SELECT 1 FROM deposits.product WHERE code = 'CA01')
            """;

    static final String BANK_FIXED = """
            INSERT INTO deposits.product (code, name, family, product_type, day_count, min_amount, max_amount, min_tenure_months, min_tenure_days,
                                          max_tenure_months, max_tenure_days, interest_period_months, rate_card, add_ons, premature_allowed,
                                          premature_penalty_percent, premature_min_days, auto_renewal_allowed, gl_liability, gl_interest_expense,
                                          effective_from, updated_by)
            SELECT v.code, v.name, 'TERM_DEPOSIT', v.product_type, 'ACTUAL_365', 5000, NULL, v.min_months, v.min_days, 120, 0, v.period,
                   v.card::jsonb, '[{"code": "SENIOR_CITIZEN", "percent": 0.5}]'::jsonb, true, 1, 7, true, '2403', '5107',
                   (SELECT business_date FROM platform.business_day WHERE id = 1), 'bootstrap'
              FROM (VALUES
                ('FDC01', 'Demo fixed deposit, cumulative (demo data)', 'FD_CUMULATIVE', 6, 0, 3,
                 '[{"fromAmount": 0, "fromMonths": 6, "fromDays": 0, "ratePercent": 6.25}, {"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 7.1},
                   {"fromAmount": 0, "fromMonths": 36, "fromDays": 0, "ratePercent": 7.25},
                   {"fromAmount": 500000, "fromMonths": 6, "fromDays": 0, "ratePercent": 6.35}, {"fromAmount": 500000, "fromMonths": 12, "fromDays": 0, "ratePercent": 7.2},
                   {"fromAmount": 500000, "fromMonths": 36, "fromDays": 0, "ratePercent": 7.35}]'),
                ('FDP01', 'Demo fixed deposit, monthly payout (demo data)', 'FD_PAYOUT', 0, 46, 1,
                 '[{"fromAmount": 0, "fromMonths": 0, "fromDays": 46, "ratePercent": 5}, {"fromAmount": 0, "fromMonths": 6, "fromDays": 0, "ratePercent": 6},
                   {"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 6.9}, {"fromAmount": 0, "fromMonths": 36, "fromDays": 0, "ratePercent": 7}]')
              ) AS v(code, name, product_type, min_months, min_days, period, card)
             WHERE NOT EXISTS (SELECT 1 FROM deposits.product p WHERE p.code = v.code)
            """;

    static final String NBFC_FIXED = """
            INSERT INTO deposits.product (code, name, family, product_type, day_count, min_amount, max_amount, min_tenure_months, min_tenure_days,
                                          max_tenure_months, max_tenure_days, interest_period_months, rate_card, add_ons, premature_allowed,
                                          auto_renewal_allowed, gl_liability, gl_interest_expense, effective_from, updated_by)
            SELECT v.code, v.name, 'TERM_DEPOSIT', v.product_type, 'ACTUAL_365', 10000, NULL, 12, 0, 60, 0, v.period,
                   '[{"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 8.5}, {"fromAmount": 0, "fromMonths": 24, "fromDays": 0, "ratePercent": 8.75},
                     {"fromAmount": 0, "fromMonths": 36, "fromDays": 0, "ratePercent": 9}]'::jsonb,
                   '[{"code": "SENIOR_CITIZEN", "percent": 0.25}]'::jsonb, true, false, '2403', '5107',
                   (SELECT business_date FROM platform.business_day WHERE id = 1), 'bootstrap'
              FROM (VALUES ('FD01', 'Demo public deposit, cumulative (demo data)', 'FD_CUMULATIVE', 12),
                           ('FD02', 'Demo public deposit, quarterly payout (demo data)', 'FD_PAYOUT', 3)) AS v(code, name, product_type, period)
             WHERE NOT EXISTS (SELECT 1 FROM deposits.product p WHERE p.code = v.code)
            """;

    /** P4-5: a recurring deposit for each kind of tenant (demo figures; late charge Rs 1.50 per Rs 100 a month). */
    static final String BANK_RECURRING = """
            INSERT INTO deposits.product (code, name, family, product_type, day_count, min_amount, max_amount, min_tenure_months, min_tenure_days,
                                          max_tenure_months, max_tenure_days, interest_period_months, rate_card, add_ons, premature_allowed,
                                          premature_penalty_percent, premature_min_days, auto_renewal_allowed, gl_liability, gl_interest_expense,
                                          rd_late_charge_per_100, effective_from, updated_by)
            SELECT 'RD01', 'Demo recurring deposit (demo data)', 'TERM_DEPOSIT', 'RD', 'ACTUAL_365', 500, NULL, 6, 0, 120, 0, 3,
                   '[{"fromAmount": 0, "fromMonths": 6, "fromDays": 0, "ratePercent": 6.5}, {"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 7},
                     {"fromAmount": 0, "fromMonths": 36, "fromDays": 0, "ratePercent": 7.1}]'::jsonb,
                   '[{"code": "SENIOR_CITIZEN", "percent": 0.5}]'::jsonb, true, 1, 7, false, '2405', '5107', 1.5,
                   (SELECT business_date FROM platform.business_day WHERE id = 1), 'bootstrap'
             WHERE NOT EXISTS (SELECT 1 FROM deposits.product WHERE code = 'RD01') AND EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '2405')
            """;

    static final String NBFC_RECURRING = """
            INSERT INTO deposits.product (code, name, family, product_type, day_count, min_amount, max_amount, min_tenure_months, min_tenure_days,
                                          max_tenure_months, max_tenure_days, interest_period_months, rate_card, add_ons, premature_allowed,
                                          auto_renewal_allowed, gl_liability, gl_interest_expense, rd_late_charge_per_100, effective_from, updated_by)
            SELECT 'RD02', 'Demo recurring public deposit (demo data)', 'TERM_DEPOSIT', 'RD', 'ACTUAL_365', 500, NULL, 12, 0, 60, 0, 3,
                   '[{"fromAmount": 0, "fromMonths": 12, "fromDays": 0, "ratePercent": 8.25}, {"fromAmount": 0, "fromMonths": 24, "fromDays": 0, "ratePercent": 8.5}]'::jsonb,
                   '[]'::jsonb, true, false, '2405', '5107', 1.5, (SELECT business_date FROM platform.business_day WHERE id = 1), 'bootstrap'
             WHERE NOT EXISTS (SELECT 1 FROM deposits.product WHERE code = 'RD02') AND EXISTS (SELECT 1 FROM ledger.gl_head WHERE code = '2405')
            """;

    /** P4-6: a net owned fund for the demo deposit-taking NBFC, so its deposits can be held to 1.5 times it (demo figure). */
    static final String NBFC_NOF = """
            INSERT INTO deposits.net_owned_fund (as_of, amount, reference, recorded_by)
            SELECT DATE '2026-03-31', 50000000, 'Demo audited balance sheet 31-Mar-2026 (demo data)', 'bootstrap'
             WHERE NOT EXISTS (SELECT 1 FROM deposits.net_owned_fund)
            """;

    private final JdbcTemplate jdbc;

    DemoDepositSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void seed() {
        String kind = jdbc.queryForObject("SELECT deposits.rule_kind()", String.class);
        if (kind == null) return;                           // this tenant takes no deposits
        int added;
        if ("BANK".equals(kind)) {
            added = jdbc.update(BANK_SAVINGS) + jdbc.update(BANK_CURRENT) + jdbc.update(BANK_FIXED) + jdbc.update(BANK_RECURRING);
        } else {
            added = jdbc.update(NBFC_FIXED) + jdbc.update(NBFC_RECURRING) + jdbc.update(NBFC_NOF);
        }
        if (added > 0) log.warn("demo tenant: {} deposit product(s) added (demo data, local development only)", added);
    }
}
