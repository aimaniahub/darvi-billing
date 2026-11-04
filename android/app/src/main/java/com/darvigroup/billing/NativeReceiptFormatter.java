package com.darvigroup.billing;

import com.getcapacitor.JSObject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class NativeReceiptFormatter {
    private static final int COLUMNS = 32;

    private NativeReceiptFormatter() {}

    static byte[] format(JSObject document, String docType) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean quotation = "quotation".equalsIgnoreCase(docType);
        JSONObject business = document.optJSONObject("businessSnapshot");
        JSONObject customer = document.optJSONObject("customerSnapshot");

        appendCentered(output, quotation ? "QUOTATION" : "TAX INVOICE");
        appendCentered(output, value(business, "name", "Darvi Group"));
        appendWrapped(output, value(business, "address", ""));
        appendIfPresent(output, "GSTIN: " + value(business, "gstin", ""));
        appendIfPresent(output, "Phone: " + value(business, "phone", ""));
        appendLine(output, divider());
        appendWrapped(output, (quotation ? "Quotation No: " : "Bill No: ") + value(document, "number", ""));
        appendWrapped(output, "Date: " + value(document, "date", ""));
        appendWrapped(output, "Party: " + value(customer, "name", value(document, "customerName", "Walk-in Customer")));
        appendIfPresent(output, "Phone: " + value(customer, "phone", ""));
        appendLine(output, divider());
        appendLine(output, "ITEMS");
        appendLine(output, divider());

        JSONArray items = document.optJSONArray("lines");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                appendWrapped(output, (i + 1) + ". " + value(item, "name", "Item"));
                String unit = value(item, "unit", "");
                String quantity = number(item, "qty", 0) + (unit.isEmpty() ? "" : " " + unit);
                appendWrapped(output, quantity + " x Rs. " + money(number(item, "price", 0)));
                appendWrapped(output, "Amount: Rs. " + money(number(item, "total",
                    number(item, "qty", 0) * number(item, "price", 0))));
            }
        }

        appendLine(output, divider());
        appendAmount(output, "Subtotal", number(document, "subtotal", 0));
        appendAmount(output, "Discount", number(document, "discount", 0));
        appendAmount(output, "Tax/GST", number(document, "tax", 0));
        appendAmount(output, "Transport", number(document, "transportAmount", 0));
        appendLine(output, divider());
        double total = number(document, "total", number(document, "subtotal", 0));
        appendAmount(output, "TOTAL", total);
        if (!quotation) {
            double paid = number(document, "paidAmount", 0);
            appendAmount(output, "Paid", paid);
            appendAmount(output, "Balance Due", Math.max(0, total - paid));
        }
        appendLine(output, divider());
        appendLine(output, "Thank you for your purchase");
        appendLine(output, "Improving Agriculture, Improving lives");
        appendLine(output, "");
        appendLine(output, "");
        appendLine(output, "");
        appendLine(output, "");
        return output.toByteArray();
    }

    static byte[] diagnostic() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        appendLine(output, "DARVI NATIVE SPP TEST");
        appendLine(output, "If visible, text printing works.");
        appendLine(output, "");
        appendLine(output, "");
        appendLine(output, "");
        return output.toByteArray();
    }

    private static void appendAmount(ByteArrayOutputStream output, String label, double amount) {
        appendWrapped(output, label + ": Rs. " + money(amount));
    }

    private static void appendIfPresent(ByteArrayOutputStream output, String line) {
        if (line != null && !line.trim().isEmpty() && !line.endsWith(": ")) {
            appendWrapped(output, line);
        }
    }

    private static void appendCentered(ByteArrayOutputStream output, String text) {
        String safe = ascii(text);
        int left = Math.max(0, (COLUMNS - safe.length()) / 2);
        appendLine(output, repeat(' ', left) + safe);
    }

    private static void appendWrapped(ByteArrayOutputStream output, String text) {
        if (text == null || text.isEmpty()) {
            appendLine(output, "");
            return;
        }
        String[] paragraphs = text.split("\\r?\\n", -1);
        for (String paragraph : paragraphs) {
            String remaining = ascii(paragraph).trim();
            if (remaining.isEmpty()) {
                appendLine(output, "");
                continue;
            }
            while (remaining.length() > COLUMNS) {
                int split = remaining.lastIndexOf(' ', COLUMNS);
                if (split <= 0) split = COLUMNS;
                appendLine(output, remaining.substring(0, split));
                remaining = remaining.substring(split).trim();
            }
            appendLine(output, remaining);
        }
    }

    private static void appendLine(ByteArrayOutputStream output, String line) {
        byte[] bytes = ascii(line).getBytes(StandardCharsets.US_ASCII);
        output.write(bytes, 0, bytes.length);
        output.write('\r');
        output.write('\n');
    }

    private static String ascii(String text) {
        StringBuilder safe = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            safe.append(character >= 32 && character <= 126 ? character : '?');
        }
        return safe.toString();
    }

    private static String value(JSONObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.isNull(key)) return fallback;
        String value = object.optString(key, fallback);
        return value == null ? fallback : value;
    }

    private static double number(JSONObject object, String key, double fallback) {
        if (object == null) return fallback;
        Object value = object.opt(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String money(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }

    private static String divider() {
        return repeat('-', COLUMNS);
    }

    private static String repeat(char character, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) text.append(character);
        return text.toString();
    }
}
