# 2-inch POS thermal receipt — current v0.4 support

A 2-inch roll is commonly sold as **58mm paper width**. The printable width is often around 48mm and varies by model and print-head margin. This prototype includes a narrow receipt layout and a **2-inch POS / 58mm** action on every invoice details page.

On desktop, install your printer's appropriate driver; select the 58mm paper profile, remove headers/footers and extra margins, select 100% / actual size, then print a test invoice. On Android, the web version opens the browser print dialog, which may **not** expose a direct Bluetooth thermal printer. Direct Bluetooth ESC/POS printing needs a native Android app or hardware/SDK bridge, with the exact printer model and Bluetooth mode tested.

The prototype receipt prints company details, invoice number, date, customer, products, quantity/rate, discounts, tax, payment received, due and footer in a compact black-and-white design. It is not yet hardware-certified and is marked PROTOTYPE. Do not assume every 58mm printer has the same printable area or paper advance behavior.
