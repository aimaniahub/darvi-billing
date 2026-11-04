package com.darvigroup.billing;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class NativePrinter {
    private static final UUID SPP_UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int RASTER_WIDTH_DOTS = 384;
    private static final int RECEIPT_COLUMNS = 32;
    private static final int RASTER_BAND_HEIGHT_ROWS = 128;
    private static final int RECEIPT_LINE_HEIGHT = 34;
    private static final int MIN_SEGMENT_DRAIN_DELAY_MS = 2000;
    private static final int MIN_JOB_DRAIN_DELAY_MS = 6000;

    private static BluetoothSocket connectedSocket;
    private static String connectedAddress;

    private NativePrinter() {}

    static int printInvoice(Context context, BluetoothDevice device, JSONObject business,
        JSONObject invoice)
        throws IOException, InterruptedException {
        Bitmap logo = BitmapFactory.decodeResource(
            context.getResources(), R.drawable.darvi_receipt_logo);
        Bitmap qr = BitmapFactory.decodeResource(
            context.getResources(), R.drawable.darvi_website_qr);
        if (logo == null || qr == null) {
            if (logo != null) logo.recycle();
            if (qr != null) qr.recycle();
            throw new IOException("Receipt logo or website QR image could not be loaded");
        }
        try {
            List<String> lines = receiptLines(business, invoice);
            int footerStart = lines.size() - 2;
            byte[] body = rasterReceipt(lines.subList(0, footerStart), logo, qr);
            byte[] footer = rasterText(lines.subList(footerStart, lines.size()));
            return writeSegments(device, receiptSegments(body, footer));
        } finally {
            logo.recycle();
            qr.recycle();
        }
    }

    static int testPrint(BluetoothDevice device) throws IOException, InterruptedException {
        List<byte[]> segments = new ArrayList<>();
        ByteArrayOutputStream textData = new ByteArrayOutputStream();
        initializeTextMode(textData);
        command(textData, 0x1B, 0x61, 0x01);
        text(textData, "DARVI PRINTER TEST\r\n");
        command(textData, 0x1B, 0x61, 0x00);
        text(textData, "TEXT MODE: ASCII check\r\n");
        text(textData, "------------------------------\r\n");
        segments.add(textData.toByteArray());
        List<String> rasterLines = new ArrayList<>();
        rasterLines.add("GRAPHICS MODE");
        rasterLines.add("If readable, raster output works.");
        segments.add(rasterText(rasterLines));
        ByteArrayOutputStream feed = new ByteArrayOutputStream();
        command(feed, 0x1B, 0x64, 0x03);
        segments.add(feed.toByteArray());
        return writeSegments(device, segments);
    }

    static int printShippingLabel(BluetoothDevice device, JSONObject invoice)
        throws IOException, InterruptedException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        JSONObject shipping = invoice.optJSONObject("shippingSnapshot");
        initializeTextMode(data);
        command(data, 0x1B, 0x61, 0x01);
        command(data, 0x1B, 0x45, 0x01);
        text(data, "SHIP TO\r\n");
        command(data, 0x1B, 0x45, 0x00);
        text(data, "\r\n" + truncate(shipping == null ? invoice.optString("customerName") :
            shipping.optString("name", invoice.optString("customerName")), 32) + "\r\n");
        if (shipping != null) {
            String phone = shipping.optString("phone");
            if (!phone.isEmpty()) text(data, "Phone: " + truncate(phone, 24) + "\r\n");
            text(data, wrap(shipping.optString("address", ""), 32));
            String city = shipping.optString("city");
            String state = shipping.optString("state");
            String pin = shipping.optString("pincode");
            String locality = (city + " " + state + " " + pin).trim();
            if (!locality.isEmpty()) text(data, wrap(locality, 32));
        }
        command(data, 0x1B, 0x61, 0x00);
        text(data, "\r\nInvoice: " + invoice.optString("number") + "\r\n");
        command(data, 0x1B, 0x64, 0x05);
        return write(device, data.toByteArray());
    }

    private static int write(BluetoothDevice device, byte[] payload)
        throws IOException, InterruptedException {
        return writeSegments(device, java.util.Collections.singletonList(payload));
    }

    private static synchronized int writeSegments(BluetoothDevice device, List<byte[]> segments)
        throws IOException, InterruptedException {
        String address = device.getAddress();
        if (connectedSocket == null || !connectedSocket.isConnected() ||
            !address.equals(connectedAddress)) {
            disconnect();
            connectedSocket = connect(device);
            connectedAddress = address;
        }

        try {
            OutputStream output = connectedSocket.getOutputStream();
            int sent = 0;
            for (int i = 0; i < segments.size(); i++) {
                byte[] segment = segments.get(i);
                output.write(segment);
                output.flush();
                sent += segment.length;
                if (i == segments.size() - 1) {
                    Thread.sleep(Math.max(MIN_JOB_DRAIN_DELAY_MS, segment.length / 16));
                } else if (segment.length > 3) {
                    Thread.sleep(Math.max(MIN_SEGMENT_DRAIN_DELAY_MS, segment.length / 16));
                }
            }
            return sent;
        } catch (IOException | InterruptedException e) {
            disconnect();
            throw e;
        }
    }

    private static BluetoothSocket connect(BluetoothDevice device) throws IOException {
        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
        try {
            socket.connect();
            return socket;
        } catch (IOException firstFailure) {
            close(socket);
            BluetoothSocket fallback = null;
            try {
                fallback = (BluetoothSocket) device.getClass()
                    .getMethod("createRfcommSocket", int.class).invoke(device, 1);
                fallback.connect();
                return fallback;
            } catch (ReflectiveOperationException | IOException secondFailure) {
                close(fallback);
                IOException failure = new IOException(
                    "Could not open the printer's Bluetooth serial connection", secondFailure);
                failure.addSuppressed(firstFailure);
                throw failure;
            }
        }
    }

    private static synchronized void disconnect() {
        close(connectedSocket);
        connectedSocket = null;
        connectedAddress = null;
    }

    static List<byte[]> receiptSegments(JSONObject business, JSONObject invoice) {
        List<String> lines = receiptLines(business, invoice);
        int footerStart = lines.size() - 2;
        return receiptSegments(
            rasterText(lines.subList(0, footerStart)),
            rasterText(lines.subList(footerStart, lines.size())));
    }

    static List<byte[]> receiptSegments(byte[] bodyRaster, byte[] footerRaster) {
        List<byte[]> segments = new ArrayList<>();
        segments.add(new byte[] {0x1B, 0x40});
        segments.add(bodyRaster);
        segments.add(footerRaster);
        segments.add(new byte[] {0x1B, 0x64, 0x04});
        return segments;
    }

    static byte[] textInitializationCommands() {
        return new byte[] {
            0x1B, 0x40,       // Initialize printer.
            0x1B, 0x74, 0x00, // ESC t 0: PC437 (common ESC/POS table 0).
            0x1B, 0x21, 0x00  // Normal font and print mode.
        };
    }

    private static void initializeTextMode(ByteArrayOutputStream out) {
        byte[] commands = textInitializationCommands();
        out.write(commands, 0, commands.length);
    }

    static byte[] rasterCommand(int widthBytes, int height, byte[] rasterData) {
        if (widthBytes <= 0 || widthBytes > 0xFFFF || height <= 0 || height > 0xFFFF) {
            throw new IllegalArgumentException("Invalid ESC/POS raster dimensions");
        }
        if ((long) rasterData.length != (long) widthBytes * height) {
            throw new IllegalArgumentException("Raster data length does not match dimensions");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + rasterData.length);
        command(out, 0x1D, 0x76, 0x30, 0x00,
            widthBytes & 0xFF, (widthBytes >> 8) & 0xFF,
            height & 0xFF, (height >> 8) & 0xFF);
        out.write(rasterData, 0, rasterData.length);
        return out.toByteArray();
    }

    static List<byte[]> rasterBands(int widthBytes, int height, byte[] rasterData) {
        if (widthBytes <= 0 || height <= 0 ||
            (long) rasterData.length != (long) widthBytes * height) {
            throw new IllegalArgumentException("Invalid ESC/POS raster image dimensions");
        }
        List<byte[]> bands = new ArrayList<>();
        for (int row = 0; row < height; row += RASTER_BAND_HEIGHT_ROWS) {
            int bandHeight = Math.min(RASTER_BAND_HEIGHT_ROWS, height - row);
            byte[] bandData = new byte[widthBytes * bandHeight];
            System.arraycopy(rasterData, row * widthBytes, bandData, 0, bandData.length);
            bands.add(rasterCommand(widthBytes, bandHeight, bandData));
        }
        return bands;
    }

    static List<String> receiptLines(JSONObject business, JSONObject invoice) {
        List<String> lines = new ArrayList<>();
        lines.add(truncateReceipt(business.optString("name", "Darvi Billing"), RECEIPT_COLUMNS));
        addWrappedReceiptLines(lines, business.optString("address", ""));
        addOptionalReceiptLine(lines, "Phone: ", business.optString("phone", ""));
        addOptionalReceiptLine(lines, "GSTIN: ", business.optString("gstin", ""));
        lines.add("================================");
        lines.add("INVOICE");
        lines.add("Invoice #\t" + invoice.optString("number", ""));
        lines.add("Date\t" + invoice.optString("date", ""));
        lines.add("BILL TO");
        addWrappedReceiptLines(lines, invoice.optString("customerName", "Walk-in Customer"));
        lines.add("--------------------------------");
        lines.add("ITEMS");

        JSONArray items = invoice.optJSONArray("lines");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                addWrappedReceiptLines(lines, item.optString("name", "Item"));
                String itemAmount = formatQuantity(item.optDouble("qty")) + " x " +
                    formatMoney(item.optDouble("price"));
                lines.add(alignedReceiptLine(itemAmount,
                    formatMoney(item.optDouble("total"))));
            }
        }

        lines.add("--------------------------------");
        lines.add("PAYMENT SUMMARY");
        lines.add(alignedReceiptLine("Subtotal", formatMoney(invoice.optDouble("subtotal"))));
        if (invoice.optDouble("discount") > 0) {
            lines.add(alignedReceiptLine("Discount", formatMoney(invoice.optDouble("discount"))));
        }
        if (invoice.optDouble("tax") > 0) {
            lines.add(alignedReceiptLine("Tax", formatMoney(invoice.optDouble("tax"))));
        }
        lines.add(alignedReceiptLine("TOTAL", formatMoney(invoice.optDouble("total"))));
        lines.add(alignedReceiptLine("Paid", formatMoney(invoice.optDouble("paidAmount"))));
        lines.add(alignedReceiptLine("Balance", formatMoney(Math.max(0,
            invoice.optDouble("total") - invoice.optDouble("paidAmount")))));
        lines.add("Thank you!");
        lines.add("We appreciate your business.");
        return lines;
    }

    private static void addOptionalReceiptLine(List<String> lines, String label, String value) {
        if (value.isEmpty()) return;
        addWrappedReceiptLines(lines, label + value);
    }

    static String alignedReceiptLine(String label, String value) {
        return label + "\t" + value;
    }

    private static void addWrappedReceiptLines(List<String> lines, String value) {
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.isEmpty()) return;
        StringBuilder line = new StringBuilder();
        for (String word : normalized.split("\\s+")) {
            if (line.length() > 0 && codePointLength(line) + 1 + codePointLength(word) >
                RECEIPT_COLUMNS) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(truncateReceipt(word, RECEIPT_COLUMNS));
        }
        if (line.length() > 0) lines.add(line.toString());
    }

    private static String truncateReceipt(String value, int maxCodePoints) {
        int count = codePointLength(value);
        if (count <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static int codePointLength(CharSequence value) {
        return Character.codePointCount(value, 0, value.length());
    }

    private static byte[] rasterText(List<String> lines) {
        int padding = 12;
        int top = 12;
        int height = top + lines.size() * RECEIPT_LINE_HEIGHT + 12;
        Bitmap bitmap = Bitmap.createBitmap(
            RASTER_WIDTH_DOTS, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            drawReceiptLines(canvas, lines, padding, top);
            return rasterBitmap(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] rasterReceipt(List<String> lines, Bitmap logo, Bitmap qr) {
        int bodyTop = 188;
        int height = bodyTop + lines.size() * RECEIPT_LINE_HEIGHT + 12;
        Bitmap bitmap = Bitmap.createBitmap(
            RASTER_WIDTH_DOTS, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);

            Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            RectF logoBounds = new RectF(12, 38, 210, 121);
            canvas.drawBitmap(logo, null, logoBounds, imagePaint);
            RectF qrBounds = new RectF(230, 8, 366, 144);
            imagePaint.setFilterBitmap(false);
            canvas.drawBitmap(qr, null, qrBounds, imagePaint);

            Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelPaint.setColor(Color.BLACK);
            labelPaint.setTextSize(15f);
            labelPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            String scanLabel = "SCAN HERE";
            canvas.drawText(scanLabel,
                (RASTER_WIDTH_DOTS - labelPaint.measureText(scanLabel)) / 2f + 106,
                164, labelPaint);

            Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dividerPaint.setColor(Color.BLACK);
            dividerPaint.setStrokeWidth(2f);
            canvas.drawLine(12, 178, RASTER_WIDTH_DOTS - 12, 178, dividerPaint);
            drawReceiptLines(canvas, lines, 12, bodyTop);
            return rasterBitmap(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private static void drawReceiptLines(Canvas canvas, List<String> lines, int padding, int top) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(19f);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        float availableWidth = RASTER_WIDTH_DOTS - padding * 2;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            float baseline = top + (i + 1) * RECEIPT_LINE_HEIGHT - 7;
            if (line.matches("[-=]{8,}")) {
                Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
                divider.setColor(Color.BLACK);
                divider.setStrokeWidth(line.charAt(0) == '=' ? 2f : 1f);
                canvas.drawLine(padding, baseline - 8, RASTER_WIDTH_DOTS - padding,
                    baseline - 8, divider);
                continue;
            }

            boolean section = "BILL TO".equals(line) || "ITEMS".equals(line) ||
                "PAYMENT SUMMARY".equals(line);
            boolean emphasis = i == 0 || section || "INVOICE".equals(line) ||
                line.startsWith("TOTAL\t") || "Thank you!".equals(line);
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE,
                emphasis ? Typeface.BOLD : Typeface.NORMAL));
            paint.setTextSize("Thank you!".equals(line) ? 22f :
                ("INVOICE".equals(line) ? 23f : 19f));

            int tab = line.indexOf('\t');
            String left = tab < 0 ? line : line.substring(0, tab);
            String right = tab < 0 ? null : line.substring(tab + 1);
            float leftWidth = paint.measureText(left);
            float scale = leftWidth > availableWidth ? availableWidth / leftWidth : 1f;
            float x = i == 0 || section || "INVOICE".equals(line) ||
                "Thank you!".equals(line) || "We appreciate your business.".equals(line)
                ? (RASTER_WIDTH_DOTS - leftWidth * scale) / 2f
                : padding;
            canvas.save();
            canvas.scale(scale, 1f, x, baseline);
            canvas.drawText(left, x, baseline, paint);
            canvas.restore();

            if (right != null) {
                float rightWidth = paint.measureText(right);
                float rightX = RASTER_WIDTH_DOTS - padding - rightWidth;
                canvas.drawText(right, Math.max(padding + leftWidth + 8, rightX),
                    baseline, paint);
            }
        }
    }

    private static byte[] rasterBitmap(Bitmap bitmap) {
        int height = bitmap.getHeight();
        int[] pixels = new int[RASTER_WIDTH_DOTS * height];
        bitmap.getPixels(pixels, 0, RASTER_WIDTH_DOTS, 0, 0, RASTER_WIDTH_DOTS, height);
        int widthBytes = RASTER_WIDTH_DOTS / 8;
        byte[] raster = new byte[widthBytes * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < RASTER_WIDTH_DOTS; x++) {
                int color = pixels[y * RASTER_WIDTH_DOTS + x];
                int luminance = (Color.red(color) * 299 + Color.green(color) * 587 +
                    Color.blue(color) * 114) / 1000;
                if (luminance < 200) {
                    raster[y * widthBytes + x / 8] |= (byte) (0x80 >> (x % 8));
                }
            }
        }
        return rasterCommand(widthBytes, height, raster);
    }

    private static String wrap(String value, int width) {
        StringBuilder result = new StringBuilder();
        String[] words = value.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (line.length() > 0 && line.length() + word.length() + 1 > width) {
                result.append(line).append("\r\n");
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(truncate(word, width));
        }
        if (line.length() > 0) result.append(line).append("\r\n");
        return result.toString();
    }

    private static String truncate(String value, int length) {
        String ascii = value.replaceAll("[^\\x20-\\x7E]", "?");
        return ascii.length() <= length ? ascii : ascii.substring(0, length);
    }

    private static String formatMoney(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    private static String formatQuantity(double value) {
        return value == Math.rint(value)
            ? String.format(java.util.Locale.US, "%.0f", value)
            : String.format(java.util.Locale.US, "%.2f", value);
    }

    private static void text(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }

    private static void command(ByteArrayOutputStream out, int... values) {
        for (int value : values) out.write(value);
    }

    private static void close(BluetoothSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) { }
    }
}
