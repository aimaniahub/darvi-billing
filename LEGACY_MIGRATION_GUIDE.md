# Historical Vyapar / other POS migration — Darvi Billing v0.4

1. Make a full backup of the previous app and retain the original source files securely before migrating anything.
2. Export **separate** customer/party, item/stock, invoice/sales, payments, purchases, expenses and report data, preferably as Excel or CSV from your previous app. If you have Excel workbooks, export each relevant sheet to **CSV UTF-8** first. A supported JSON export also works; **encrypted/proprietary app backup files are not supported** by this version.
3. Select the correct business (Darvi Group LLP, Darvi Genetics or Darvi Agri Developer LLP), then open **Old data migration**.
4. Select a source category and the previous billing app name. Choose a CSV/JSON file, preview records, and confirm import. The original columns and standardized mapped fields are retained in a business-scoped historical IndexedDB archive. Duplicate references are skipped; originals can be downloaded as archive JSON.
5. Optional: activate only new customer and item masters in the live billing lists. **Imported item stock starts at zero** until reviewed; existing customer/item records are not overwritten.
6. The archive does **not** alter live sales reports, GST totals, previous receipts or balances. Historical opening balances are for review only, pending reconciliation by the accountant. Validate invoice lines, taxes, returns, refunds, customer and supplier balances against original reports before formally cutting over.
7. Export the **historical archive JSON** per company separately, in addition to the regular all-business live-data backup. The live backup does not include this separate archive. Do not clear browser storage before both backups are verified.

## Important limitations

* In this release the archive is in a separate IndexedDB **on the current device**, partitioned by `businessId`. It is not a server database and is not automatically shared between employees.
* `CSV` files larger than 20MB need splitting into batches. Exports with complex worksheets, malformed column headers, journal reversals or duplicate item names may need an additional mapping pass.
* For a production multi-user website, migrate to isolated PostgreSQL tables with verified business-level Row Level Security, import batch tracking, audit logs and object storage for original files. Avoid merging old and new invoice sequences without a verified cutover date.
* Sensitive customer data should only be migrated to the authenticated, production-grade database, **not** to this browser demo.
