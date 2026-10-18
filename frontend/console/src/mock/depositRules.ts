/**
 * The two deposit rule sets as seeded by tenant migrations V28, V29 and V34, for the mock API. Generated from
 * deposits.rules_in_force() of a migrated bank and deposit-taking NBFC database; the backend's rows are the truth.
 */
export interface MockDepositRule {
  code: string;
  value: string;
  effectiveFrom: string;
  source: string;
  verified: boolean;
  note: string | null;
}

export const BANK_RULES: MockDepositRule[] = [
  {"code": "casa.inoperative-after-months", "value": "24", "effectiveFrom": "2026-04-01", "source": "RBI Inoperative Accounts / Unclaimed Deposits instructions of 1-Jan-2024 (bank summary)", "verified": false, "note": "No customer-induced transaction for over two years; per account"},
  {"code": "casa.review-after-months", "value": "12", "effectiveFrom": "2026-04-01", "source": "RBI Inoperative Accounts / Unclaimed Deposits instructions of 1-Jan-2024 (bank summary)", "verified": false, "note": "Annual review of accounts with no customer-induced transaction"},
  {"code": "current.interest-allowed", "value": "0", "effectiveFrom": "2026-04-01", "source": "RBI Commercial Banks Interest Rate on Deposits Directions 2025 (summary)", "verified": false, "note": "Current accounts bear no interest; the exception for a deceased depositor's balance is not built"},
  {"code": "demand-deposits-allowed", "value": "1", "effectiveFrom": "2026-04-01", "source": "Banking Regulation Act 1949 (banking includes deposits repayable on demand)", "verified": false, "note": null},
  {"code": "insured", "value": "1", "effectiveFrom": "2026-04-01", "source": "DICGC deposit insurance; the cover amount is not seeded", "verified": false, "note": "Confirm the cover amount before it is shown on receipts"},
  {"code": "nominees.max", "value": "4", "effectiveFrom": "2026-04-01", "source": "Banking Laws (Amendment) Act 2025, in force 1-Nov-2025 (PIB release via IIBF note)", "verified": false, "note": "Simultaneous with shares totalling 100%, or successive"},
  {"code": "savings.uniform-rate-up-to", "value": "100000", "effectiveFrom": "2026-04-01", "source": "RBI Commercial Banks Interest Rate on Deposits Directions 2025 (summary)", "verified": false, "note": "One rate on balances up to this amount; different rates allowed above"},
  {"code": "senior-citizen-age", "value": "60", "effectiveFrom": "2026-04-01", "source": "Income-tax practice (resident aged 60 or more in the tax year)", "verified": false, "note": null},
  {"code": "td.overdue-interest", "value": "1", "effectiveFrom": "2026-04-01", "source": "RBI Commercial Banks Interest Rate on Deposits Directions 2025 (summary)", "verified": false, "note": "A matured deposit left unpaid earns the savings rate or the contracted rate, whichever is lower"},
  {"code": "td.premature.mandatory-up-to", "value": "10000000", "effectiveFrom": "2026-04-01", "source": "RBI Commercial Banks Interest Rate on Deposits Directions 2025 (summary)", "verified": false, "note": "Term deposits of individuals up to this amount must allow premature withdrawal"},
  {"code": "tds.no-pan-rate-percent", "value": "20", "effectiveFrom": "2026-04-01", "source": "Carried over from s.206AA of the 1961 Act; not confirmed under the 2025 Act", "verified": false, "note": "Confirm before go-live"},
  {"code": "tds.on-savings-interest", "value": "0", "effectiveFrom": "2026-04-01", "source": "Listed among the no-deduction cases by one practitioner source", "verified": false, "note": "Decision D-17: off by default; confirm"},
  {"code": "tds.rate-percent", "value": "10", "effectiveFrom": "2026-04-01", "source": "Income-tax Act 2025, s.393(1) (practitioner rate charts, FY 2026-27)", "verified": false, "note": "Residents"},
  {"code": "tds.threshold", "value": "50000", "effectiveFrom": "2026-04-01", "source": "Income-tax Act 2025, s.393(1) (practitioner rate charts, FY 2026-27)", "verified": false, "note": "Aggregate interest in the tax year"},
  {"code": "tds.threshold-senior", "value": "100000", "effectiveFrom": "2026-04-01", "source": "Income-tax Act 2025, s.393(1) (practitioner rate charts, FY 2026-27)", "verified": false, "note": "Senior citizens"},
  {"code": "unclaimed-after-years", "value": "10", "effectiveFrom": "2026-04-01", "source": "Depositor Education and Awareness Fund (press report of the RBI mandate)", "verified": false, "note": "Balance not operated or claimed is transferred to the DEA Fund"},
];

