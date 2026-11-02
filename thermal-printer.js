/* Darvi Billing - Seznik 2POS / 58mm Thermal Printer Engine
   Supports:
   1. Direct Web Bluetooth API (Chrome/Edge on Android and Desktop)
   2. Android 1-Tap Intent Print (RawBT / ESC-POS thermal print apps)
   3. Monospace 32-Column CSS Print (Exact 1:1 match to physical roll)
   4. ESC/POS Binary Bytecode Generation with robust chunked Bluetooth transmission
*/

(function(global) {
  'use strict';

  // Standard ESC/POS byte definitions
  const ESC = 0x1B;
  const GS  = 0x1D;
  const LF  = 0x0A;

  // Bluetooth GATT service & characteristic UUIDs common in Indian & international 58mm thermal printers
  const PRINTER_SERVICES = [
    '000018f0-0000-1000-8000-00805f9b34fb', // Standard POS Printer Service
    '0000ffe0-0000-1000-8000-00805f9b34fb', // Transparent Serial Port (Most Seznik / POS-58 printers)
    '0000fee7-0000-1000-8000-00805f9b34fb', // Tencent / Low Energy POS
    '0000fff0-0000-1000-8000-00805f9b34fb', // Feasycom BLE Serial
    '49535343-fe7d-4ae5-8fa9-9fafd205e455', // ISSC SPP over BLE
    'e7810a71-73ae-499d-8c15-faa9aef0c3f2'  // AIROC / Broadcom POS
  ];

  // Helper string pad functions for 32-column printing
  const padRight = (str, len) => {
    str = String(str || '');
    return str.length >= len ? str.slice(0, len) : str + ' '.repeat(len - str.length);
  };

  const padLeft = (str, len) => {
    str = String(str || '');
    return str.length >= len ? str.slice(0, len) : ' '.repeat(len - str.length) + str;
  };

  const centerText = (str, width = 32) => {
    str = String(str || '').trim();
    if (str.length >= width) return str.slice(0, width);
    const leftPad = Math.floor((width - str.length) / 2);
    const rightPad = width - str.length - leftPad;
    return ' '.repeat(leftPad) + str + ' '.repeat(rightPad);
  };

  const wrapText = (str, width = 32) => {
    str = String(str || '').trim();
    if (!str) return [];
    const words = str.split(/\s+/);
    const lines = [];
    let cur = '';
    for (const w of words) {
      if (!cur) {
        cur = w;
      } else if ((cur + ' ' + w).length <= width) {
        cur += ' ' + w;
      } else {
        lines.push(cur);
        cur = w;
      }
    }
    if (cur) lines.push(cur);
    return lines;
  };

  const formatUnit = (unit) => {
    const u = String(unit || '').toLowerCase().trim();
    if (u === 'sapling' || u === 'plant') return 'P';
    if (u === 'piece' || u === 'item' || u === 'set') return 'PC';
    if (u === 'no' || u === 'nos' || u === 'number') return 'NO';
    if (u === 'kg') return 'KG';
    if (u === 'bag' || u === 'bags') return 'BG';
    if (u.length <= 2) return u.toUpperCase();
    return u.slice(0, 2).toUpperCase();
  };

  const formatIndianNumber = (num) => {
    return Number(num || 0).toLocaleString('en-IN', {
      maximumFractionDigits: 2
    });
  };

  const formatReceiptDate = (dateStr) => {
    const d = new Date(String(dateStr || '').slice(0, 10) + 'T12:00:00');
    if (isNaN(d)) return String(dateStr || '');
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'June', 'July', 'Aug', 'Sept', 'Oct', 'Nov', 'Dec'];
    return `${d.getDate()} ${months[d.getMonth()]} ${d.getFullYear()}`;
  };

  // Extract short number from invoice or quotation ID
  const extractShortNumber = (rawNum, defaultPrefix = '487') => {
    if (!rawNum) return defaultPrefix;
    // Check if ends with digits (e.g. DG-2026-0001 or DG-EST-2026-0002)
    const match = String(rawNum).match(/(\d+)$/);
    if (match) {
      const parsed = parseInt(match[1], 10);
      return parsed > 0 ? String(parsed) : rawNum;
    }
    return String(rawNum);
  };

  // State for Bluetooth connection
  let btDevice = null;
  let btCharacteristic = null;
  let isConnecting = false;
  let onStatusChangeCallback = null;

  const DarviPrinter = {
    // 32-column roll width standard for 2-inch (58mm) printers
    COLUMNS: 32,

    // Register callback for UI status updates
    onStatusChange(fn) {
      onStatusChangeCallback = fn;
    },

    notifyStatus(status) {
      if (typeof onStatusChangeCallback === 'function') {
        try { onStatusChangeCallback(status); } catch (e) { console.error(e); }
      }
    },

    isConnected() {
      return !!(btDevice && btDevice.gatt && btDevice.gatt.connected && btCharacteristic);
    },

    getDeviceName() {
      return btDevice ? (btDevice.name || 'Seznik 2POS Printer') : null;
    },

    getStatus() {
      return {
        connected: this.isConnected(),
        deviceName: this.getDeviceName(),
        isConnecting
      };
    },

    // Build normalized document payload from an invoice or quotation
    normalizeDoc(doc, type = 'invoice') {
      const isQuote = type === 'quotation' || type === 'estimate' || !!doc.convertedInvoiceId !== undefined && doc.number?.includes('-EST-');
      const b = doc.businessSnapshot || (window.store && window.store.businesses[window.store.activeBusinessId]?.business) || {};
      const c = doc.customerSnapshot || { name: doc.customerName || 'Walk-in Customer' };

      const lines = (doc.lines || []).map((l, idx) => {
        const qtyNum = Number(l.qty || 0);
        const qtyStr = qtyNum.toFixed(1) + formatUnit(l.unit);
        const mrpNum = Number(l.mrp !== undefined ? l.mrp : 0);
        const mrpStr = mrpNum.toFixed(1);
        const rateNum = Number(l.price !== undefined ? l.price : (l.rate || 0));
        const rateStr = rateNum.toFixed(1);
        const amountNum = Number(l.total !== undefined ? l.total : (l.gross || qtyNum * rateNum));
        const amountStr = amountNum.toFixed(1);

        return {
          idx: idx + 1,
          name: l.name || 'Item ' + (idx + 1),
          qty: qtyNum,
          qtyFormatted: qtyStr,
          mrp: mrpNum,
          mrpFormatted: mrpStr,
          rate: rateNum,
          rateFormatted: rateStr,
          amount: amountNum,
          amountFormatted: amountStr,
          discount: Number(l.discount || 0),
          tax: Number(l.tax || 0)
        };
      });

      const totalNum = Number(doc.total || 0);
      const subtotalNum = Number(doc.subtotal || totalNum);
      const paidNum = Number(doc.paidAmount || 0);
      const dueNum = Math.max(0, totalNum - paidNum);

      return {
        isQuote,
        title: isQuote ? 'QUOTATION' : 'TAX INVOICE',
        businessName: b.name || 'Darvi Group',
        address: b.address || '#2 totad building, Arjun Vihar,\nGokul road Hubli, Karnataka\n580030, India HUBLI',
        gstin: b.gstin || '29BKZPA0053M1ZQ',
        placeOfSupply: b.state || 'Karnataka',
        phone: b.phone || '9986890777',
        docLabel: isQuote ? 'Quotation No' : 'Bill No',
        docNumber: extractShortNumber(doc.number),
        fullNumber: doc.number || '',
        date: formatReceiptDate(doc.date || new Date().toISOString()),
        partyName: c.name || doc.customerName || 'Harshavardhan',
        partyPhone: c.phone || '',
        lines,
        subtotal: subtotalNum,
        discount: Number(doc.discount || 0),
        tax: Number(doc.tax || 0),
        transportAmount: Number(doc.transportAmount || 0),
        total: totalNum,
        paid: paidNum,
        due: dueNum,
        terms: [
          '1 Goods once sold will not be',
          '  taken back or exchanged',
          '2 No cash payments accepted'
        ],
        slogan: 'Improving Agriculture, Improving lives',
        footer: 'Thank you for your purchase'
      };
    },

    // Generates the EXACT 32-column text matching the user's photograph
    generateReceiptText(rawDoc, docType = 'invoice') {
      const doc = this.normalizeDoc(rawDoc, docType);
      const W = this.COLUMNS; // 32
      const sep = '-'.repeat(W);
      const lines = [];

      // 1. Header (Centered)
      lines.push(centerText(doc.title, W));
      lines.push(centerText(doc.businessName, W));

      // Address lines (wrapped and centered)
      const addrLines = (doc.address || '').split('\n');
      for (const rawLine of addrLines) {
        const wrapped = wrapText(rawLine, W);
        for (const wl of wrapped) {
          lines.push(centerText(wl, W));
        }
      }

      lines.push(''); // blank line
      if (doc.gstin) {
        lines.push(`GSTIN:${doc.gstin}`);
      }
      lines.push(''); // blank line
      lines.push(`Place of Supply:${doc.placeOfSupply}`);
      lines.push(`Mobile:${doc.phone}`);

      // 2. Separator
      lines.push(sep);

      // 3. Document details
      lines.push(`${doc.docLabel}:${doc.docNumber}`);
      lines.push(`Date:${doc.date}`);
      lines.push(`Party Details:${doc.partyName}`);

      lines.push(''); // blank line
      lines.push(sep);

      // 4. Items Table Header
      // Matches the photo:
      // Line A: No Item Description
      // Line B: Qty      MRP    Rate      Amount
      lines.push('No Item Description');
      lines.push('');
      // 32-col layout: Qty (7) + MRP (7) + Rate (8) + Amount (10) = 32
      const colHeader = padRight('Qty', 7) + padLeft('MRP', 7) + padLeft('Rate', 8) + padLeft('Amount', 10);
      lines.push(colHeader);
      lines.push(sep);

      // 5. Items Rows
      doc.lines.forEach((item) => {
        // Line 1: Item index + Item name (with leading space for single digits like in photo)
        const numPrefix = String(item.idx).padStart(2, ' ') + '  ';
        const nameLines = wrapText(item.name, W - numPrefix.length);
        if (nameLines.length === 0) {
          lines.push(numPrefix);
        } else {
          lines.push(numPrefix + nameLines[0]);
          for (let i = 1; i < nameLines.length; i++) {
            lines.push('    ' + nameLines[i]);
          }
        }

        // Line 2: Qty   MRP   Rate   Amount
        // e.g. "300.0P     0.0   375.0  112500.0"
        const col1 = padRight(item.qtyFormatted, 7);
        const col2 = padLeft(item.mrpFormatted, 7);
        const col3 = padLeft(item.rateFormatted, 8);
        const col4 = padLeft(item.amountFormatted, 10);
        lines.push(col1 + col2 + col3 + col4);
        lines.push(''); // blank line between items, matching photo
      });

      // 6. Subtotal
      lines.push(sep);
      const subtotalVal = '₹ ' + formatIndianNumber(doc.subtotal);
      const subtotalLabel = 'Subtotal:';
      const subtotalSpaces = Math.max(1, W - subtotalLabel.length - subtotalVal.length);
      lines.push(subtotalLabel + ' '.repeat(subtotalSpaces) + subtotalVal);

      // 7. Total Amount
      lines.push(sep);
      const totalVal = '₹ ' + formatIndianNumber(doc.total);
      const totalLabel = 'Total Amount:';
      const totalSpaces = Math.max(1, W - totalLabel.length - totalVal.length);
      lines.push(totalLabel + ' '.repeat(totalSpaces) + totalVal);
      lines.push(sep);

      // If Paid or Due are relevant (for bills)
      if (!doc.isQuote && doc.paid > 0) {
        const paidVal = '₹ ' + formatIndianNumber(doc.paid);
        const paidLabel = 'Paid Amount:';
        lines.push(paidLabel + ' '.repeat(Math.max(1, W - paidLabel.length - paidVal.length)) + paidVal);

        const dueVal = '₹ ' + formatIndianNumber(doc.due);
        const dueLabel = 'Balance Due:';
        lines.push(dueLabel + ' '.repeat(Math.max(1, W - dueLabel.length - dueVal.length)) + dueVal);
        lines.push(sep);
      }

      lines.push(''); // blank line

      // 8. Terms and Condition
      lines.push('Terms and Condition');
      for (const t of doc.terms) {
        lines.push(' ' + t);
      }

      lines.push(''); // blank line

      // 9. Slogan & Footer (Centered)
      // Wrap slogan to 2 lines like in image:
      // Line 1: Improving Agriculture, Improving
      // Line 2:              lives
      lines.push(centerText('Improving Agriculture, Improving', W));
      lines.push(centerText('lives', W));
      lines.push(centerText(doc.footer, W));

      return lines.join('\n');
    },

    // Generates HTML formatted specifically for 58mm CSS printing
    generateReceiptHtml(rawDoc, docType = 'invoice') {
      const doc = this.normalizeDoc(rawDoc, docType);
      const text = this.generateReceiptText(rawDoc, docType);

      return `
        <div class="seznik-58mm-thermal-roll" id="seznik-thermal-slip">
          <pre class="thermal-monospace-receipt">${this.escapeHtml(text)}</pre>
        </div>
      `;
    },

    escapeHtml(str) {
      return String(str || '').replace(/[&<>"']/g, c => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;'
      }[c]));
    },

    // Generates raw ESC/POS binary byte stream (Uint8Array)
    generateEscPosBytes(rawDoc, docType = 'invoice') {
      const doc = this.normalizeDoc(rawDoc, docType);
      const W = this.COLUMNS;
      const sep = '-'.repeat(W);
      const bytes = [];

      const addBytes = (...arr) => {
        for (let i = 0; i < arr.length; i++) {
          bytes.push(arr[i]);
        }
      };

      const addText = (str) => {
        // Standard ASCII / UTF-8 conversion
        const encoded = new TextEncoder().encode(str);
        for (let i = 0; i < encoded.length; i++) {
          // Replace rupee symbol '₹' with 'Rs.' for thermal printers lacking UTF-8 currency glyphs
          bytes.push(encoded[i]);
        }
      };

      const addLine = (str = '') => {
        // Replace unicode ₹ with Rs. in raw thermal printers to prevent garbage bytes
        const cleanStr = String(str).replace(/₹/g, 'Rs.');
        addText(cleanStr);
        addBytes(LF);
      };

      // 1. Initialize Printer
      addBytes(ESC, 0x40); // ESC @ (Reset)

      // 2. Select Character Code Table (PC437 Standard)
      addBytes(ESC, 0x74, 0x00);

      // 3. Header Title: Centered + Double Height / Bold
      addBytes(ESC, 0x61, 0x01); // Center Align
      addBytes(ESC, 0x45, 0x01); // Bold ON
      addBytes(ESC, 0x21, 0x10); // Double Height
      addLine(doc.title);
      addBytes(ESC, 0x21, 0x00); // Normal Font
      addLine(doc.businessName);
      addBytes(ESC, 0x45, 0x00); // Bold OFF

      // Address lines
      const addrLines = (doc.address || '').split('\n');
      for (const line of addrLines) {
        const wrapped = wrapText(line, W);
        for (const wl of wrapped) {
          addLine(wl);
        }
      }

      addLine('');
      if (doc.gstin) {
        addLine(`GSTIN:${doc.gstin}`);
      }
      addLine('');
      addLine(`Place of Supply:${doc.placeOfSupply}`);
      addLine(`Mobile:${doc.phone}`);

      // 4. Details Section (Left aligned)
      addBytes(ESC, 0x61, 0x00); // Left Align
      addLine(sep);
      addLine(`${doc.docLabel}:${doc.docNumber}`);
      addLine(`Date:${doc.date}`);
      addLine(`Party Details:${doc.partyName}`);
      addLine('');
      addLine(sep);

      // 5. Items Header
      addBytes(ESC, 0x45, 0x01); // Bold ON
      addLine('No Item Description');
      addLine('');
      const colHeader = padRight('Qty', 7) + padLeft('MRP', 7) + padLeft('Rate', 8) + padLeft('Amount', 10);
      addLine(colHeader);
      addBytes(ESC, 0x45, 0x00); // Bold OFF
      addLine(sep);

      // 6. Items
      doc.lines.forEach((item) => {
        addBytes(ESC, 0x45, 0x01); // Bold item title
        const numPrefix = String(item.idx).padStart(2, ' ') + '  ';
        const nameLines = wrapText(item.name, W - numPrefix.length);
        if (nameLines.length === 0) {
          addLine(numPrefix);
        } else {
          addLine(numPrefix + nameLines[0]);
          for (let i = 1; i < nameLines.length; i++) {
            addLine('    ' + nameLines[i]);
          }
        }
        addBytes(ESC, 0x45, 0x00); // Bold OFF

        const col1 = padRight(item.qtyFormatted, 7);
        const col2 = padLeft(item.mrpFormatted, 7);
        const col3 = padLeft(item.rateFormatted, 8);
        const col4 = padLeft(item.amountFormatted, 10);
        addLine(col1 + col2 + col3 + col4);
        addLine(''); // blank line
      });

      // 7. Subtotal
      addLine(sep);
      const subtotalVal = 'Rs. ' + formatIndianNumber(doc.subtotal);
      const subtotalLabel = 'Subtotal:';
      const subtotalSpaces = Math.max(1, W - subtotalLabel.length - subtotalVal.length);
      addLine(subtotalLabel + ' '.repeat(subtotalSpaces) + subtotalVal);

      // 8. Total Amount (Bold)
      addLine(sep);
      addBytes(ESC, 0x45, 0x01); // Bold ON
      const totalVal = 'Rs. ' + formatIndianNumber(doc.total);
      const totalLabel = 'Total Amount:';
      const totalSpaces = Math.max(1, W - totalLabel.length - totalVal.length);
      addLine(totalLabel + ' '.repeat(totalSpaces) + totalVal);
      addBytes(ESC, 0x45, 0x00); // Bold OFF
      addLine(sep);

      if (!doc.isQuote && doc.paid > 0) {
        const paidVal = 'Rs. ' + formatIndianNumber(doc.paid);
        const paidLabel = 'Paid Amount:';
        addLine(paidLabel + ' '.repeat(Math.max(1, W - paidLabel.length - paidVal.length)) + paidVal);

        const dueVal = 'Rs. ' + formatIndianNumber(doc.due);
        const dueLabel = 'Balance Due:';
        addLine(dueLabel + ' '.repeat(Math.max(1, W - dueLabel.length - dueVal.length)) + dueVal);
        addLine(sep);
      }

      addLine('');

      // 9. Terms and Condition
      addLine('Terms and Condition');
      for (const t of doc.terms) {
        addLine(' ' + t);
      }

      addLine('');

      // 10. Slogan & Footer (Centered)
      addBytes(ESC, 0x61, 0x01); // Center Align
      addLine('Improving Agriculture, Improving');
      addLine('lives');
      addBytes(ESC, 0x45, 0x01);
      addLine(doc.footer);
      addBytes(ESC, 0x45, 0x00);

      // 11. Feed 4 lines for easy tear-off
      addBytes(ESC, 0x64, 0x04);

      // 12. Optional paper cut (GS V 0)
      addBytes(GS, 0x56, 0x00);

      return new Uint8Array(bytes);
    },

    // Connect to Seznik 2POS / Bluetooth Thermal Printer via Web Bluetooth
    async connectBluetooth() {
      if (!navigator.bluetooth) {
        throw new Error('Web Bluetooth is not supported in this browser. Please use Google Chrome or Microsoft Edge on Android or Desktop, or use Android 1-Tap Print.');
      }

      isConnecting = true;
      this.notifyStatus({ isConnecting: true, connected: false, message: 'Searching for Seznik 2POS...' });

      try {
        // Request Bluetooth device with all common thermal printer GATT services
        btDevice = await navigator.bluetooth.requestDevice({
          acceptAllDevices: true,
          optionalServices: PRINTER_SERVICES
        });

        btDevice.addEventListener('gattserverdisconnected', () => {
          btCharacteristic = null;
          this.notifyStatus({ isConnecting: false, connected: false, deviceName: null, message: 'Printer disconnected' });
          if (window.toast) window.toast('Bluetooth printer disconnected', 'error');
        });

        const server = await btDevice.gatt.connect();

        // Search for writable characteristic among printer services
        let targetCharacteristic = null;

        for (const serviceUuid of PRINTER_SERVICES) {
          try {
            const service = await server.getPrimaryService(serviceUuid);
            const characteristics = await service.getCharacteristics();
            for (const char of characteristics) {
              if (char.properties.write || char.properties.writeWithoutResponse) {
                targetCharacteristic = char;
                break;
              }
            }
            if (targetCharacteristic) break;
          } catch (e) {
            // Service not supported on this printer, try next service
          }
        }

        // If not found in known UUIDs, inspect all available primary services
        if (!targetCharacteristic) {
          try {
            const services = await server.getPrimaryServices();
            for (const s of services) {
              try {
                const chars = await s.getCharacteristics();
                for (const char of chars) {
                  if (char.properties.write || char.properties.writeWithoutResponse) {
                    targetCharacteristic = char;
                    break;
                  }
                }
                if (targetCharacteristic) break;
              } catch (e) {}
            }
          } catch (e) {}
        }

        if (!targetCharacteristic) {
          throw new Error('Could not find writable printer characteristic on ' + (btDevice.name || 'Bluetooth device'));
        }

        btCharacteristic = targetCharacteristic;
        isConnecting = false;

        const info = {
          connected: true,
          isConnecting: false,
          deviceName: btDevice.name || 'Seznik 2POS Printer'
        };

        this.notifyStatus(info);
        if (window.toast) window.toast(`Connected to ${info.deviceName}!`);
        return info;
      } catch (err) {
        isConnecting = false;
        btCharacteristic = null;
        this.notifyStatus({ connected: false, isConnecting: false, deviceName: null });
        if (err.name !== 'NotFoundError') { // User didn't just cancel picker
          throw err;
        }
        return { connected: false, cancelled: true };
      }
    },

    // Disconnect current Bluetooth printer
    async disconnectBluetooth() {
      if (btDevice && btDevice.gatt && btDevice.gatt.connected) {
        btDevice.gatt.disconnect();
      }
      btDevice = null;
      btCharacteristic = null;
      isConnecting = false;
      this.notifyStatus({ connected: false, isConnecting: false, deviceName: null });
      if (window.toast) window.toast('Printer disconnected');
    },

    // Transmit ESC/POS byte buffer in safe chunks (100 bytes per packet) over Bluetooth
    async printViaBluetooth(rawDoc, docType = 'invoice') {
      if (!this.isConnected()) {
        const connectResult = await this.connectBluetooth();
        if (!connectResult || !connectResult.connected) {
          throw new Error('Please connect your Seznik 2POS Bluetooth printer first.');
        }
      }

      const bytes = this.generateEscPosBytes(rawDoc, docType);
      const CHUNK_SIZE = 100; // Safe chunk size for Bluetooth LE GATT packets

      for (let offset = 0; offset < bytes.length; offset += CHUNK_SIZE) {
        const chunk = bytes.slice(offset, offset + CHUNK_SIZE);
        if (btCharacteristic.writeValueWithoutResponse) {
          await btCharacteristic.writeValueWithoutResponse(chunk);
        } else {
          await btCharacteristic.writeValue(chunk);
        }
        // Small delay (25ms) between packets to avoid buffer overflow on printer
        await new Promise(r => setTimeout(r, 25));
      }

      if (window.toast) window.toast('Printed to Seznik 2POS successfully!');
      return true;
    },

    // Android 1-Tap Direct Print via RawBT app
    generateRawBtIntent(rawDoc, docType = 'invoice') {
      const bytes = this.generateEscPosBytes(rawDoc, docType);
      let binary = '';
      const len = bytes.byteLength;
      for (let i = 0; i < len; i++) {
        binary += String.fromCharCode(bytes[i]);
      }
      const b64 = typeof btoa !== 'undefined' ? btoa(binary) : Buffer.from(binary, 'binary').toString('base64');
      return 'rawbt:data:application/octet-stream;base64,' + b64;
    },

    printViaRawBt(rawDoc, docType = 'invoice') {
      const url = this.generateRawBtIntent(rawDoc, docType);
      if (typeof window !== 'undefined' && window.location) {
        window.location.href = url;
      }
      return url;
    },

    // System Print (window.print with CSS 58mm format)
    printViaBrowser(rawDoc, docType = 'invoice') {
      const html = this.generateReceiptHtml(rawDoc, docType);
      const printSheet = document.getElementById('print-sheet');
      if (printSheet) {
        document.body.dataset.printFormat = 'seznik58';
        printSheet.innerHTML = html;
        window.print();
      }
    },

    // Print a quick test receipt to verify connection and formatting
    async printTestReceipt() {
      const testDoc = {
        number: '487',
        date: '2026-09-20',
        customerName: 'Harshavardhan',
        businessSnapshot: {
          name: 'Darvi Group',
          address: '#2 totad building, Arjun Vihar,\nGokul road Hubli, Karnataka\n580030, India HUBLI',
          gstin: '29BKZPA0053M1ZQ',
          state: 'Karnataka',
          phone: '9986890777'
        },
        lines: [
          { name: 'Coconut Hybrid', qty: 300, unit: 'sapling', mrp: 0, price: 375, total: 112500 },
          { name: 'Sandalwood', qty: 20, unit: 'no', mrp: 40, price: 35, total: 700 },
          { name: 'Redsandal', qty: 20, unit: 'piece', mrp: 0, price: 50, total: 1000 },
          { name: 'Red Diamond Guava', qty: 80, unit: 'piece', mrp: 0, price: 80, total: 6400 }
        ],
        subtotal: 120600,
        total: 120600,
        paidAmount: 0
      };

      if (this.isConnected()) {
        return this.printViaBluetooth(testDoc, 'quotation');
      } else {
        this.printViaBrowser(testDoc, 'quotation');
      }
    }
  };

  global.DarviPrinter = DarviPrinter;
})(typeof window !== 'undefined' ? window : globalThis);
