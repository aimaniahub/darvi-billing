# Darvi Billing v0.6 — CRM & Shipping

Multi-business responsive web/PWA prototype for Darvi Group. **Local demonstration; not ready for use with real private records.**

## Business workspaces
1. **Darvi Group Agri Tech** — nursery products, quotations, invoices, transport charges, 58mm / 80mm thermal print styles.
2. **Darvi Genetics** — cows, semen bank, ghee; quotations, sales and transport charges.
3. **Darvi Agri Developer LLP** — Vikshha plot register / land information for admin/superadmin in the prototype.

## New in v0.6
- Mini CRM: manual leads (name, phone, email, interest, source), stages, follow-ups, notes, staff assignments, admin conversion to customers and lead CSV export.
- Phone-only search at the business dashboard for customers, leads and invoice contacts.
- Stable per-business customer codes, customer-specific invoice references in addition to globally distinct business invoice numbers.
- Shipping address on invoices, labels printable 4×6 inches or A4 with four identical labels.

## Existing modules
Purchases, expenses, salary rules, sales incentives, separate business account manager, sales reports, legacy CSV/JSON migration archive, stock and quotations (no stock change until invoicing).

## Open
- Standalone HTML: open `Darvi_Billing_v0.6_Demo.html` in desktop/mobile browser.
- Staff demo login: select a staff profile; use `1234` (shared demo PIN is not secure authentication).
- Website staging: unpack `Darvi_Billing_v0.6_Website_Staging.zip` in a private, access-restricted test environment only. **It does not provide security or connect to any live website.**

## Important safeguards
- **Never use real private customer, bank, staff salary or land-registration data in this prototype.** It uses browser-local storage and client-side role checks.
- Actual secure cloud sync, staff authentication, database authorization and printer Bluetooth bridging are still pending.
- 58mm and shipping labels use browser printing; verify the physical printer and media settings.

See `CRM_PHONE_SHIPPING_v06.md`, `PROJECT_STATUS.md`, `schema-planning.sql`, and `POS_58MM_PRINT_GUIDE.md`.
