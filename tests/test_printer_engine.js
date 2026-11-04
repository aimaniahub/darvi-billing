// tests/test_printer_engine.js
// Automated verification suite for DarviPrinter & DarviReceiptFormatter

const assert = require('assert');
const { DarviPrinter, DarviReceiptFormatter } = require('../thermal-printer.js');

console.log('======================================================');
console.log(' RUNNING DARVI THERMAL PRINTER VERIFICATION SUITE');
console.log('======================================================');

let passedTests = 0;
const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// Sample Invoice
const sampleInvoice = {
  id: 'inv_001',
  number: 'DB-2026-0001',
  date: '2026-11-03',
  customerName: 'Walk-in Customer',
  customerInvoiceRef: 'LEGACY-001',
  businessSnapshot: {
    name: 'Darvi Group',
    address: '#2 totad building, Arjun Vihar,\nGokul road Hubli, Karnataka\n580030, India HUBLI',
    gstin: '29BKZPA0053M1ZQ',
    state: 'Karnataka',
    phone: '9986890777'
  },
  customerSnapshot: {
    name: 'Walk-in Customer',
    phone: '9876543210'
  },
  lines: [
    { name: 'Coconut Hybrid', qty: 1, unit: 'sapling', mrp: 400, price: 375, discount: 0, tax: 0, total: 375 },
    { name: 'Red Diamond Guava with Long Hybrid Grafting Name That Must Wrap', qty: 2, unit: 'piece', mrp: 100, price: 80, discount: 0, tax: 0, total: 160 }
  ],
  subtotal: 535,
  discount: 10,
  tax: 25,
  transportAmount: 50,
  total: 600,
  paidAmount: 500,
  paymentMethod: 'UPI'
};

// Sample Quotation
const sampleQuotation = {
  id: 'est_001',
  number: 'DG-EST-2026-0002',
  date: '2026-11-03',
  customerName: 'Ramesh Agro',
  businessSnapshot: {
    name: 'Darvi Agri Genetics',
    address: 'Agri Hub, Dharwad, Karnataka',
    gstin: '29ABCDE1234F1Z5',
    phone: '9123456789'
  },
  customerSnapshot: {
    name: 'Ramesh Agro',
    phone: '9988776655'
  },
  lines: [
    { name: 'Tissue Culture Teak', qty: 100, unit: 'sapling', mrp: 60, price: 50, discount: 0, tax: 0, total: 5000 }
  ],
  subtotal: 5000,
  discount: 0,
  tax: 0,
  transportAmount: 200,
  total: 5200,
  paidAmount: 0
};

// ── TEST 1: Document Normalisation ─────────────────────────────────
test('Document Normalisation extracts all fields correctly', () => {
  const norm = DarviReceiptFormatter.normaliseDoc(sampleInvoice, 'invoice');
  assert.strictEqual(norm.title, 'TAX INVOICE');
  assert.strictEqual(norm.businessName, 'Darvi Group');
  assert.strictEqual(norm.total, 600);
  assert.strictEqual(norm.paid, 500);
  assert.strictEqual(norm.due, 100);
  assert.strictEqual(norm.paymentMethod, 'UPI');
  assert.strictEqual(norm.lines.length, 2);
  assert.strictEqual(norm.lines[0].qtyFormatted, '1 P');
  assert.strictEqual(norm.lines[1].qtyFormatted, '2 PC');

  const quoteNorm = DarviReceiptFormatter.normaliseDoc(sampleQuotation, 'quotation');
  assert.strictEqual(quoteNorm.title, 'QUOTATION');
  assert.strictEqual(quoteNorm.isQuote, true);
  assert.strictEqual(quoteNorm.total, 5200);
});

// ── TEST 2: Monospace Text 32-Column Bounds ─────────────────────────
test('Monospace text conforms to 32 columns width', () => {
  const text = DarviReceiptFormatter.generateReceiptText(sampleInvoice, 'invoice');
  const lines = text.split('\n');
  assert.ok(lines.length > 15, 'Receipt must have header, items, totals and footer');
  for (let i = 0; i < lines.length; i++) {
    assert.ok(
      lines[i].length <= 32,
      `Line ${i + 1} exceeds 32 chars: "${lines[i]}" (length: ${lines[i].length})`
    );
  }
});

