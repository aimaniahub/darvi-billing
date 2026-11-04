/* cleanter-printer.js — Darvi Billing v0.6
 * Authoritative Cleanter HTTP Bridge Module
 *
 * Architecture:
 *   Android Chrome (opens invoice site) → this module → GET/POST localhost:9100
 *   → Cleanter app on Android → Bluetooth Classic SPP → SSEZNIK2POS printer
 *
 * ALL Cleanter calls are centralised here. No other file should call
 * localhost:9100 directly.
 *
 * Cleanter API (http://localhost:9100):
 *   GET  /health   — Check bridge and printer status
 *   POST /print    — Submit a print job
 *
 * Payload format for POST /print:
 *   { cut: true, content: [ ...blocks ] }
 *
 * Supported block types: text | row | divider | feed
 * Text/row block properties: align ("left"|"center"|"right"), bold, size ("normal"|"large")
 */

(function (global) {
  'use strict';

  // ─────────────────────────────────────────────────────────────────
  // Configuration
  // ─────────────────────────────────────────────────────────────────

  // Dual-endpoint support: 127.0.0.1 is tried first to avoid Android Chrome IPv6
  // DNS lookup delays on "localhost", with localhost as fallback.
  const BRIDGE_HOSTS = ['http://localhost:9100', 'http://127.0.0.1:9100'];
  let activeBridgeHost = 'http://localhost:9100';
  const HEALTH_TIMEOUT_MS = 2500;
  const PRINT_TIMEOUT_MS  = 10000;

  // 58mm / 32-column receipt width (characters per line in plain-text fallback)
  const RECEIPT_COLUMNS = 32;

  // ─────────────────────────────────────────────────────────────────
  // Fetch with AbortController timeout & automatic loopback fallback
  // ─────────────────────────────────────────────────────────────────

  async function fetchWithTimeout(url, options, timeoutMs) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const res = await fetch(url, { ...options, signal: controller.signal });
      return res;
    } finally {
      clearTimeout(timer);
    }
  }

  async function requestBridge(path, options, timeoutMs) {
    // 1. Try currently active host (127.0.0.1:9100 by default)
    try {
      const res = await fetchWithTimeout(activeBridgeHost + path, options, timeoutMs);
      return { ok: true, res, host: activeBridgeHost };
    } catch (err1) {
      // 2. Try alternative loopback host (localhost:9100)
      const altHost = BRIDGE_HOSTS.find(h => h !== activeBridgeHost) || 'http://localhost:9100';
      try {
        const res2 = await fetchWithTimeout(altHost + path, options, timeoutMs);
        activeBridgeHost = altHost; // remember successful host
        return { ok: true, res: res2, host: altHost };
      } catch (err2) {
        return { ok: false, err: err1 };
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────
  // Receipt Formatting Helpers
  // ─────────────────────────────────────────────────────────────────

  function padRight(str, len) {
    str = String(str || '');
    return str.length >= len ? str.slice(0, len) : str + ' '.repeat(len - str.length);
  }

  function padLeft(str, len) {
    str = String(str || '');
    return str.length >= len ? str.slice(0, len) : ' '.repeat(len - str.length) + str;
  }

  function formatIndianNumber(num) {
    return Number(num || 0).toLocaleString('en-IN', { maximumFractionDigits: 2 });
  }

  function formatReceiptDate(dateStr) {
    const d = new Date(String(dateStr || '').slice(0, 10) + 'T12:00:00');
    if (isNaN(d)) return String(dateStr || '');
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'June', 'July', 'Aug', 'Sept', 'Oct', 'Nov', 'Dec'];
    return `${d.getDate()} ${months[d.getMonth()]} ${d.getFullYear()}`;
  }

  function extractShortNumber(rawNum) {
    if (!rawNum) return '';
    const match = String(rawNum).match(/(\d+)$/);
    if (match) {
      const parsed = parseInt(match[1], 10);
      return parsed > 0 ? String(parsed) : rawNum;
    }
    return String(rawNum);
  }

  function formatUnit(unit) {
    const u = String(unit || '').toLowerCase().trim();
    if (u === 'sapling' || u === 'plant') return 'P';
    if (u === 'piece' || u === 'item' || u === 'set') return 'PC';
    if (u === 'no' || u === 'nos' || u === 'number') return 'NO';
    if (u === 'kg') return 'KG';
    if (u === 'bag' || u === 'bags') return 'BG';
    if (u.length <= 2) return u.toUpperCase();
    return u.slice(0, 2).toUpperCase();
  }

  // ─────────────────────────────────────────────────────────────────
  // Normalise invoice / estimate document into a flat print object
  // (mirrors DarviPrinter.normalizeDoc in thermal-printer.js but
  //  is kept independent so this module has no dependency)
  // ─────────────────────────────────────────────────────────────────

  function normaliseDoc(rawDoc, docType) {
    const isQuote = docType === 'quotation' || docType === 'estimate';
    const b = rawDoc.businessSnapshot ||
      (global.store && global.store.businesses &&
       global.store.businesses[global.store.activeBusinessId] &&
       global.store.businesses[global.store.activeBusinessId].business) || {};
    const c = rawDoc.customerSnapshot || { name: rawDoc.customerName || 'Walk-in Customer' };

    const lines = (rawDoc.lines || []).map((l, idx) => {
      const qtyNum = Number(l.qty || 0);
      const unit = formatUnit(l.unit);
      const mrpNum = Number(l.mrp !== undefined ? l.mrp : 0);
      const rateNum = Number(l.price !== undefined ? l.price : (l.rate || 0));
      const amountNum = Number(l.total !== undefined ? l.total : qtyNum * rateNum);
      return {
        idx: idx + 1,
        name: l.name || ('Item ' + (idx + 1)),
        qtyStr: qtyNum.toFixed(1) + unit,
        mrpStr: mrpNum.toFixed(1),
        rateStr: rateNum.toFixed(1),
        amountStr: amountNum.toFixed(1)
      };
    });

    const totalNum   = Number(rawDoc.total || 0);
    const subtotalNum = Number(rawDoc.subtotal || totalNum);
    const paidNum    = Number(rawDoc.paidAmount || 0);
    const dueNum     = Math.max(0, totalNum - paidNum);

    return {
      isQuote,
      title:       isQuote ? 'QUOTATION' : 'TAX INVOICE',
      businessName: b.name || 'Darvi Group',
      address:      b.address || '#2 totad building, Arjun Vihar,\nGokul road Hubli, Karnataka\n580030, India HUBLI',
      gstin:        b.gstin || '',
      placeOfSupply: b.state || 'Karnataka',
      phone:        b.phone || '',
      docLabel:     isQuote ? 'Quotation No' : 'Bill No',
      docNumber:    extractShortNumber(rawDoc.number),
      date:         formatReceiptDate(rawDoc.date || new Date().toISOString()),
      partyName:    c.name || rawDoc.customerName || 'Walk-in Customer',
      partyPhone:   c.phone || '',
      lines,
      subtotal:     subtotalNum,
      discount:     Number(rawDoc.discount || 0),
      tax:          Number(rawDoc.tax || 0),
      total:        totalNum,
      paid:         paidNum,
      due:          dueNum,
      terms: [
        'Goods once sold will not be taken back',
        'or exchanged.',
        'No cash payments accepted.'
      ],
      slogan: 'Improving Agriculture, Improving lives',
      footer: 'Thank you for your purchase'
    };
  }

  // ─────────────────────────────────────────────────────────────────
  // Build Cleanter JSON receipt payload from normalised doc
  //
  // Cleanter block types used:
  //   { type: "text",    text, align, bold, size }
  //   { type: "row",     left, right, bold }
  //   { type: "divider" }
  //   { type: "feed" }
  // ─────────────────────────────────────────────────────────────────

  function formatReceipt(rawDoc, docType) {
    const doc = normaliseDoc(rawDoc, docType || 'invoice');
    const content = [];

    const text = (t, opts) => content.push({ type: 'text', text: String(t || ''), ...opts });
    const row  = (left, right, bold) => content.push({ type: 'row', left: String(left || ''), right: String(right || ''), ...(bold ? { bold: true } : {}) });
    const div  = () => content.push({ type: 'divider' });
    const feed = () => content.push({ type: 'feed' });

    // ── Header ───────────────────────────────────────────────────
    text(doc.title,        { align: 'center', bold: true, size: 'large' });
    text(doc.businessName, { align: 'center', bold: true });

    // Address — split on \n, print each line centred
    const addrLines = (doc.address || '').split('\n');
    for (const line of addrLines) {
      if (line.trim()) text(line.trim(), { align: 'center' });
    }

    feed();
    if (doc.gstin) text('GSTIN: ' + doc.gstin, { align: 'center' });
    text('Place of Supply: ' + doc.placeOfSupply);
    if (doc.phone) text('Mobile: ' + doc.phone);

    // ── Doc details ───────────────────────────────────────────────
    div();
    text(doc.docLabel + ': ' + doc.docNumber);
    text('Date: ' + doc.date);
    text('Party: ' + doc.partyName);
    if (doc.partyPhone) text('Phone: ' + doc.partyPhone);

    // ── Items table header ────────────────────────────────────────
    div();
    text('No  Item Description',       { bold: true });
    // Column header: Qty  MRP  Rate  Amount (right-aligned values)
    row(padRight('Qty', 7) + padRight('MRP', 7) + padRight('Rate', 8), padLeft('Amount', 10), true);
    div();

    // ── Items ─────────────────────────────────────────────────────
    for (const item of doc.lines) {
      text(String(item.idx).padStart(2, ' ') + '  ' + item.name, { bold: true });
      // Values line: Qty   MRP   Rate          Amount
      row(
        padRight(item.qtyStr, 7) + padRight(item.mrpStr, 7) + padRight(item.rateStr, 8),
        padLeft(item.amountStr, 10)
      );
      feed();
    }

    // ── Subtotal / Total ──────────────────────────────────────────
    div();
    row('Subtotal:', 'Rs. ' + formatIndianNumber(doc.subtotal));

    if (doc.discount > 0) {
      row('Discount:', '- Rs. ' + formatIndianNumber(doc.discount));
    }
    if (doc.tax > 0) {
      row('Tax / GST:', 'Rs. ' + formatIndianNumber(doc.tax));
    }

    div();
    row('Total Amount:', 'Rs. ' + formatIndianNumber(doc.total), true);
    div();

    // ── Payment summary (invoices only) ──────────────────────────
    if (!doc.isQuote && doc.paid > 0) {
      row('Amount Paid:', 'Rs. ' + formatIndianNumber(doc.paid));
      row('Balance Due:', 'Rs. ' + formatIndianNumber(doc.due));
      div();
    }

    // ── Terms ─────────────────────────────────────────────────────
    feed();
    text('Terms & Conditions:', { bold: true });
    for (const t of doc.terms) {
      text('  ' + t);
    }

    // ── Footer ────────────────────────────────────────────────────
    feed();
    text(doc.slogan, { align: 'center' });
    text(doc.footer,  { align: 'center', bold: true });

    // Feed lines before cut so the receipt tears cleanly
    for (let i = 0; i < 3; i++) feed();

    return { cut: true, content, blocks: content };
  }

  // ─────────────────────────────────────────────────────────────────
  // Check whether the Cleanter bridge is reachable on this device
  //
  // Returns:
  //   { reachable: true,  printerConnected: true|false, raw: <response body> }
  //   { reachable: false, reason: "timeout"|"network"|"error",  message: string }
  // ─────────────────────────────────────────────────────────────────

  async function checkBridge() {
    const isLikelyAndroid = typeof navigator !== 'undefined' && /Android/i.test(navigator.userAgent);
    if (!isLikelyAndroid) {
      return {
        reachable: false,
        reason: 'not_android',
        message: 'Cleanter runs on Android. In desktop browser, use USB cable (Web Serial) or print from the Darvi Billing Android app.'
      };
    }
    const outcome = await requestBridge('/health', { method: 'GET' }, HEALTH_TIMEOUT_MS);
    if (!outcome.ok) {
      const err = outcome.err;
      if (err && err.name === 'AbortError') {
        return { reachable: false, reason: 'timeout', message: 'Cleanter did not respond within ' + (HEALTH_TIMEOUT_MS / 1000) + 's.' };
      }
      return { reachable: false, reason: 'network', message: err ? err.message : 'Cannot reach Cleanter on 127.0.0.1:9100 or localhost:9100.' };
    }

    const res = outcome.res;
    let body = null;
    try { body = await res.json(); } catch (_) {}

    if (!res.ok) {
      return {
        reachable: false,
        reason: 'http_error',
        message: 'Cleanter responded with HTTP ' + res.status
      };
    }

    let printerConnected = null;
    if (body && typeof body === 'object') {
      if (typeof body.printer === 'object' && body.printer !== null) {
        printerConnected = !!body.printer.connected;
      } else if (typeof body.printerConnected === 'boolean') {
        printerConnected = body.printerConnected;
      } else if (typeof body.connected === 'boolean') {
        printerConnected = body.connected;
      }
    }

    return { reachable: true, printerConnected, raw: body, host: outcome.host };
  }

  // Convenience alias
  async function getPrinterStatus() {
    return checkBridge();
  }

  // ─────────────────────────────────────────────────────────────────
  // Submit a print job to Cleanter
  //
  // Returns:
  //   { ok: true }
  //   { ok: false, message: string, httpStatus?: number }
  // ─────────────────────────────────────────────────────────────────

  async function submitPrintJob(payload) {
    const outcome = await requestBridge(
      '/print',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      },
      PRINT_TIMEOUT_MS
    );

    if (!outcome.ok) {
      const isHttp = typeof location !== 'undefined' && location.protocol === 'http:' && location.hostname !== 'localhost' && location.hostname !== '127.0.0.1';
      if (isHttp) {
        return {
          ok: false,
          isHttpPnaBlock: true,
          httpsUrl: 'https://' + location.hostname + ':8443',
          message: 'Android Chrome blocks local Cleanter loopback connections from HTTP. Please open this page via HTTPS (https://' + location.hostname + ':8443) on your phone to print.'
        };
      }

      const err = outcome.err;
      if (err && err.name === 'AbortError') {
        return {
          ok: false,
          message: 'Print request timed out. Make sure the Cleanter app is open and running on this phone.'
        };
      }
      return {
        ok: false,
        message: 'Could not connect to Cleanter (tried 127.0.0.1:9100 and localhost:9100). Please open Cleanter and tap Start Server.'
      };
    }

    const res = outcome.res;
    if (res.ok) {
      return { ok: true };
    }

    let errMsg = 'Cleanter returned HTTP ' + res.status;
    try {
      const body = await res.json();
      if (body && (body.message || body.error)) {
        errMsg = body.message || body.error;
      }
    } catch (_) {}

    return { ok: false, message: errMsg, httpStatus: res.status };
  }

  // ─────────────────────────────────────────────────────────────────
  // Public: print an invoice or estimate
  //
  // Returns:
  //   { ok: true }               — receipt sent successfully
  //   { ok: false, message }     — bridge or printer error
  //   { ok: false, notAndroid }  — called from laptop browser
  // ─────────────────────────────────────────────────────────────────

  async function printInvoice(rawDoc, docType) {
    // Guard: Cleanter only runs on Android.
    // If opened on a laptop/desktop PC, localhost:9100 points to the PC itself
    // where Cleanter does not exist. The page must be opened on the Android phone.
    const isLikelyAndroid = (typeof navigator !== 'undefined' && /Android/i.test(navigator.userAgent)) || (typeof window !== 'undefined' && !!window.Capacitor);

    if (!isLikelyAndroid) {
      return {
        ok: false,
        notAndroid: true,
        message: 'Cleanter runs on your Android phone. Open this billing page on your Android phone to print directly to your Seznik printer.'
      };
    }

    // Validate document
    if (!rawDoc) {
      return { ok: false, message: 'No document provided for printing.' };
    }
    if (!rawDoc.lines || rawDoc.lines.length === 0) {
      return { ok: false, message: 'The invoice has no line items. Cannot print an empty receipt.' };
    }

    // Format receipt into Cleanter structured block payload
    const payload = formatReceipt(rawDoc, docType || 'invoice');

    // Submit print job directly to Cleanter (tries 127.0.0.1:9100 then localhost:9100)
    const result = await submitPrintJob(payload);
    return result;
  }

  // ─────────────────────────────────────────────────────────────────
  // Public: print a test receipt
  // ─────────────────────────────────────────────────────────────────

  async function testPrint() {
    const testDoc = {
      number: '487',
      date: '2026-09-20',
      customerName: 'Test Customer',
      businessSnapshot: {
        name: 'Darvi Group',
        address: '#2 totad building, Arjun Vihar,\nGokul road Hubli, Karnataka\n580030, India HUBLI',
        gstin: '29BKZPA0053M1ZQ',
        state: 'Karnataka',
        phone: '9986890777'
      },
      customerSnapshot: { name: 'Test Customer', phone: '9999999999' },
      lines: [
        { name: 'Coconut Hybrid', qty: 2, unit: 'sapling', mrp: 0, price: 375, total: 750 },
        { name: 'Test Item',      qty: 1, unit: 'piece',   mrp: 0, price: 100, total: 100 }
      ],
      subtotal: 850,
      total: 850,
      paidAmount: 850
    };
    return printInvoice(testDoc, 'invoice');
  }

  // ─────────────────────────────────────────────────────────────────
  // Public: feed paper
  // ─────────────────────────────────────────────────────────────────

  async function feedPaper(lines = 3) {
    return submitPrintJob({
      cut: false,
      content: [{ type: 'feed', lines: lines || 3 }]
    });
  }

  // ─────────────────────────────────────────────────────────────────
  // Export
  // ─────────────────────────────────────────────────────────────────

  global.CleanterPrinter = {
    checkBridge,
    getPrinterStatus,
    formatReceipt,
    printInvoice,
    testPrint,
    feedPaper,
    getBridgeHost: () => activeBridgeHost,
    BRIDGE_HOSTS
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { CleanterPrinter: global.CleanterPrinter };
  }
})(typeof window !== 'undefined' ? window : globalThis);

