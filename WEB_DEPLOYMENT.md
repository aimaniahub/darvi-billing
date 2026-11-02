# Darvi Billing — publishing through darvigroup.in

## Current readiness
The static app is subpath-friendly and can be loaded from `https://darvigroup.in/billing/` once web hosting is configured. **This is packaging readiness, NOT live deployment or secure production readiness.** The files have NOT been uploaded to your site by this release.

## Strong recommendation
Keep the public www.darvigroup.in marketing site unchanged. Introduce a **private staff portal** at `/billing/` or `billing.darvigroup.in`. Protect the staging URL with server-level authentication/IP restriction. The application prototype's shared PIN is NOT security.

## Private demonstration procedure
1. Confirm how darvigroup.in is hosted (cPanel/Apache, nginx, WordPress or static service) and whether the host supports a protected private subdirectory.
2. Create `/billing/` under your site's web root, or configure an independent subdomain. Copy the **contents** of the included `billing/` directory into that location.
3. Protect `/billing/` on the server. Example Apache and nginx snippets below require the site administrator to supply verified paths and actual passwords. Do not place a password file in a publicly accessible directory.
4. Enable TLS/HTTPS (for PWA/service worker, offline features and credential security). Check `/billing/manifest.webmanifest`, `/billing/sw.js`, `/billing/app.js` and logo asset all return 200, and that unauthenticated visitors are denied by the web server.
5. Test in a private browsing context with sample data only. See the mobile layout and print dialogs; note that Bluetooth printer hardware still needs native integration.
6. Add a staff-only link from the public website AFTER authentication is in place, e.g. `<a href="/billing/">Staff Billing</a>`; do not put real billing data into the public website's content system.

## Apache/.htaccess example (server admin configuration, **not** active by default)
```
AuthType Basic
AuthName "Darvi Billing Demo - Private"
AuthUserFile /absolute/server/path/outside/webroot/darvi-htpasswd
Require valid-user
Options -Indexes
```
A hosting administrator must create the htpasswd file in a non-public location, provision user credentials and ensure the site does not expose this file. Never hard-code credentials in HTML/JavaScript.

## nginx example (illustrative, adjust your root/hosting)
```
location ^~ /billing/ {
    root /var/www/darvigroup;
    index index.html;
    auth_basic "Darvi Billing Demo - Private";
    auth_basic_user_file /etc/nginx/darvi-billing-htpasswd;
    try_files $uri $uri/ /billing/index.html;
}
```
Set up certificate/TLS and disable public crawling. For WordPress/cPanel, your site administrator should coordinate the route with the existing site's rewrite rules; avoid overwriting the root `index.html` or WordPress files.

## Why the prototype cannot go live as real finance software yet
- Common demo PIN and client-side admin roles are trivially bypassed; any visitor to an unprotected demo can act as "admin" for that visitor's browser data.
- Browser localStorage data is unique per device and not centrally available to your staff; clearing data loses entries.
- No backup/restore operations, durable DB transactions, electronic audit trail, per-business isolation enforced by the server, or accounting compliance controls.

## Production design
- Frontend PWA remains under `https://darvigroup.in/billing/`.
- Authenticated API with PostgreSQL, encrypted backups, per-business server-side access checks and immutable audit trail; never rely on browser PINs.
- Role policies for Admin, Accountant, Manager, Sales, Cashier; staff may hold different roles in each legal entity.
- Separate invoice sequences and GST registration for each company. Intercompany transfers require their own business-side entries.
- Protect payroll fields and customer data; use HTTPS, server sessions in HttpOnly Secure cookies, CSRF protection, rate limiting and MFA for administrators.
- Add cloud sync, offline IndexedDB outbox and conflict resolution only after the backend tests pass.

## Releasing a new version
1. Take/export any old demo data from authorized test devices; keep demo vs production records distinct.
2. Unpack the new website ZIP into a *staging* location, not straight over the public site; test business switching, account balances, invoice payments and printing.
3. Move through version-controlled deployment once backend, security and compliance are ready.
