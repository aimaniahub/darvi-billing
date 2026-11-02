# Darvi Billing 0.6 — CRM, Phone Lookup, Customer Invoice IDs and Shipping Labels

## Mini CRM
- Each business has a separate `leads` array in its own browser-local workspace. Fields: Lead name, phone, email, interest, source, stage, next follow-up date, assigned staff, notes, activity history, created/updated dates.
- Stages: New, Contacted, Qualified, Quotation Sent, Negotiation, Won, Lost.
- Staff can create leads, record call/WhatsApp/meeting notes, change stage/follow-up and edit only their own/assigned leads. Admin can see all business leads and assign them. Lead-to-customer conversion requires admin; duplicate phone prompts admin before linking existing customer.
- Dashboard shows a phone-only lookup for customers, visible leads and legacy customer names on invoices. Search within the **active business only**; requires at least four digits, supports +91 formatting. Staff invoice history remains limited to their own invoices.
- CSV export is available for visible leads.
- Follow-up reminders are *in-app date filters only*; no scheduled push, WhatsApp, SMS or email reminders yet. Requires secure server jobs and opt-in/approved messaging integrations.

## Customer and invoice identifiers
- Per-business customer IDs such as `DB-C00001`/`DGE-C00001` are assigned once and remain stable in the prototype; previous customer records receive codes on upgrade. Existing IDs are preserved.
- Each invoice retains a unique business invoice `number` (e.g. `DB-2026-0001`) and immutable internal random `id`.
- Each invoice also has a per-customer sequence/reference (`customerInvoiceRef`: `DB-C00001-I0001`, `-I0002`, etc). For walk-in customers: `WALK-IN-I0001`, etc.
- Invoice details, printed A4 and 58mm receipts show the customer invoice reference. Historical invoices are backfilled with references without changing their original numbers.
- **Production:** allocate sequences in the database transactionally, add `UNIQUE (business_id, invoice_number)` and `UNIQUE (business_id, customer_id, customer_invoice_sequence)` constraints, handle fiscal-year/GST invoice numbering lawfully, and prevent issuance races across devices.

## E-commerce shipping address and label
- The invoice draft has a dedicated delivery form (recipient, phone, street/landmark, city, state, 6-digit PIN). Prefills from the customer master if available; staff can override per invoice without editing master data.
- The invoice stores a `shippingSnapshot`, preserving the address as it was at the time of sale.
- The invoice detail has **Print shipping label 4×6** and **A4 labels (4-up)** actions. The latter produces four copies of the selected invoice's label, useful for label sheets, not four different orders.
- Includes invoice number, customer reference, ship-to details, seller name and amount-due status. Courier QR/barcode/tracking number is NOT yet provided.
- Browser print dialog uses CSS `@page` named pages. Printer margins/paper size must be configured to match actual stock; browsers differ.

## Data and security limitations
- v0.6 is still local-only HTML/JS with a shared demo PIN. DO NOT upload confidential customer information or use on a public site.
- Staff restrictions are client-side prototype behavior, not access security. Before production: secure authentication, role-based database policies, per-business tenant isolation, audit logs, encryption, multi-device sync and backups.
- No direct Bluetooth ESC/POS bridge yet. 58mm is a browser print layout, not Android automatic printer pairing.
- The official original Darvi Group logo is preserved.
