# Darvi Billing: architecture toward a commercial SaaS product

## v0.2 prototype: company books are separated locally, but not securely

The browser saves a v2 root containing `businesses` keyed by three business IDs, a `staff` table and a current business pointer. This enables workflow demos and aggregate reports. Staff login and role restrictions are only client-side and must **not** be represented as actual security.

## Current vertical slice

```
Android native Java screens
        |
 NativeStore / SQLite
        |
 Invoices + purchases + expenses + payroll + incentives + CRM + plots + reports
        |
 Native Bluetooth SPP / ESC-POS printing

Legacy Android WebView / desktop browser
        |
 Responsive PWA UI + browser localStorage
        |
 Manual JSON export / import for migration
```

The Android launcher uses native screens and SQLite. The prior Capacitor UI remains available as a legacy activity for data export and workflows not yet ported to the native implementation. Native backup import accepts the existing multi-business JSON shape. The app remains a **single-device multi-business prototype**: no backend, no live user authentication, and no cross-device data sync.

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
- Direct Bluetooth: native Android Bluetooth Classic SPP. The chosen paired printer is saved and reused for receipts and labels. Invoice receipts use a raster logo/QR header, separated invoice sections, aligned item and total rows, and increased vertical spacing. The body is followed by a separately flushed raster thank-you footer and feed command, with the sender waiting between complete output writes; this works around printers that defer the end of one large raster job until another command arrives. The diagnostic test page retains raster output. Shipping labels use explicit ESC/POS PC437 text mode. Verify actual output on each supported printer because byte writes do not confirm physical printing and model-specific ESC/POS support can differ.
- USB/LAN: use OS driver, native bridge or local printer service; validate actual printer models.

### Regulatory & accounting items to confirm before live launch

- GSTIN, invoice legal field requirements, customer/seller addresses, place of supply, HSN/SAC and tax slab overrides.
- Item-level inclusive/exclusive pricing and line/total rounding policy.
- Reverse-charge, exemptions, discounts, returns/credit/debit notes and cancellation rules.
- Relevant e-invoicing/e-way-bill compliance, financial-year sequencing, reconciliation, records retention and export formats.
- Payments, refunds, UPI references and settlement/reconciliation as accounting events rather than just invoice counters.
- Price lists, purchase costs, supplier purchases, expenses, profit-and-loss reporting and inventory valuation.

## Production readiness priorities

Before any live deployment, replace browser-local records and client-side roles with authenticated identities, server-enforced business isolation, transactional invoice and inventory updates, auditable financial events, encrypted backups, tested recovery, and accountant-reviewed tax workflows. Add offline synchronization only after the backend authorization and conflict tests pass. Validate each Android printer model and complete paper handling on physical devices.

## Database sketch

See `schema-planning.sql`. It is intentionally a *planning sketch* and is not production-ready migrations or a security certification.
