# Deploy Darvi Billing through darvigroup.in — v0.4

## Recommended URL and rollout

* Suggested installation path: `https://darvigroup.in/billing/` (or a dedicated subdomain such as `https://billing.darvigroup.in/`). These URLs are proposed; the app **has not been deployed**.
* The folder is a static progressive web app (PWA). All CSS, icons, JavaScript, logo, manifest and service worker use relative paths so it can be hosted under `/billing/` without rewriting asset URLs.
* On Android, employees can access the HTTPS URL in Chrome and use **Install app / Add to Home screen** for an app-like launch. On desktop, use the browser or install as a PWA where supported.
* Example link for the existing site menu after deployment:
  ```html
  <a href="/billing/" rel="nofollow">Staff Billing Login</a>
  ```
* For a protected test deployment, use an **authenticated staging environment** behind a private access gateway before publishing the files, then replace the demo PIN and local-store model with real server authentication.

## Critical production blockers

This prototype is **not safe to publish as a real billing portal**. Anyone with access to the demo can select a staff identity using the shared PIN 1234; all data in that user's browser is accessible to local scripts/users. Browser localStorage and IndexedDB are device-local and are not synced to the other staff members. There is no hosted database, real passwords, permission enforcement, encryption-at-rest guarantees for client data, or server reconciliation.

**Never upload real customer details, bank details, payroll data, invoices or proprietary billing backups to a publicly accessible demo.** Before live deployment: real auth (MFA/password resets), business-level server-enforced authorization, PostgreSQL/Supabase tenant isolation + RLS, encrypted backups, audit logs, HTTPS, atomic invoice sequences, data retention and restoration testing, GST compliance validation and printer compatibility tests.

## Suggested application infrastructure

1. Keep public marketing website separate from billing application: either `/billing` on the main website or `billing.darvigroup.in` routed to an HTTPS app host.
2. Use managed auth (e.g., Supabase Auth) and PostgreSQL. Store `business_id` on every financial table; enforce tenant-scoped composite constraints and Row Level Security on every query.
3. Use financial account tables and historical import batches; keep previous-app records immutable in archival tables, and only publish approved masters into live products/customers.
4. Process every cashbook movement, invoice number and inventory change transactionally on the server; do not trust a user-selected business ID from the browser alone.
5. On web: 58mm **browser print preview** works with a printer driver configured for the 58mm roll. **Direct Android Bluetooth printing is not implemented** in the browser demo; build/test a native Android bridge with appropriate permissions or a vetted printer-specific SDK (ESC/POS profile supported by the exact printer model).
6. Maintain a private company admin workspace for finance, customer history, staff roles, salaries and consolidated management views. Do not expose it on the public marketing site.

## How to preview this folder on a trusted computer

From the folder that contains `index.html`, run a local development server, for example `python -m http.server 8000` and open `http://localhost:8000`. On a production host, use HTTPS and a secure authentication gate. Browser storage is origin-specific, so migrating from file:// or localhost to the real domain does **not** automatically move customer data; export backups and import via the approved migration process.

## Existing website status

`darvigroup.in` is a real existing public site, but **this deliverable has not changed it**. To deploy, the website owner/administrator must provide hosting access or use their hosting control panel / CI/CD pipeline. No DNS changes or server uploads have been performed in this prototype update.
