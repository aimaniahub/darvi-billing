# Darvi Billing

Darvi Billing is a multi-business billing prototype with a browser/PWA interface and a native Android application. It includes invoices and quotations, customer and product records, purchases, expenses, payroll, sales incentives, CRM leads, plot records, reports, legacy-data import, shipping labels, and Android Bluetooth receipt printing.

## Run locally

Requirements: Node.js/npm. Install dependencies and start the local server:

```sh
npm ci
npm start
```

The server prints its local HTTP and HTTPS addresses. The HTTPS server uses development certificates under `.ssl/`; do not commit those private keys. Browser data is local to the browser and device.

## Android build

Requirements: Android Studio/SDK, a compatible JDK, and Node.js/npm.

```sh
npm ci
npm run build
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
```

The web bundle in `www/`, Gradle outputs, and APKs are generated artifacts and are intentionally excluded from Git. Keep the Android source, Gradle wrapper, and project configuration under version control.

## Tests

```sh
npm test
```

The Android unit tests are run with `.\gradlew.bat testDebugUnitTest` from `android/`.

## Printing

The Android app sends raster ESC/POS receipts over paired Bluetooth Classic printers. Receipts include the Darvi logo and website QR code, separated receipt sections, aligned amounts, and a separately flushed footer. Printer behavior varies by model; successful Bluetooth writes do not confirm physical printing. Verify the complete output on the target printer.

Browser printing uses the browser/operating-system print dialog; select the appropriate paper and printer settings. Direct web Bluetooth printing is not guaranteed by the browser prototype.

## Data and deployment warning

This is a local prototype, not a production billing service. It has no authenticated backend or server-enforced business/staff access controls; browser roles and local data are not security boundaries. **Do not use real customer, financial, payroll, or land-registration data, and do not publish it as a live billing portal.** Production deployment requires a secured backend, authentication, tenant isolation, backups, auditability, and accountant-reviewed tax compliance.

## Repository guide

- [ARCHITECTURE.md](./ARCHITECTURE.md) — current application structure, boundaries, and production considerations.
- [CHANGELOG.md](./CHANGELOG.md) — release history.
- `schema-planning.sql` — non-production database planning sketch.