// ── TEST 3: ESC/POS Byte Generation & Size Optimization ─────────────
test('ESC/POS byte generation produces compact bytecode under 1.5KB', () => {
  const bytes = DarviReceiptFormatter.generateEscPosBytes(sampleInvoice, 'invoice');
  assert.ok(bytes instanceof Uint8Array, 'Must return Uint8Array');
  assert.ok(bytes.length > 200, 'Byte array must contain receipt content');
  assert.ok(bytes.length < 1500, `Byte array must be compact (<1.5KB), got ${bytes.length} bytes`);

  // Verify ESC @ (0x1B, 0x40) init command at start
  assert.strictEqual(bytes[0], 0x1B);
  assert.strictEqual(bytes[1], 0x40);

  // Verify ESC d (0x1B, 0x64) paper feed at end
  const len = bytes.length;
  assert.strictEqual(bytes[len - 3], 0x1B);
  assert.strictEqual(bytes[len - 2], 0x64);
  assert.strictEqual(bytes[len - 1], 0x04);

  console.log(`     [Detail: ESC/POS payload is ${bytes.length} bytes — 50x smaller than 60KB system print!]`);
});

// ── TEST 4: HTML Preview Rendering ──────────────────────────────────
test('HTML preview generates valid HTML wrapper', () => {
  const html = DarviReceiptFormatter.generateReceiptHtml(sampleInvoice, 'invoice');
  assert.ok(html.startsWith('<pre class="receipt-monotext"'), 'Must produce <pre> container');
  assert.ok(html.includes('TAX INVOICE'), 'Must contain invoice title');
  assert.ok(html.includes('Darvi Group'), 'Must contain business name');
  assert.ok(html.includes('Total Amount:'), 'Must contain total amount');
});

