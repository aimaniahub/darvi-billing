-- Darvi Billing / commercial SaaS planning schema. DO NOT DEPLOY AS-IS.
-- This is a normalized data-model sketch only; add migrations, secure RLS policies,
-- permissions, idempotency, transaction procedures and audits before use.

CREATE TABLE businesses (
  id uuid PRIMARY KEY,
  name text NOT NULL,
  gstin text,
  address text,
  state_code text,
  subscription_status text NOT NULL DEFAULT 'trial',
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE business_memberships (
  business_id uuid NOT NULL REFERENCES businesses(id),
  auth_user_id uuid NOT NULL,
  role text NOT NULL CHECK (role IN ('owner','admin','manager','cashier','accountant')),
  is_active boolean NOT NULL DEFAULT true,
  PRIMARY KEY (business_id, auth_user_id)
);

CREATE TABLE branches (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  name text NOT NULL,
  address text,
  UNIQUE (id,business_id)
);

CREATE TABLE products (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  sku text,
  name text NOT NULL,
  unit text NOT NULL,
  hsn_sac text,
  gst_rate numeric(5,2),
  sale_price numeric(14,2) NOT NULL,
  reorder_point numeric(14,3) DEFAULT 0,
  is_active boolean DEFAULT true,
  UNIQUE (business_id,sku)
);

CREATE TABLE customers (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  name text NOT NULL,
  phone text,
  email text,
  gstin text,
  address text
);

CREATE TABLE invoice_sequences (
  business_id uuid NOT NULL REFERENCES businesses(id),
  fiscal_year text NOT NULL,
  prefix text NOT NULL,
  next_number bigint NOT NULL DEFAULT 1,
  PRIMARY KEY (business_id,fiscal_year,prefix)
);

CREATE TABLE invoices (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  branch_id uuid REFERENCES branches(id),
  customer_id uuid REFERENCES customers(id),
  invoice_number text NOT NULL,
  invoice_date date NOT NULL,
  tax_type text CHECK (tax_type IN ('CGST_SGST','IGST','NO_GST')),
  subtotal numeric(14,2) NOT NULL,
  discount_total numeric(14,2) NOT NULL,
  tax_total numeric(14,2) NOT NULL,
  grand_total numeric(14,2) NOT NULL,
  status text NOT NULL,
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (business_id,invoice_number)
);

CREATE TABLE invoice_items (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  invoice_id uuid NOT NULL REFERENCES invoices(id),
  product_id uuid REFERENCES products(id),
  name_snapshot text NOT NULL,
  hsn_snapshot text,
  unit_snapshot text NOT NULL,
  quantity numeric(14,3) NOT NULL,
  unit_price numeric(14,2) NOT NULL,
  discount_rate numeric(5,2) NOT NULL DEFAULT 0,
  tax_rate numeric(5,2) NOT NULL DEFAULT 0,
  line_total numeric(14,2) NOT NULL
);

CREATE TABLE payments (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  invoice_id uuid NOT NULL REFERENCES invoices(id),
  amount numeric(14,2) NOT NULL,
  method text NOT NULL,
  reference text,
  paid_at timestamptz NOT NULL DEFAULT now(),
  recorded_by uuid
);

CREATE TABLE stock_movements (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  branch_id uuid REFERENCES branches(id),
  product_id uuid NOT NULL REFERENCES products(id),
  invoice_id uuid REFERENCES invoices(id),
  quantity_delta numeric(14,3) NOT NULL,
  reason text NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT now(),
  actor_id uuid
);

CREATE TABLE audit_events (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  actor_id uuid,
  action text NOT NULL,
  subject_id uuid,
  event_time timestamptz NOT NULL DEFAULT now(),
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);

-- REQUIRED BEFORE DEPLOYING:
-- 1. Build cross-tenant FK constraints (composite foreign keys) and consistent
--    business_id enforcement, including ownership across invoice items and payments.
-- 2. ENABLE / FORCE ROW LEVEL SECURITY ON EVERY TENANT TABLE and write policies
--    tied to verified auth identity + active business_memberships. No open policies.
-- 3. Define database functions for atomic invoice numbering, stock movement,
--    payment posting, void/credit notes and immutable audit records.
-- 4. Add access-control tests, authentication, secure API and encrypted backups.

-- v0.2 domain additions for production roadmap — still PLANNING ONLY
CREATE TABLE staff_profiles (
  id uuid PRIMARY KEY,
  employee_code text NOT NULL UNIQUE,
  auth_user_id uuid,
  display_name text NOT NULL,
  home_business_id uuid REFERENCES businesses(id),
  is_active boolean NOT NULL DEFAULT true
);
CREATE TABLE suppliers (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  name text NOT NULL,
  gstin text,
  phone text
);
CREATE TABLE purchase_bills (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  supplier_id uuid REFERENCES suppliers(id),
  bill_number text,
  bill_date date NOT NULL,
  taxable_total numeric(14,2) NOT NULL DEFAULT 0,
  tax_total numeric(14,2) NOT NULL DEFAULT 0,
  grand_total numeric(14,2) NOT NULL DEFAULT 0,
  created_by uuid,
  UNIQUE (business_id, bill_number)
);
CREATE TABLE purchase_items (
  id uuid PRIMARY KEY,
  purchase_bill_id uuid NOT NULL REFERENCES purchase_bills(id),
  business_id uuid NOT NULL REFERENCES businesses(id),
  product_id uuid NOT NULL REFERENCES products(id),
  quantity numeric(14,3) NOT NULL,
  purchase_rate numeric(14,2) NOT NULL,
  tax_rate numeric(5,2) DEFAULT 0
);
CREATE TABLE business_expenses (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  expense_date date NOT NULL,
  expense_category text NOT NULL,
  amount numeric(14,2) NOT NULL,
  payment_method text,
  payee text,
  remarks text,
  created_by uuid
);
CREATE TABLE staff_payroll (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  staff_id uuid NOT NULL REFERENCES staff_profiles(id),
  salary_month text NOT NULL,
  gross_pay numeric(14,2) NOT NULL,
  leave_deduction numeric(14,2) NOT NULL DEFAULT 0,
  other_adjustment numeric(14,2) NOT NULL DEFAULT 0,
  net_pay numeric(14,2) NOT NULL,
  approval_status text NOT NULL DEFAULT 'draft',
  paid_at timestamptz,
  UNIQUE (business_id, staff_id, salary_month)
);
CREATE TABLE staff_incentives (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  staff_id uuid NOT NULL REFERENCES staff_profiles(id),
  invoice_id uuid NOT NULL REFERENCES invoices(id),
  source text NOT NULL CHECK (source IN ('inbound','outbound')),
  basis_amount numeric(14,2) NOT NULL,
  rate_percent numeric(6,3) NOT NULL,
  amount numeric(14,2) NOT NULL,
  approval_status text NOT NULL DEFAULT 'pending',
  paid_at timestamptz,
  UNIQUE (business_id,invoice_id,staff_id)
);
-- NOTE: all cross-business foreign keys must be reinforced by composite tenant-scoped
-- uniqueness/FKs and row-level security policies; the sketch above is NOT safe to deploy.

-- v0.4 planning additions: company-specific legacy migration and finance accounts
CREATE TABLE legacy_import_batches (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  source_application text NOT NULL,
  source_file_name text NOT NULL,
  file_checksum text,
  import_type text NOT NULL,
  imported_at timestamptz NOT NULL DEFAULT now(),
  imported_by uuid,
  rows_received integer NOT NULL DEFAULT 0,
  rows_new integer NOT NULL DEFAULT 0,
  rows_duplicates integer NOT NULL DEFAULT 0,
  status text NOT NULL DEFAULT 'staged'
);
CREATE TABLE legacy_import_records (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  batch_id uuid NOT NULL REFERENCES legacy_import_batches(id),
  record_type text NOT NULL,
  source_record_key text NOT NULL,
  mapped jsonb NOT NULL DEFAULT '{}'::jsonb,
  original_row jsonb NOT NULL,
  imported_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(business_id,record_type,source_record_key)
);
CREATE TABLE financial_accounts (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  account_name text NOT NULL,
  account_type text NOT NULL CHECK (account_type IN ('cash','bank','upi','card','other')),
  display_reference text,
  opening_balance numeric(14,2) NOT NULL DEFAULT 0,
  enabled boolean NOT NULL DEFAULT true,
  UNIQUE(business_id,account_name)
);
CREATE TABLE cashbook_transactions (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  account_id uuid NOT NULL REFERENCES financial_accounts(id),
  posted_date date NOT NULL,
  amount_signed numeric(14,2) NOT NULL,
  transaction_type text NOT NULL,
  source_type text,
  source_id uuid,
  related_transfer_id uuid,
  reference text,
  notes text,
  created_by uuid,
  created_at timestamptz NOT NULL DEFAULT now()
);
-- Require business-scoped composite FKs and verified RLS policy for every
-- new table; cross-tenant account/transaction assignments must be rejected.
-- Old transactional invoices MUST NOT enter active GST/sales totals without
-- a formally approved accounting migration and cutoff reconciliation.

-- v0.5 amendment — PLANNING ONLY; not a production migration.
-- Keep the existing business ids stable when rebranding to avoid disconnecting historical records.
-- darvi-group => Darvi Group Agri Tech (Nursery)
-- darvi-genetics => Darvi Genetics (Cows, Semen Bank, Ghee)
-- darvi-agri => Darvi Agri Developer LLP (Vikshha land/plots)

-- For a new deployment, admin/superadmin are separate verified roles; never use
-- the demo's client-side shared PIN/role selection in production.
-- In production replace memberships.role constraint with enum/check permitting 'superadmin'.
CREATE TABLE IF NOT EXISTS quotations (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  customer_id uuid REFERENCES customers(id),
  quote_number text NOT NULL,
  issued_on date NOT NULL,
  valid_until date,
  transport_amount numeric(14,2) NOT NULL DEFAULT 0,
  transport_tax_rate numeric(5,2) NOT NULL DEFAULT 0,
  tax_total numeric(14,2) NOT NULL DEFAULT 0,
  total_amount numeric(14,2) NOT NULL DEFAULT 0,
  created_by uuid NOT NULL,
  converted_invoice_id uuid REFERENCES invoices(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(business_id, quote_number)
);
CREATE TABLE IF NOT EXISTS quotation_items (
  id uuid PRIMARY KEY,
  quote_id uuid NOT NULL REFERENCES quotations(id),
  business_id uuid NOT NULL REFERENCES businesses(id),
  product_id uuid REFERENCES products(id),
  product_name_snapshot text NOT NULL,
  quantity numeric(14,3) NOT NULL CHECK(quantity>0),
  unit_price numeric(14,2) NOT NULL CHECK(unit_price>=0),
  discount_pct numeric(5,2) NOT NULL DEFAULT 0,
  tax_pct numeric(5,2) NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS vikshha_plots (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  plot_number text NOT NULL,
  size_sqft numeric(14,2) NOT NULL CHECK(size_sqft>0),
  customer_id uuid REFERENCES customers(id),
  customer_name_snapshot text NOT NULL,
  customer_phone text,
  customer_email text,
  customer_address text,
  registration_date date, -- STRICT: read/write restricted to admin/superadmin by RLS/API
  registration_reference text,
  status text NOT NULL DEFAULT 'available',
  agreement_value numeric(16,2) NOT NULL DEFAULT 0,
  notes text,
  changed_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(business_id,plot_number)
);
CREATE TABLE IF NOT EXISTS vikshha_plot_receipts (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  plot_id uuid NOT NULL REFERENCES vikshha_plots(id),
  amount numeric(16,2) NOT NULL CHECK(amount>0),
  receipt_date date NOT NULL,
  method text NOT NULL,
  reference text,
  recorded_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
-- REQUIRED production rules:
-- 1. Admin/superadmin can read and mutate Vikshha plot+receipt rows; all other roles NO ACCESS.
-- 2. Staff can create invoices, quotations and set line discounts, but CANNOT update products,
--    customer master, HSN/GST, base product price, company settings or staff role assignments.
-- 3. Prices/taxes must be reloaded from server when staff submit invoice/quotation (prevent tampering).
-- 4. Enforce all business_id links in SQL using composite unique keys / FKs, RLS, API authorization,
--    atomic invoice numbering and multi-device stock transactions. Separate each business ledger.

-- v0.6 planning additions (illustrative only; no migration runner; DO NOT DEPLOY AS-IS)
-- ALTER TABLE customers ADD COLUMN customer_code text;
-- ALTER TABLE customers ADD COLUMN shipping_address text;
-- ALTER TABLE customers ADD COLUMN shipping_city text;
-- ALTER TABLE customers ADD COLUMN shipping_state text;
-- ALTER TABLE customers ADD COLUMN shipping_pin text;
-- CREATE UNIQUE INDEX customers_business_code_uq ON customers(business_id,customer_code);
-- ALTER TABLE invoices ADD COLUMN customer_invoice_sequence bigint;
-- ALTER TABLE invoices ADD COLUMN customer_invoice_ref text;
-- ALTER TABLE invoices ADD COLUMN ship_to_snapshot jsonb;
-- CREATE UNIQUE INDEX invoice_customer_seq_uq ON invoices (business_id,customer_id,customer_invoice_sequence) WHERE customer_id IS NOT NULL;
-- Sequence allocation MUST be within a transaction with a row/advisory lock; never use client-side max()+1 in production.

CREATE TABLE crm_leads (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  customer_id uuid REFERENCES customers(id),
  owner_auth_user_id uuid,
  created_by uuid NOT NULL,
  name text NOT NULL,
  phone text NOT NULL,
  phone_normalized text,
  email text,
  interest text,
  source text,
  stage text NOT NULL CHECK (stage IN ('New','Contacted','Qualified','Quotation Sent','Negotiation','Won','Lost')),
  next_followup_on date,
  notes text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, business_id)
);
CREATE INDEX crm_leads_business_phone_idx ON crm_leads(business_id,phone_normalized);
CREATE INDEX crm_leads_due_idx ON crm_leads(business_id,next_followup_on) WHERE stage NOT IN ('Won','Lost');
CREATE TABLE crm_lead_activities (
  id uuid PRIMARY KEY,
  business_id uuid NOT NULL REFERENCES businesses(id),
  lead_id uuid NOT NULL REFERENCES crm_leads(id),
  author_auth_user_id uuid NOT NULL,
  type text NOT NULL,
  note text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
-- Security requirements: activate RLS on both tables; derive the business and owner from verified claims,
-- enforce staff access only to leads they created/own, admin access limited to businesses where authorized.
-- Never trust a provided business_id or role from the browser. Prohibit staff from changing customers master.
