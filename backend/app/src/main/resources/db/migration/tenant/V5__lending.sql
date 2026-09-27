CREATE TABLE lending.loan_product (
    code                text PRIMARY KEY,
    name                text NOT NULL,
    repayment_method    text NOT NULL CHECK (repayment_method IN
                         ('EQUATED','BULLET_TOTAL_INTEREST','BULLET_PERIODIC_INTEREST',
                          'FIXED_PRINCIPAL_ACCRUED_INTEREST','OVERDRAFT')),
    min_amount          platform.money NOT NULL,
    max_amount          platform.money NOT NULL,
    min_tenor_months    int NOT NULL,
    max_tenor_months    int NOT NULL,
    min_rate            platform.rate NOT NULL,
    max_rate            platform.rate NOT NULL,
    day_count           text NOT NULL DEFAULT 'ACTUAL_365' CHECK (day_count IN ('ACTUAL_365','ACTUAL_360','ACTUAL_ACTUAL','THIRTY_360')),
    rounding            text NOT NULL DEFAULT 'RUPEE_HALF_UP',
    extra_day_on_first  boolean NOT NULL DEFAULT false,
    apr_basis           text NOT NULL DEFAULT 'IRR_BASIC_FEE_EX_GST',
    npa_days            int NOT NULL DEFAULT 90,
    writeoff_days       int,
    penal_charge_rate   platform.rate,                   -- penal CHARGES, not penal interest (RBI 2023)
    gl_principal        text NOT NULL REFERENCES ledger.gl_head(code),
    gl_interest_income  text NOT NULL REFERENCES ledger.gl_head(code),
    gl_interest_receivable text NOT NULL REFERENCES ledger.gl_head(code),
    status              text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','WITHDRAWN')),
    version             int NOT NULL DEFAULT 0,
    CHECK (min_amount > 0 AND min_amount <= max_amount),
    CHECK (min_tenor_months >= 1 AND min_tenor_months <= max_tenor_months),
    CHECK (min_rate >= 0 AND min_rate <= max_rate)
);

CREATE TABLE lending.loan_account (
    id               uuid PRIMARY KEY,
    loan_no          text NOT NULL UNIQUE,                -- number series LOAN
    customer_id      uuid NOT NULL REFERENCES customer.customer(id),
    product_code     text NOT NULL REFERENCES lending.loan_product(code),
    branch_code      text NOT NULL REFERENCES platform.branch(code),
    sanctioned_amount platform.money NOT NULL CHECK (sanctioned_amount > 0),
    rate             platform.rate NOT NULL,
    tenor_months     int NOT NULL CHECK (tenor_months > 0),
    open_date        date NOT NULL,
    first_due_date   date,
    emi              platform.money,
    status           text NOT NULL DEFAULT 'APPLIED'
                     CHECK (status IN ('APPLIED','SANCTIONED','ACTIVE','CLOSED','WRITTEN_OFF','CANCELLED')),
    asset_class      text NOT NULL DEFAULT 'STANDARD'
                     CHECK (asset_class IN ('STANDARD','SMA0','SMA1','SMA2','SUBSTANDARD','DOUBTFUL1','DOUBTFUL2','DOUBTFUL3','LOSS')),
    dpd              int NOT NULL DEFAULT 0 CHECK (dpd >= 0),
    kfs_accepted_at  timestamptz,                        -- KFS must be accepted before disbursal
    version          int NOT NULL DEFAULT 0,
    CONSTRAINT active_needs_kfs CHECK (status NOT IN ('ACTIVE','CLOSED','WRITTEN_OFF') OR kfs_accepted_at IS NOT NULL)
);
CREATE INDEX loan_customer ON lending.loan_account (customer_id);

CREATE TABLE lending.repayment_schedule (
    loan_id        uuid NOT NULL REFERENCES lending.loan_account(id),
    schedule_version int NOT NULL,                       -- new version on reschedule / prepayment
    instalment_no  int NOT NULL,
    due_date       date NOT NULL,
    opening_balance platform.money NOT NULL,
    principal_due  platform.money NOT NULL CHECK (principal_due >= 0),
    interest_due   platform.money NOT NULL CHECK (interest_due >= 0),
    principal_paid platform.money NOT NULL DEFAULT 0,
    interest_paid  platform.money NOT NULL DEFAULT 0,
    PRIMARY KEY (loan_id, schedule_version, instalment_no),
    CHECK (principal_paid <= principal_due AND interest_paid <= interest_due)
);
CREATE INDEX schedule_due ON lending.repayment_schedule (due_date);
