# Darvi Billing: architecture toward a commercial SaaS product

## v0.2 prototype: company books are separated locally, but not securely

The browser saves a v2 root containing `businesses` keyed by three business IDs, a `staff` table and a current business pointer. This enables workflow demos and aggregate reports. Staff login and role restrictions are only client-side and must **not** be represented as actual security.

## Current vertical slice

```
Android / Desktop browser
        |
  Responsive PWA UI
        |
 Browser localStorage
        |
 Invoices + purchases + expenses + payroll + incentives + reports + backup
```

This is a working **single-device multi-business prototype**: no backend, no live user authentication, and no cross-device data sync. The goal of this version is validating workflows, invoice layouts and product/inventory concepts before integrating more complex infrastructure.

## Proposed production architecture

```
Android (Capacitor + ESC/POS plugin)    Windows (Tauri / browser)
                 \                     /
               Shared UI / client domain logic
                        |
                IndexedDB local store
                        |
                Outbox / sync worker
                        |
             Authenticated HTTPS API
                        |
     Tenant-aware service / database transactions
                        |
      PostgreSQL with row-level security (RLS)
                        |
          Background jobs / backups / reports
```

### Tenant & staff isolation

- Every important table has `business_id` (tenant) and optional `branch_id`.
- Authenticated user IDs are linked to businesses through `business_memberships` and roles (`owner`, `admin`, `manager`, `cashier`, `accountant`).
- The API/DB always derives access from authenticated identity; clients must not self-assign a tenant ID or privilege.
- Database RLS and API integration tests verify that one business can never access another's invoices or customers.
- Each invoice number must be allocated in a transaction with a per-business (and jurisdiction-appropriate) sequence. No browser-generated sequences in production.
- Staff activity should record actor, timestamp, before/after (where appropriate), branch and immutable audit events.

### Offline-first synchronization

1. Save in IndexedDB using locally generated UUIDs and an outbox event with an idempotency key.
2. When online, send events over the authenticated API in creation order.
3. Backend validates version, tenant, stock reservation and permissions, then commits with transaction locks.
4. Backend returns authoritative invoice number and transaction IDs; client reconciles local optimistic state.
5. Keep user-facing conflict resolution for same stock adjusted by multiple devices; enforce inventory conservation on server.
6. Use retention, backups, disaster recovery and access revocation, not browser-only storage.

### Printer strategy

- A4/PDF: browser/system print available now.
- 80 mm receipt: current print CSS can feed OS-managed thermal printer.
- Direct Bluetooth: Android Capacitor plugin for supported ESC/POS devices; Bluetooth permission, reconnect, codepage and paper-width testing.
- USB/LAN: use OS driver, native bridge or local printer service; validate actual printer models.

### Regulatory & accounting items to confirm before live launch

- GSTIN, invoice legal field requirements, customer/seller addresses, place of supply, HSN/SAC and tax slab overrides.
- Item-level inclusive/exclusive pricing and line/total rounding policy.
- Reverse-charge, exemptions, discounts, returns/credit/debit notes and cancellation rules.
- Relevant e-invoicing/e-way-bill compliance, financial-year sequencing, reconciliation, records retention and export formats.
- Payments, refunds, UPI references and settlement/reconciliation as accounting events rather than just invoice counters.
- Price lists, purchase costs, supplier purchases, expenses, profit-and-loss reporting and inventory valuation.

## Suggested releases

**v0.2 — finished demo:** three local workspaces, purchase receipts, expenses, salary entries, attributed incentives, staff role previews and consolidated reports. All local only.

**v0.3 — internal cloud:** hosted Postgres, secure login, Darvi organizations and business memberships, user permissions, two-way sync, audit log, encrypted backups, role-tested API and cloud reporting. Add attendance/approval ledgers.

**v1.0 — Darvi deployment:** branded Android APK, Windows installer, payment reconciliation, customer accounts, staff sales incentives, GST/accountant export, tested hardware.

**v2.0 — commercial multi-tenant SaaS:** self-service onboarding, tenant creation, subscriptions, usage limits, plans, payment gateway integration, isolated data and support tools.

## Database sketch

See `schema-planning.sql`. It is intentionally a *planning sketch* and is not production-ready migrations or a security certification.
