/* thermal-printer.js — Darvi Billing v0.6
 * Authoritative Direct Thermal Printing & ESC/POS Engine
 *
 * Target Hardware:
 *   - Brand: Seznik
 *   - Model: SSEZNIK2POS / Seznik 2in1 Printer
 *   - Paper: 58mm Thermal Roll (32 Columns)
 *   - Connection: Android Bluetooth Classic SPP (Serial Port Profile)
 *
 * Architecture:
 *   HTML/CSS/JS UI
 *         │
 *         ▼
 *   DarviPrinter (Authoritative JavaScript Service)
 *         │
 *         ▼
 *   Capacitor Native Plugin (com.darvigroup.billing.BluetoothPrinterPlugin)
 *         │
 *         ▼
 *   Android BluetoothSocket RFCOMM (UUID: 00001101-0000-1000-8000-00805F9B34FB)
 *         │
 *         ▼
 *   SSEZNIK2POS Thermal Printer (<1KB binary bytecode, ~1s execution)
 */

(function (global) {
  'use strict';

  // ─────────────────────────────────────────────────────────────────
  // ESC/POS Constants & Helpers
  // ─────────────────────────────────────────────────────────────────
  const ESC = 0x1B;
  const GS  = 0x1D;
  const LF  = 0x0A;
  const W   = 32; // 32 characters per line for 58mm roll

  const padRight = (str, len) => {
    str = String(str ?? '');
    return str.length >= len ? str.slice(0, len) : str + ' '.repeat(len - str.length);
  };

  const padLeft = (str, len) => {
    str = String(str ?? '');
    return str.length >= len ? str.slice(0, len) : ' '.repeat(len - str.length) + str;
  };

  const centerText = (str, width = W) => {
    str = String(str ?? '').trim();
    if (str.length >= width) return str.slice(0, width);
    const leftPad = Math.floor((width - str.length) / 2);
    const rightPad = width - str.length - leftPad;
    return ' '.repeat(leftPad) + str + ' '.repeat(rightPad);
  };

  const wrapText = (str, width = W) => {
    str = String(str ?? '').trim();
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
    const u = String(unit ?? '').toLowerCase().trim();
    if (u === 'sapling' || u === 'plant') return 'P';
    if (u === 'piece' || u === 'item' || u === 'set') return 'PC';
    if (u === 'no' || u === 'nos' || u === 'number') return 'NO';
    if (u === 'kg') return 'KG';
    if (u === 'bag' || u === 'bags') return 'BG';
    if (u.length <= 2) return u.toUpperCase();
    return u.slice(0, 2).toUpperCase();
  };

  const formatIndianNumber = (num) => {
    return Number(num || 0).toLocaleString('en-IN', { maximumFractionDigits: 2 });
  };

  const formatReceiptDate = (dateStr) => {
    const d = new Date(String(dateStr || '').slice(0, 10) + 'T12:00:00');
    if (isNaN(d)) return String(dateStr || '');
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'June', 'July', 'Aug', 'Sept', 'Oct', 'Nov', 'Dec'];
    return `${d.getDate()} ${months[d.getMonth()]} ${d.getFullYear()}`;
  };

  const extractShortNumber = (rawNum, defaultVal = '001') => {
    if (!rawNum) return defaultVal;
    const match = String(rawNum).match(/(\d+)$/);
    if (match) {
      const parsed = parseInt(match[1], 10);
      return parsed > 0 ? String(parsed) : rawNum;
    }
    return String(rawNum);
  };

  // ─────────────────────────────────────────────────────────────────
  // Single Shared Receipt Formatter
  // ─────────────────────────────────────────────────────────────────
  const DarviReceiptFormatter = {
    normaliseDoc(rawDoc, docType = 'invoice') {
      const isQuote = docType === 'quotation';
      const b = rawDoc.businessSnapshot || (rawDoc.business || {});
      const c = rawDoc.customerSnapshot || {};

      const lines = (rawDoc.lines || []).map(l => {
        const qtyNum = Number(l.qty || 0);
        const rateNum = Number(l.price || 0);
        const mrpNum = Number(l.mrp || 0);
        const totalNum = Number(l.total || (qtyNum * rateNum));

        return {
          name: String(l.name || 'Item'),
          qty: qtyNum,
          unit: formatUnit(l.unit || 'PC'),
          qtyFormatted: `${qtyNum} ${formatUnit(l.unit || 'PC')}`,
          mrp: mrpNum,
          mrpFormatted: mrpNum > 0 ? mrpNum.toFixed(2) : '-',
          rate: rateNum,
          rateFormatted: rateNum.toFixed(2),
          total: totalNum,
          amountFormatted: totalNum.toFixed(2)
        };
      });

      const subtotalNum = Number(rawDoc.subtotal || rawDoc.taxable || lines.reduce((s, x) => s + x.total, 0));
      const totalNum = Number(rawDoc.total || subtotalNum);
      const paidNum = Number(rawDoc.paidAmount || 0);
      const dueNum = Math.max(0, totalNum - paidNum);

      return {
        isQuote,
        title: isQuote ? 'QUOTATION' : 'TAX INVOICE',
        businessName: b.name || 'Darvi Group',
        address: b.address || '#2 totad building, Arjun Vihar,\nGokul road Hubli, Karnataka\n580030, India HUBLI',
        gstin: b.gstin || '',
        placeOfSupply: b.state || 'Karnataka',
        phone: b.phone || '',
        docLabel: isQuote ? 'Quotation No' : 'Bill No',
        docNumber: extractShortNumber(rawDoc.number),
        fullNumber: rawDoc.number || '',
        date: formatReceiptDate(rawDoc.date || new Date().toISOString()),
        partyName: c.name || rawDoc.customerName || 'Walk-in Customer',
        partyPhone: c.phone || '',
        partyAddress: c.address || '',
        customerRef: rawDoc.customerInvoiceRef || '',
        lines,
        subtotal: subtotalNum,
        discount: Number(rawDoc.discount || 0),
        tax: Number(rawDoc.tax || 0),
        transportAmount: Number(rawDoc.transportAmount || 0),
        total: totalNum,
        paid: paidNum,
        due: dueNum,
        paymentMethod: rawDoc.paymentMethod || 'Cash',
        terms: [
          'Goods once sold will not be taken back',
          'or exchanged.',
          'No cash payments accepted.'
        ],
        slogan: 'Improving Agriculture, Improving lives',
        footer: 'Thank you for your purchase'
      };
    },

    // Generates compact ESC/POS byte array (Uint8Array)
    generateEscPosBytes(rawDoc, docType = 'invoice') {
      const doc = this.normaliseDoc(rawDoc, docType);
      const bytes = [];
      const addBytes = (...b) => bytes.push(...b);

      const addText = (text) => {
        const s = String(text ?? '');
        for (let i = 0; i < s.length; i++) {
          let code = s.charCodeAt(i);
          if (code === 0x20B9) { // Indian Rupee Symbol '₹' -> 'Rs.'
            addBytes(0x52, 0x73, 0x2E);
          } else if (code === 0x00D7) { // '×' -> 'x'
            addBytes(0x78);
          } else if (code === 0x2014 || code === 0x2013) { // '—' -> '-'
            addBytes(0x2D);
          } else if (code === 0x00B7) { // '·' -> '-'
            addBytes(0x2D);
          } else if (code >= 32 && code <= 126) {
            addBytes(code);
          } else if (code === 10 || code === 13) {
            addBytes(code);
          } else {
            addBytes(0x20); // Space for unsupported glyphs
          }
        }
      };

      const addLine = (text) => {
        addText(text);
        addBytes(0x0D, 0x0A); // CR LF: required to immediately print and advance line
      };

      const sep = '-'.repeat(W);

      // 1. Initialize Printer (ESC @)
      addBytes(ESC, 0x40);

      // 2. Character set standard PC437 (ESC t 0)
      addBytes(ESC, 0x74, 0x00);

      // 3. Receipt Title & Business Name (Centered, Bold)
      addBytes(ESC, 0x61, 0x01); // Center Align
      addBytes(GS, 0x21, 0x01);  // Double-height
      addBytes(ESC, 0x45, 0x01); // Bold ON
      addLine(doc.title);
      addBytes(GS, 0x21, 0x00);  // Normal font
      addLine(doc.businessName);
      addBytes(ESC, 0x45, 0x00); // Bold OFF

      // 4. Business Address (Split on newlines)
      const addrLines = (doc.address || '').split('\n');
      for (const al of addrLines) {
        if (al.trim()) addLine(al.trim());
      }

      addLine('');
      if (doc.gstin) addLine('GSTIN: ' + doc.gstin);
      addLine('Place of Supply: ' + doc.placeOfSupply);
      if (doc.phone) addLine('Mobile: ' + doc.phone);

      // 5. Invoice & Customer Meta (Left Align)
      addBytes(ESC, 0x61, 0x00); // Left Align
      addLine(sep);
      addLine(doc.docLabel + ': ' + doc.docNumber);
      addLine('Date: ' + doc.date);
      addLine('Party: ' + doc.partyName);
      if (doc.partyPhone) addLine('Phone: ' + doc.partyPhone);
      if (doc.customerRef) addLine('Ref: ' + doc.customerRef);
      addLine(sep);

      // 6. Items Table Header
      // Layout: Qty(7) | MRP(7) | Rate(8) | Amount(10) = 32 chars
      addLine('Particulars');
      addLine('Qty    MRP     Rate     Amount');
      addLine(sep);

      // 7. Line Items
      doc.lines.forEach((item, idx) => {
        addBytes(ESC, 0x45, 0x01); // Bold ON for product name
        const numPrefix = (idx + 1) + '. ';
        const nameLines = wrapText(item.name, W - numPrefix.length);
        if (nameLines.length === 0) {
          addLine(numPrefix);
        } else {
          addLine(numPrefix + nameLines[0]);
          for (let i = 1; i < nameLines.length; i++) {
            addLine('   ' + nameLines[i]);
          }
        }
        addBytes(ESC, 0x45, 0x00); // Bold OFF

        const col1 = padRight(item.qtyFormatted, 7);
        const col2 = padLeft(item.mrpFormatted, 7);
        const col3 = padLeft(item.rateFormatted, 8);
        const col4 = padLeft(item.amountFormatted, 10);
        addLine(col1 + col2 + col3 + col4);
      });

      // 8. Totals
      addLine(sep);
      const subtotalVal = 'Rs. ' + formatIndianNumber(doc.subtotal);
      addLine('Subtotal:' + ' '.repeat(Math.max(1, W - 9 - subtotalVal.length)) + subtotalVal);

      if (doc.discount > 0) {
        const discVal = '-Rs. ' + formatIndianNumber(doc.discount);
        addLine('Discount:' + ' '.repeat(Math.max(1, W - 9 - discVal.length)) + discVal);
      }
      if (doc.tax > 0) {
        const taxVal = 'Rs. ' + formatIndianNumber(doc.tax);
        addLine('Tax/GST:' + ' '.repeat(Math.max(1, W - 8 - taxVal.length)) + taxVal);
      }
      if (doc.transportAmount > 0) {
        const transVal = 'Rs. ' + formatIndianNumber(doc.transportAmount);
        addLine('Transport:' + ' '.repeat(Math.max(1, W - 10 - transVal.length)) + transVal);
      }

      addLine(sep);
      addBytes(ESC, 0x45, 0x01); // Bold ON for Grand Total
      const totalVal = 'Rs. ' + formatIndianNumber(doc.total);
      addLine('Total Amount:' + ' '.repeat(Math.max(1, W - 13 - totalVal.length)) + totalVal);
      addBytes(ESC, 0x45, 0x00); // Bold OFF
      addLine(sep);

      // Payment Status (invoices)
      if (!doc.isQuote && doc.paid > 0) {
        const paidVal = 'Rs. ' + formatIndianNumber(doc.paid);
        const paidLbl = 'Paid (' + doc.paymentMethod + '):';
        addLine(paidLbl + ' '.repeat(Math.max(1, W - paidLbl.length - paidVal.length)) + paidVal);
        const dueVal = 'Rs. ' + formatIndianNumber(doc.due);
        addLine('Balance Due:' + ' '.repeat(Math.max(1, W - 12 - dueVal.length)) + dueVal);
        addLine(sep);
      }

      // 9. Terms and Conditions
      addLine('');
      addLine('Terms & Conditions:');
      for (const t of doc.terms) {
        const wrapped = wrapText(t, W - 2);
        for (const wl of wrapped) {
          addLine('  ' + wl);
        }
      }

      // 10. Footer (Centered)
      addLine('');
      addBytes(ESC, 0x61, 0x01); // Center Align
      addLine(doc.slogan);
      addBytes(ESC, 0x45, 0x01);
      addLine(doc.footer);
      addBytes(ESC, 0x45, 0x00);

      // 11. Feed 4 lines for easy tear-off and flush buffer
      addLine('');
      addLine('');
      addLine('');
      addLine('');
      addBytes(ESC, 0x64, 0x04);

      return new Uint8Array(bytes);
    },

    // Formats plain monospace text (for terminal debug or preview)
    generateReceiptText(rawDoc, docType = 'invoice') {
      const doc = this.normaliseDoc(rawDoc, docType);
      const lines = [];
      const sep = '-'.repeat(W);

      lines.push(centerText(doc.title));
      lines.push(centerText(doc.businessName));
      (doc.address || '').split('\n').forEach(l => { if (l.trim()) lines.push(centerText(l.trim())); });
      lines.push('');
      if (doc.gstin) lines.push(centerText('GSTIN: ' + doc.gstin));
      lines.push(centerText('Place of Supply: ' + doc.placeOfSupply));
      if (doc.phone) lines.push(centerText('Mobile: ' + doc.phone));
      lines.push(sep);
      lines.push(doc.docLabel + ': ' + doc.docNumber);
      lines.push('Date: ' + doc.date);
      lines.push('Party: ' + doc.partyName);
      if (doc.partyPhone) lines.push('Phone: ' + doc.partyPhone);
      lines.push(sep);
      lines.push('Particulars');
      lines.push('Qty    MRP     Rate     Amount');
      lines.push(sep);

      doc.lines.forEach((item, idx) => {
        const numPrefix = (idx + 1) + '. ';
        const nameLines = wrapText(item.name, W - numPrefix.length);
        lines.push(numPrefix + (nameLines[0] || ''));
        for (let i = 1; i < nameLines.length; i++) lines.push('   ' + nameLines[i]);
        lines.push(padRight(item.qtyFormatted, 7) + padLeft(item.mrpFormatted, 7) + padLeft(item.rateFormatted, 8) + padLeft(item.amountFormatted, 10));
      });

      lines.push(sep);
      const subtotalVal = 'Rs. ' + formatIndianNumber(doc.subtotal);
      lines.push('Subtotal:' + ' '.repeat(Math.max(1, W - 9 - subtotalVal.length)) + subtotalVal);
      if (doc.discount > 0) {
        const dVal = '-Rs. ' + formatIndianNumber(doc.discount);
        lines.push('Discount:' + ' '.repeat(Math.max(1, W - 9 - dVal.length)) + dVal);
      }
      if (doc.tax > 0) {
        const tVal = 'Rs. ' + formatIndianNumber(doc.tax);
        lines.push('Tax/GST:' + ' '.repeat(Math.max(1, W - 8 - tVal.length)) + tVal);
      }
      if (doc.transportAmount > 0) {
        const trVal = 'Rs. ' + formatIndianNumber(doc.transportAmount);
        lines.push('Transport:' + ' '.repeat(Math.max(1, W - 10 - trVal.length)) + trVal);
      }
      lines.push(sep);
      const totalVal = 'Rs. ' + formatIndianNumber(doc.total);
      lines.push('Total Amount:' + ' '.repeat(Math.max(1, W - 13 - totalVal.length)) + totalVal);
      lines.push(sep);

      if (!doc.isQuote && doc.paid > 0) {
        const pVal = 'Rs. ' + formatIndianNumber(doc.paid);
        const pLbl = 'Paid (' + doc.paymentMethod + '):';
        lines.push(pLbl + ' '.repeat(Math.max(1, W - pLbl.length - pVal.length)) + pVal);
        const duVal = 'Rs. ' + formatIndianNumber(doc.due);
        lines.push('Balance Due:' + ' '.repeat(Math.max(1, W - 12 - duVal.length)) + duVal);
        lines.push(sep);
      }

      lines.push('');
      lines.push('Terms & Conditions:');
      doc.terms.forEach(t => {
        const wrapped = wrapText(t, W - 2);
        wrapped.forEach(wl => lines.push('  ' + wl));
      });
      lines.push('');
      lines.push(centerText(doc.slogan));
      lines.push(centerText(doc.footer));
      lines.push('');
      lines.push('');

      return lines.join('\n');
    },

    // Monospace HTML for preview pane
    generateReceiptHtml(rawDoc, docType = 'invoice') {
      const text = this.generateReceiptText(rawDoc, docType);
      const escaped = text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
      return `<pre class="receipt-monotext" style="font-family:'Courier New',Courier,monospace;font-size:12px;line-height:1.2;white-space:pre;background:#fff;color:#000;padding:12px;margin:0;">${escaped}</pre>`;
    }
  };

  // ─────────────────────────────────────────────────────────────────
  // Authoritative Printer Service (DarviPrinter)
  // ─────────────────────────────────────────────────────────────────
  const STATUS = {
    BLUETOOTH_UNAVAILABLE: 'BluetoothUnavailable',
    PERMISSION_REQUIRED:   'PermissionRequired',
    BLUETOOTH_DISABLED:    'BluetoothDisabled',
    SEARCHING:             'Searching',
    PRINTER_NOT_PAIRED:    'PrinterNotPaired',
    PRINTER_FOUND:         'PrinterFound',
    CONNECTING:            'Connecting',
    CONNECTED:             'Connected',
    PRINTING:              'Printing',
    DISCONNECTED:          'Disconnected',
    CONNECTION_FAILED:     'ConnectionFailed'
  };

  let currentState = STATUS.DISCONNECTED;
  let connectedDeviceName = null;
  let connectedDeviceAddress = null;
  let lastErrorMessage = null;
  const statusListeners = [];

  function notifyStatusListeners() {
    const s = DarviPrinter.getStatus();
    statusListeners.forEach(fn => {
      try { fn(s); } catch (e) { console.error('Status listener error:', e); }
    });
  }

  function setPrinterState(state, devName = null, devAddr = null, error = null) {
    currentState = state;
    if (devName !== null) connectedDeviceName = devName;
    if (devAddr !== null) connectedDeviceAddress = devAddr;
    if (error !== null) lastErrorMessage = error;
    if (state === STATUS.DISCONNECTED || state === STATUS.CONNECTION_FAILED) {
      connectedDeviceName = null;
      connectedDeviceAddress = null;
    }
    notifyStatusListeners();
  }

  function getNativePlugin() {
    if (typeof window !== 'undefined' && window.Capacitor && window.Capacitor.isNativePlatform()) {
      return window.Capacitor.Plugins.BluetoothPrinter;
    }
    return null;
  }

  function encodeBytesAsBase64(bytes) {
    if (typeof global.btoa !== 'function') {
      throw new Error('Base64 encoding is not available in this WebView.');
    }

    let binary = '';
    const chunkSize = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
    }
    return global.btoa(binary);
  }

  const DarviPrinter = {
    STATUS,

    isNative() {
      return !!getNativePlugin();
    },

    isConnected() {
      return currentState === STATUS.CONNECTED && connectedDeviceName !== null;
    },

    getDeviceName() {
      return connectedDeviceName || (this.isConnected() ? 'SSEZNIK2POS' : null);
    },

    getDeviceAddress() {
      return connectedDeviceAddress;
    },

    getStatus() {
      return {
        state: currentState,
        isConnected: this.isConnected(),
        deviceName: connectedDeviceName,
        deviceAddress: connectedDeviceAddress,
        isNative: this.isNative(),
        error: lastErrorMessage
      };
    },

    onStatusChange(callback) {
      if (typeof callback === 'function') {
        statusListeners.push(callback);
        // Call immediately with current status
        try { callback(this.getStatus()); } catch (_) {}
      }
      return () => {
        const idx = statusListeners.indexOf(callback);
        if (idx !== -1) statusListeners.splice(idx, 1);
      };
    },

    async checkStatus() {
      const plugin = getNativePlugin();
      if (!plugin) {
        // In browser / development mode
        return this.getStatus();
      }
      try {
        const res = await plugin.getStatus();
        if (res) {
          if (res.isConnected) {
            setPrinterState(STATUS.CONNECTED, res.deviceName, res.deviceAddress);
          } else {
            setPrinterState(res.state || STATUS.DISCONNECTED, null, null, res.error);
          }
        }
      } catch (err) {
        setPrinterState(STATUS.DISCONNECTED, null, null, err.message || String(err));
      }
      return this.getStatus();
    },

    async listPairedDevices() {
      const plugin = getNativePlugin();
      if (!plugin) {
        return [];
      }
      try {
        const res = await plugin.listPairedDevices();
        return res?.devices || [];
      } catch (err) {
        console.error('listPairedDevices error:', err);
        throw err;
      }
    },

    async connect(target = {}) {
      const plugin = getNativePlugin();
      if (!plugin) {
        setPrinterState(STATUS.DISCONNECTED, null, null, 'Bluetooth Classic SPP requires running inside the Darvi Billing Android application.');
        throw new Error('Bluetooth Classic SPP is only available in the Android Native Application. In browser mode, use USB Serial or Print Preview.');
      }

      setPrinterState(STATUS.CONNECTING);
      try {
        const res = await plugin.connect({
          address: target.address || null,
          name: target.name || 'SSEZNIK2POS'
        });
        if (res && res.ok) {
          setPrinterState(STATUS.CONNECTED, res.deviceName || 'SSEZNIK2POS', res.deviceAddress);
          return { ok: true, deviceName: res.deviceName, deviceAddress: res.deviceAddress };
        } else {
          throw new Error('Connection failed');
        }
      } catch (err) {
        const msg = err.message || (typeof err === 'string' ? err : 'Connection failed');
        setPrinterState(STATUS.CONNECTION_FAILED, null, null, msg);
        throw new Error(msg);
      }
    },

    async disconnect() {
      const plugin = getNativePlugin();
      if (plugin) {
        try {
          await plugin.disconnect();
        } catch (_) {}
      }
      setPrinterState(STATUS.DISCONNECTED);
      return { ok: true };
    },

    async printBytes(bytesUint8Array) {
      const plugin = getNativePlugin();
      if (!plugin) {
        throw new Error('Native Bluetooth driver not available in desktop browser.');
      }

      setPrinterState(STATUS.PRINTING, connectedDeviceName, connectedDeviceAddress);
      try {
        const base64 = encodeBytesAsBase64(bytesUint8Array);
        const startTime = Date.now();
        const res = await plugin.printBytes({ base64 });
        const duration = res?.durationMs || (Date.now() - startTime);
        const bytesWritten = Number(res?.bytesWritten);
        if (!Number.isSafeInteger(bytesWritten) || bytesWritten !== bytesUint8Array.length) {
          throw new Error(`Incomplete printer write: sent ${bytesWritten || 0} of ${bytesUint8Array.length} bytes.`);
        }

        setPrinterState(STATUS.CONNECTED, connectedDeviceName, connectedDeviceAddress);
        return {
          ok: true,
          bytesWritten,
          deviceName: res?.deviceName || connectedDeviceName,
          durationMs: duration
        };
      } catch (err) {
        const msg = err.message || 'Write failed';
        setPrinterState(STATUS.DISCONNECTED, null, null, msg);
        throw new Error(msg);
      }
    },

    async printInvoice(rawDoc, docType = 'invoice', preferredTransport = 'auto') {
      if (!rawDoc) throw new Error('No document provided for printing.');
      if (!rawDoc.lines || rawDoc.lines.length === 0) {
        throw new Error('Cannot print receipt: document has no items.');
      }

      if (preferredTransport === 'cleanter') {
        if (typeof window === 'undefined' || !window.CleanterPrinter) {
          throw new Error('Cleanter bridge is not available.');
        }
        try {
          const status = await window.CleanterPrinter.checkBridge();
          if (!status.reachable) {
            throw new Error(status.message || 'Cleanter bridge is not reachable.');
          }
          const startedAt = Date.now();
          const cleanterRes = await window.CleanterPrinter.printInvoice(rawDoc, docType);
          if (!cleanterRes.ok) {
            throw new Error(cleanterRes.message || 'Cleanter print failed.');
          }
          return {
            ...cleanterRes,
            transport: 'cleanter',
            durationMs: Date.now() - startedAt,
            message: 'Receipt sent via Cleanter (Seznik 2POS)'
          };
        } catch (cleanterErr) {
          const msg = cleanterErr.message || String(cleanterErr);
          throw new Error(msg);
        }
      }

      // Use Android's Bluetooth socket directly in the app; do not silently reroute a failed write.
      if (this.isNative() && preferredTransport !== 'browser') {
        if (!this.isConnected()) {
          await this.connect();
        }
        const plugin = getNativePlugin();
        setPrinterState(STATUS.PRINTING, connectedDeviceName, connectedDeviceAddress);
        let result;
        try {
          result = await plugin.printReceipt({ document: rawDoc, docType });
          if (!Number.isSafeInteger(Number(result?.bytesWritten)) || Number(result.bytesWritten) <= 0) {
            throw new Error('Android did not confirm writing receipt bytes to the Bluetooth socket.');
          }
          setPrinterState(STATUS.CONNECTED, connectedDeviceName, connectedDeviceAddress);
        } catch (err) {
          const msg = err.message || 'Native receipt print failed';
          setPrinterState(STATUS.DISCONNECTED, null, null, msg);
          throw new Error(msg);
        }
        return {
          ok: true,
          transport: 'bluetooth_spp',
          bytesWritten: Number(result.bytesWritten),
          durationMs: Number(result.durationMs) || 0,
          deviceName: result.deviceName || connectedDeviceName,
          message: `Android formatted and sent ${result.bytesWritten} receipt bytes via Bluetooth SPP`
        };
      }

      if (preferredTransport === 'native') {
        throw new Error('Native Bluetooth printing is only available in the Android application.');
      }

      // Keep the optional Cleanter bridge for browser clients, but never let it override native SPP.
      if (preferredTransport === 'auto' && typeof window !== 'undefined' && window.CleanterPrinter) {
        try {
          return await this.printInvoice(rawDoc, docType, 'cleanter');
        } catch (cleanterErr) {
          console.warn('[DarviPrinter] Cleanter bridge unavailable; using browser print instead:', cleanterErr);
        }
      }

      // Priority 3: Web Serial if on Desktop/Laptop USB
      if (typeof navigator !== 'undefined' && navigator.serial && preferredTransport === 'serial') {
        return this.printViaSerial(rawDoc, docType);
      }

      // Priority 4: Direct System Thermal Print Spooler (no 3rd party apps needed!)
      // Exact 58mm / 32-column receipt layout printed directly via system dialog
      this.printViaBrowser(rawDoc, docType);
      return {
        ok: true,
        transport: 'system_print',
        fallback: true,
        message: 'Opened 58mm thermal receipt print preview'
      };
    },

    async printViaSerial(rawDoc, docType = 'invoice') {
      if (typeof navigator === 'undefined' || !navigator.serial) {
        throw new Error('Web Serial is not supported in this browser.');
      }
      const port = await navigator.serial.requestPort();
      await port.open({ baudRate: 9600 });
      const writer = port.writable.getWriter();
      const bytes = DarviReceiptFormatter.generateEscPosBytes(rawDoc, docType);
      await writer.write(bytes);
      writer.releaseLock();
      await port.close();
      return { ok: true, bytesWritten: bytes.length };
    },

    printViaBrowser(rawDoc, docType = 'invoice') {
      const html = DarviReceiptFormatter.generateReceiptHtml(rawDoc, docType);
      const printSheet = document.getElementById('print-sheet');
      if (printSheet) {
        document.body.dataset.printFormat = 'seznik58';
        printSheet.innerHTML = html;
        const resetPrint = () => {
          document.body.removeAttribute('data-print-format');
          window.removeEventListener('afterprint', resetPrint);
        };
        window.addEventListener('afterprint', resetPrint);
        window.print();
        setTimeout(resetPrint, 2500);
      }
    },

    async testPrint() {
      const line = 'DARVI SPP TEST\r\n';
      const bytes = Uint8Array.from([
        ...Array.from(line, char => char.charCodeAt(0)),
        0x0A, 0x0A, 0x0A
      ]);

      if (this.isNative()) {
        if (!this.isConnected()) await this.connect();
        const plugin = getNativePlugin();
        setPrinterState(STATUS.PRINTING, connectedDeviceName, connectedDeviceAddress);
        try {
          const result = await plugin.testPrint();
          if (!Number.isSafeInteger(Number(result?.bytesWritten)) || Number(result.bytesWritten) <= 0) {
            throw new Error('Android did not confirm writing diagnostic bytes to the Bluetooth socket.');
          }
          setPrinterState(STATUS.CONNECTED, connectedDeviceName, connectedDeviceAddress);
          return {
            ...result,
            bytesWritten: Number(result.bytesWritten),
            transport: 'bluetooth_spp',
            diagnostic: true
          };
        } catch (err) {
          const msg = err.message || 'Native diagnostic print failed';
          setPrinterState(STATUS.DISCONNECTED, null, null, msg);
          throw new Error(msg);
        }
      }

      if (typeof navigator !== 'undefined' && navigator.serial) {
        const port = await navigator.serial.requestPort();
        await port.open({ baudRate: 9600 });
        const writer = port.writable.getWriter();
        try {
          await writer.write(bytes);
        } finally {
          writer.releaseLock();
          await port.close();
        }
        return { ok: true, bytesWritten: bytes.length, transport: 'serial', diagnostic: true };
      }

      throw new Error('A native SPP connection or USB serial printer is required for the diagnostic test.');
    },

    async feedPaper(lines = 4) {
      const plugin = getNativePlugin();
      if (plugin && this.isConnected()) {
        return plugin.feed({ lines });
      }
      // If Web Serial
      if (typeof navigator !== 'undefined' && navigator.serial) {
        const feedBytes = new Uint8Array([ESC, 0x64, lines]);
        const port = await navigator.serial.requestPort();
        await port.open({ baudRate: 9600 });
        const writer = port.writable.getWriter();
        await writer.write(feedBytes);
        writer.releaseLock();
        await port.close();
        return { ok: true };
      }
      throw new Error('Printer not connected');
    },

    // Formatter helpers exposed
    formatReceipt: (doc, type) => DarviReceiptFormatter.normaliseDoc(doc, type),
    generateEscPosBytes: (doc, type) => DarviReceiptFormatter.generateEscPosBytes(doc, type),
    generateReceiptText: (doc, type) => DarviReceiptFormatter.generateReceiptText(doc, type),
    generateReceiptHtml: (doc, type) => DarviReceiptFormatter.generateReceiptHtml(doc, type)
  };

  // Export globally and for Node.js test environment
  global.DarviPrinter = DarviPrinter;
  global.DarviReceiptFormatter = DarviReceiptFormatter;

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { DarviPrinter, DarviReceiptFormatter };
  }

  // Periodic status poll in native app to detect Bluetooth state changes
  if (typeof window !== 'undefined') {
    window.addEventListener('DOMContentLoaded', () => {
      DarviPrinter.checkStatus().catch(() => {});
    });
  }
})(typeof window !== 'undefined' ? window : globalThis);