export const NBFC_DEPOSIT_RULES: MockDepositRule[] = [
  {"code": "demand-deposits-allowed", "value": "0", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 15", "verified": true, "note": "No public deposit repayable on demand"},
  {"code": "insured", "value": "0", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 31(9)", "verified": true, "note": "Advertisement and form state that the deposits are not insured"},
  {"code": "nominees.max", "value": "1", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 50", "verified": true, "note": "One person, under the Banking Companies (Nomination) Rules, 1985"},
  {"code": "senior-citizen-age", "value": "60", "effectiveFrom": "2026-04-01", "source": "Income-tax practice (resident aged 60 or more in the tax year)", "verified": false, "note": null},
  {"code": "td.deposits-to-nof-multiple", "value": "1.5", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 17", "verified": true, "note": "Public deposits outstanding against net owned fund"},
  {"code": "td.emergency.max-amount", "value": "500000", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 37(2)", "verified": true, "note": null},
  {"code": "td.emergency.max-percent", "value": "50", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 37(2)", "verified": true, "note": "Of the principal, without interest, within three months"},
  {"code": "td.emergency.tiny-deposit-max", "value": "10000", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 7(19) and 37(1)", "verified": true, "note": "All deposits of the sole or first-named depositor together"},
  {"code": "td.loan-max-percent", "value": "75", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 36(2)", "verified": true, "note": "Loan against the deposit, after three months"},
  {"code": "td.loan-spread-percent", "value": "2", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 36(2)", "verified": true, "note": "Above the rate payable on the deposit"},
  {"code": "td.lock-in-months", "value": "3", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 35", "verified": true, "note": "No premature repayment and no loan against the deposit, except on death"},
  {"code": "td.maturity-notice-days", "value": "14", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 22", "verified": true, "note": "At least this many days before maturity"},
  {"code": "td.max-rate-percent", "value": "12.5", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 19", "verified": true, "note": "Ceiling on the rate of interest per annum"},
  {"code": "td.max-tenure-months", "value": "60", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 16", "verified": true, "note": null},
  {"code": "td.min-compounding-rest-months", "value": "1", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 19", "verified": true, "note": "Interest paid or compounded at rests not shorter than monthly"},
  {"code": "td.min-tenure-months", "value": "12", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 16", "verified": true, "note": null},
  {"code": "td.overdue-interest", "value": "0", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025 (no provision found for interest after maturity)", "verified": false, "note": "No interest after maturity unless the deposit is renewed; confirm"},
  {"code": "td.premature.no-interest-before-months", "value": "6", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 40", "verified": true, "note": "After 3 months but before 6 months: no interest"},
  {"code": "td.premature.rate-cut-from-minimum-percent", "value": "3", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 40", "verified": true, "note": "Below the minimum rate, when no rate exists for the period run"},
  {"code": "td.premature.rate-cut-percent", "value": "2", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 40", "verified": true, "note": "Below the rate for the period the deposit has run"},
  {"code": "td.renewal-needs-consent", "value": "1", "effectiveFrom": "2026-04-01", "source": "RBI NBFC Public Deposits Directions 2025, para 17", "verified": true, "note": "No renewal without the express and voluntary consent of the depositor"},
  {"code": "tds.no-pan-rate-percent", "value": "20", "effectiveFrom": "2026-04-01", "source": "Carried over from s.206AA of the 1961 Act; not confirmed under the 2025 Act", "verified": false, "note": "Confirm before go-live"},
  {"code": "tds.rate-percent", "value": "10", "effectiveFrom": "2026-04-01", "source": "Income-tax Act 2025, s.393(1) (practitioner rate charts, FY 2026-27)", "verified": false, "note": "Residents"},
  {"code": "tds.threshold", "value": "10000", "effectiveFrom": "2026-04-01", "source": "Income-tax Act 2025, s.393(1) (practitioner rate charts, FY 2026-27)", "verified": false, "note": "Payers other than banks, co-operative societies and post offices"},
  {"code": "tds.threshold-senior", "value": "10000", "effectiveFrom": "2026-04-01", "source": "Income-tax Act 2025, s.393(1) (practitioner rate charts, FY 2026-27)", "verified": false, "note": "No higher threshold for senior citizens with this kind of payer"},
];