// ── TEST 5: Mock Native Bluetooth Transport & Connection Lifecycle ──
test('DarviPrinter integrates with Native Plugin & tracks state transitions', async () => {
  let mockState = 'Disconnected';
  let mockConnectedDevice = null;
  let writtenBytesCount = 0;
  let cleanterPrintCalls = 0;
  let failNextPrint = false;
  let lastSubmittedBytes = null;
  let lastReceiptRequest = null;

  // Setup Mock Capacitor Plugin
  global.window = {
    CleanterPrinter: {
      checkBridge: async () => ({ reachable: true }),
      printInvoice: async () => {
        cleanterPrintCalls++;
        return { ok: true };
      }
    },
    Capacitor: {
      isNativePlatform: () => true,
      Plugins: {
        BluetoothPrinter: {
          getStatus: async () => ({
            state: mockState,
            isConnected: mockState === 'Connected',
            deviceName: mockConnectedDevice?.name || null,
            deviceAddress: mockConnectedDevice?.address || null
          }),
          listPairedDevices: async () => ({
            devices: [
              { name: 'SSEZNIK2POS', address: '00:11:22:33:44:55', isSeznik: true },
              { name: 'Redmi Earbuds', address: 'AA:BB:CC:DD:EE:FF', isSeznik: false }
            ]
          }),
          connect: async ({ address, name }) => {
            mockState = 'Connected';
            mockConnectedDevice = { name: 'SSEZNIK2POS', address: '00:11:22:33:44:55' };
            return { ok: true, deviceName: 'SSEZNIK2POS', deviceAddress: '00:11:22:33:44:55' };
          },
          disconnect: async () => {
            mockState = 'Disconnected';
            mockConnectedDevice = null;
            return { ok: true };
          },
          printBytes: async ({ base64 }) => {
            if (mockState !== 'Connected') throw new Error('Printer not connected');
            if (failNextPrint) {
              failNextPrint = false;
              throw new Error('Simulated Bluetooth write failure');
            }
            lastSubmittedBytes = Uint8Array.from(Buffer.from(base64, 'base64'));
            writtenBytesCount += lastSubmittedBytes.length;
            return { ok: true, bytesWritten: lastSubmittedBytes.length, durationMs: 45 };
          },
          printReceipt: async ({ document, docType }) => {
            if (mockState !== 'Connected') throw new Error('Printer not connected');
            if (failNextPrint) {
              failNextPrint = false;
              throw new Error('Simulated native receipt failure');
            }
            lastReceiptRequest = { document, docType };
            const bytesWritten = Buffer.byteLength(JSON.stringify(document), 'ascii');
            writtenBytesCount += bytesWritten;
            return { ok: true, bytesWritten, durationMs: 45, deviceName: 'SSEZNIK2POS' };
          },
          testPrint: async () => {
            const bytes = Buffer.from('DARVI NATIVE SPP TEST\r\nIf visible, text printing works.\r\n\r\n\r\n\r\n', 'ascii');
            lastSubmittedBytes = Uint8Array.from(bytes);
            writtenBytesCount += bytes.length;
            return { ok: true, bytesWritten: bytes.length, durationMs: 45, deviceName: 'SSEZNIK2POS' };
          },
          feed: async ({ lines }) => ({ ok: true })
        }
      }
    }
  };

  // Track status listener notifications
  const statesRecorded = [];
  const unsubscribe = DarviPrinter.onStatusChange(s => statesRecorded.push(s.state));

  // 1. Initial State
  const initialStatus = DarviPrinter.getStatus();
  assert.strictEqual(initialStatus.isConnected, false);

  // 2. Discover Devices
  const devices = await DarviPrinter.listPairedDevices();
  assert.strictEqual(devices.length, 2);
  assert.strictEqual(devices[0].name, 'SSEZNIK2POS');

  // 3. Connect
  const conn = await DarviPrinter.connect();
  assert.strictEqual(conn.ok, true);
  assert.strictEqual(DarviPrinter.isConnected(), true);
  assert.strictEqual(DarviPrinter.getDeviceName(), 'SSEZNIK2POS');

  // 4. Print Invoice
  const printResult = await DarviPrinter.printInvoice(sampleInvoice, 'invoice');
  assert.strictEqual(printResult.ok, true);
  assert.ok(printResult.bytesWritten > 200);
  assert.strictEqual(printResult.durationMs, 45);
  assert.strictEqual(printResult.transport, 'bluetooth_spp');
  assert.strictEqual(printResult.deviceName, 'SSEZNIK2POS');
  assert.strictEqual(lastReceiptRequest.document, sampleInvoice, 'Native Android receives the receipt document, not JS-generated printer bytes');
  assert.strictEqual(lastReceiptRequest.docType, 'invoice');
  assert.strictEqual(cleanterPrintCalls, 0, 'Native SPP must take precedence over the Cleanter bridge');

  const largePayload = Uint8Array.from({ length: 8192 }, (_, i) => i & 0xFF);
  const largeResult = await DarviPrinter.printBytes(largePayload);
  assert.strictEqual(largeResult.bytesWritten, largePayload.length);
  assert.deepStrictEqual(Array.from(lastSubmittedBytes), Array.from(largePayload), 'The complete payload must cross the native bridge');

  failNextPrint = true;
  await assert.rejects(
    DarviPrinter.printInvoice(sampleInvoice, 'invoice'),
    /Simulated native receipt failure/
  );
  assert.strictEqual(cleanterPrintCalls, 0, 'A failed native write must not be reported as a successful bridge print');

  // 5. Test Print
  const testResult = await DarviPrinter.testPrint();
  assert.strictEqual(testResult.ok, true);
  assert.strictEqual(testResult.transport, 'bluetooth_spp');
  assert.strictEqual(testResult.diagnostic, true);
  assert.ok(testResult.bytesWritten < 100, 'Diagnostic print must stay a minimal raw ASCII job');
  assert.deepStrictEqual(Array.from(lastSubmittedBytes), Array.from(Buffer.from(
    'DARVI NATIVE SPP TEST\r\nIf visible, text printing works.\r\n\r\n\r\n\r\n', 'ascii'
  )), 'Native diagnostic print should contain plain ASCII only');

  // 6. Disconnect
  await DarviPrinter.disconnect();
  assert.strictEqual(DarviPrinter.isConnected(), false);
  assert.strictEqual(DarviPrinter.getDeviceName(), null);

  unsubscribe();
  assert.ok(statesRecorded.includes('Connected'), 'Must have notified listener of Connected state');
  assert.ok(statesRecorded.includes('Disconnected'), 'Must have notified listener of Disconnected state');

  console.log(`     [Detail: Mock wrote ${writtenBytesCount} bytes, including an 8KB payload and minimal SPP diagnostic]`);
});

async function runTests() {
  for (const { name, fn } of tests) {
    try {
      await fn();
      console.log(`  ✓ ${name}`);
      passedTests++;
    } catch (err) {
      console.error(`  ✗ FAIL: ${name}`);
      console.error(err);
      process.exitCode = 1;
      return;
    }
  }
  console.log('======================================================');
  console.log(` ALL ${passedTests} TESTS PASSED CLEANLY!`);
  console.log('======================================================');
}

runTests().catch(err => {
  console.error(err);
  process.exitCode = 1;
});
