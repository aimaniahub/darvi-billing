# Darvi Billing release notes

## v0.5 — 31 October 2026
- Rename primary business to Darvi Group Agri Tech (Nursery) without changing storage key.
- Darvi Genetics dedicated Cows, Semen Bank and Ghee starter catalogue.
- Vikshha-only land plot/customer and registration-date manager under Darvi Agri Developer LLP, visible to Admin/Super Admin only in the demo.
- New Quotation/Estimation workflow, no stock deduction until converted to invoice; saved quote and conversion reference.
- Delivery/Transport charges in nursery and Genetics invoice/quotation builder, summaries and printouts.
- Employee roles limited to bills/estimates/discounts and own incentive view. Master customer/product data admin-controlled; base prices and GST rate are read-only for staff.
- Added prototype workflow and mobile UI tests and production SQL roadmap.

## v0.4 — 31 October 2026
- Cash, bank and UPI account manager, customer/supplier ledger, payment allocations and statement export.

## v0.3 — 31 October 2026
- Previous-app migration and 58mm browser print layout.

## v0.2 — 31 October 2026
- Multi-business staff demo, purchases, expenses, payroll, incentives, total sales reporting.

## v0.1 — 31 October 2026
- Invoice builder, stock, payments, A4/80mm print and responsive prototype.

## v0.6 — CRM, phone lookup, customer invoice reference and shipping labels
- Added Leads & Mini CRM to each company workspace, with assigned staff, contact details, statuses, notes, follow-up date and activity trail.
- Staff can create and manage assigned leads without changing customer/product master; admin converts or links leads to customers with duplicate-number checks.
- Dashboard phone-only search finds matching customers, visible leads and invoice contact snapshots in the selected business.
- New stable customer codes plus customer-specific invoice references, displayed on invoice detail and printouts; original invoice numbers are retained.
- Shipping recipient and address stored on invoices; 4×6-inch and A4 four-up print layouts available.
- Kept all v0.5 modules and previous local-storage key to preserve existing prototype records.
- Tested Chromium admin/staff conversion, unique refs, phone search, and both label print modes. Production backend not yet deployed.
