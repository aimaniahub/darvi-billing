# Darvi Billing — Project Status v0.6 (31 October 2026)

## Latest checkpoint
- **Version:** 0.6 CRM, phone lookup, customer IDs, shipping labels.
- **Saved source:** `Darvi_Billing_v0.6_Source.zip`; standalone `Darvi_Billing_v0.6_Demo.html`.
- **Persistent folder:** `/Darvi Group/Darvi Billing` in ChatGPT Library.
- **Status:** frontend prototype tested in local Chromium. NOT installed on real devices, NOT connected to secure server, NOT published.

## Requirements carried forward
- Three businesses (IDs must remain `darvi-group`, `darvi-genetics`, `darvi-agri`): nursery only; cows/semen/ghee; Vikshha farmland only.
- Masters controlled by admin/superadmin; employees can create invoices, quotes, permitted discounts and follow up CRM leads.
- Vikshha plot number, size, buyer/customer, date and payments must remain admin/superadmin only with production database rules.
- Invoices: 58mm/80mm browser print, A4/PDF, transport fees, payment tracking; quotations convert to invoices.
- Migration: keep original customer and invoice exports separate until reviewed for duplicates. Legacy archive remains.
- Accounting: purchases, expenses, staff salaries, commissions, consolidated sales and account manager per business.

## Tested in v0.6
- Admin lead entry with email, phone, source, follow-up, assigned staff; note chronology; conversion into a uniquely coded customer.
- Phone lookup by partial number, showing customer history and authorized lead matches.
- Two invoices for the same customer received different business invoice numbers and customer-specific invoice refs.
- Per-invoice shipping snapshots; successful 4×6 and four-up A4 label HTML print rendering.
- Sales staff created a lead but could not edit master customer or convert lead to customer.
- Desktop and mobile rendering checked; browser JS runtime had no errors.

## Known limitations / production gates
1. Shared 1234 PIN, browser-only data, no central permissions. **Never deploy to public site or enter personal/financial data before server authentication, RLS and backups.**
2. Phone reminders are on-screen only, not background notifications.
3. Invoice number/sequence generation is local-only; multi-device production requires transactional backend issuance and unique constraints.
4. Label printing is through browser print, not direct Bluetooth ESC/POS; need test actual 58mm and 4×6 printer models.
5. No courier/aggregator API, tracking number, barcode or shipping carrier integration yet.
6. Full live migration from previous billing app is not complete; needs real exports and reconciliation.
7. GST compliance and legal terms must be validated with accountant before production use.

## Next steps
- Backend Supabase/PostgreSQL, signed-in staff identities, tenant separation with RLS, secure business-wide search, lead events and follow-up scheduler.
- Connect existing web hosting to a private portal (e.g. darvigroup.in/billing/) only after security review, backups and UAT.
- Add courier shipping integration, barcode/tracking, manifests, 4-up bulk shipping-label jobs and actual 58mm Bluetooth bridge.
- Preserve original logo and historical browser data during schema upgrades. Avoid changing original invoice numbers.
