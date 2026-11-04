package com.darvigroup.billing;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int GREEN = Color.rgb(37, 78, 55);
    private static final int GREEN_DARK = Color.rgb(24, 57, 39);
    private static final int BACKGROUND = Color.rgb(243, 247, 243);
    private static final int SURFACE_TINT = Color.rgb(232, 241, 233);
    private static final int BORDER = Color.rgb(220, 230, 221);
    private static final int ACCENT = Color.rgb(219, 171, 91);
    private static final int INK = Color.rgb(33, 45, 37);
    private static final int MUTED = Color.rgb(106, 119, 110);
    private static final String PRINTER_PREFERENCES = "native_printer";
    private static final String PRINTER_ADDRESS_KEY = "address";
    private static final String PRINTER_NAME_KEY = "name";
    private static final String[] NAV_PAGES = {
        "dashboard", "leads", "invoices", "new", "estimates", "new-estimate", "plots",
        "products", "customers", "purchases", "new-purchase", "expenses", "accounts",
        "payroll", "incentives", "staff", "reports", "total-sales", "legacy", "settings"
    };
    private static final String[] NAV_LABELS = {
        "Dashboard", "Leads & mini CRM", "Invoices", "New invoice", "Quotations / estimates",
        "New quotation", "Vikshha plot register", "Products & stock", "Customers", "Purchases",
        "New purchase", "Expenses", "Account manager", "Staff salary", "Incentives",
        "Staff accounts", "Business reports", "Total sales report", "Old data migration", "Settings"
    };

    private NativeStore store;
    private String businessId = "darvi-group";
    private JSONObject user;
    private String page = "dashboard";
    private LinearLayout root;
    private LinearLayout content;
    private LinearLayout navigation;
    private ActivityResultLauncher<String> createBackup;
    private ActivityResultLauncher<String[]> openBackup;
    private ActivityResultLauncher<String> requestBluetoothPermission;
    private final ExecutorService printExecutor = Executors.newSingleThreadExecutor();
    private JSONObject pendingPrinterInvoice;
    private boolean pendingPrinterIsLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        store = new NativeStore(this);
        store.initialize();
        JSONObject app = store.getAppState();
        businessId = app.optString("activeBusinessId", "darvi-group");
        createBackup = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
            this::writeBackup);
        openBackup = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::readBackup);
        requestBluetoothPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
            granted -> {
                JSONObject pending = pendingPrinterInvoice;
                boolean pendingLabel = pendingPrinterIsLabel;
                pendingPrinterInvoice = null;
                pendingPrinterIsLabel = false;
                if (!granted) {
                    toast("Bluetooth permission was denied.");
                } else if (pending == null) {
                    choosePrinter(null);
                } else if (pendingLabel) {
                    choosePrinterForLabel(pending);
                } else {
                    choosePrinter(pending);
                }
            });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!"dashboard".equals(page) && user != null) {
                    page = "dashboard";
                    render();
                } else {
                    finish();
                }
            }
        });
        getWindow().setStatusBarColor(GREEN);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        user = findStaff(app.optString("currentUserId", ""));
        if (user == null) showLogin();
        else render();
    }

    private String nextDocumentNumber(String module, String prefix, boolean estimate) {
        String base = prefix + (estimate ? "-Q-" : "-");
        List<JSONObject> existing = store.list(businessId, module);
        int candidate = existing.size() + 1;
        boolean used;
        do {
            used = false;
            String number = base + candidate;
            for (JSONObject record : existing) {
                if (number.equals(record.optString("number"))) {
                    used = true;
                    candidate++;
                    break;
                }
            }
        } while (used);
        return base + candidate;
    }

    private int nextCustomerInvoiceSequence(String customerId) {
        int count = 0;
        int maximumSaved = 0;
        for (JSONObject invoice : store.list(businessId, "invoices")) {
            if (customerId.equals(invoice.optString("customerId"))) {
                count++;
                maximumSaved = Math.max(maximumSaved, invoice.optInt("customerInvoiceSequence"));
            }
        }
        return Math.max(count, maximumSaved) + 1;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        printExecutor.shutdown();
        if (store != null) store.close();
    }

    private void showLogin() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(24), dp(56), dp(24), dp(24));
        setContentView(root);
        TextView brand = text("DARVI BILLING", 26, GREEN, true);
        brand.setGravity(Gravity.CENTER);
        root.addView(brand, matchWidth());
        addText(root, "On-device billing workspace", 15, MUTED, false);
        addSpace(root, 28);
        LinearLayout loginCard = card();
        loginCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.addView(loginCard, matchWidth());
        List<JSONObject> staff = store.list("darvi-group", "staff");
        ArrayList<String> names = new ArrayList<>();
        ArrayList<JSONObject> enabledStaff = new ArrayList<>();
        for (JSONObject person : staff) {
            if (person.optBoolean("enabled", true)) {
                names.add(person.optString("name"));
                enabledStaff.add(person);
            }
        }
        Spinner staffPicker = spinner(names);
        loginCard.addView(fieldLabel("Staff account"), matchWidth());
        loginCard.addView(staffPicker, matchWidth());
        addSpace(root, 12);
        EditText pin = input("Staff PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        loginCard.addView(fieldLabel("PIN"), matchWidth());
        loginCard.addView(pin, matchWidth());
        addSpace(loginCard, 12);
        Button signIn = button("Sign in", true);
        loginCard.addView(signIn, matchWidth());
        addSpace(root, 12);
        TextView notice = text("Local demo PINs are not production authentication.", 13, MUTED, false);
        notice.setGravity(Gravity.CENTER);
        root.addView(notice, matchWidth());
        signIn.setOnClickListener(v -> {
            int position = staffPicker.getSelectedItemPosition();
            if (position < 0 || position >= enabledStaff.size()) {
                toast("No active staff account is available.");
                return;
            }
            JSONObject selected = enabledStaff.get(position);
            if (!selected.optString("pin", "1234").equals(pin.getText().toString().trim())) {
                pin.setError("Incorrect PIN");
                return;
            }
            user = selected;
            JSONObject appState = store.getAppState();
            put(appState, "currentUserId", user.optString("id"));
            store.putMeta("app", appState);
            render();
        });
    }

    private void render() {
        if (user == null) {
            showLogin();
            return;
        }
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        setContentView(root);

        LinearLayout toolbar = horizontal();
        toolbar.setPadding(dp(12), dp(8), dp(12), dp(8));
        toolbar.setBackground(roundedDrawable(Color.WHITE, 18, BORDER));
        toolbar.setElevation(dp(2));
        Button menu = button("☰", false);
        menu.setMinWidth(dp(46));
        toolbar.addView(menu, wrap());
        menu.setOnClickListener(v -> showNavigationMenu());
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(8), 0, dp(8), 0);
        heading.addView(text("DARVI BILLING", 10, MUTED, true), matchWidth());
        heading.addView(text(title(page), 17, INK, true), matchWidth());
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button businessButton = button(shortName(store.business(businessId).optString("name")), false);
        businessButton.setTextSize(12);
        toolbar.addView(businessButton, wrap());
        businessButton.setOnClickListener(v -> chooseBusiness());
        LinearLayout.LayoutParams toolbarParams = matchWidth();
        toolbarParams.setMargins(dp(10), dp(8), dp(10), 0);
        root.addView(toolbar, toolbarParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(22));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER);
        navigation.setBackground(roundedDrawable(Color.WHITE, 18, BORDER));
        navigation.setElevation(dp(3));
        navigation.setPadding(dp(4), dp(5), dp(4), dp(5));
        LinearLayout.LayoutParams navigationParams = matchWidth();
        navigationParams.setMargins(dp(8), 0, dp(8), dp(5));
        root.addView(navigation, navigationParams);
        renderPage();
        renderBottomNavigation();
    }

    private void renderPage() {
        content.removeAllViews();
        switch (page) {
            case "dashboard": renderDashboard(); break;
            case "reports":
            case "total-sales": renderReports(); break;
            case "settings": renderSettings(); break;
            case "legacy": renderLegacyTools(); break;
            case "accounts": renderAccounts(); break;
            case "new":
            case "new-estimate": showInvoiceEditor("new-estimate".equals(page)); break;
            case "new-purchase": showRecordForm("purchases", null); break;
            default:
                String module = moduleForPage(page);
                if (module == null) addCard("Screen unavailable", "Choose another section from the menu.");
                else renderModule(module);
        }
    }

    private void renderDashboard() {
        JSONObject profile = store.business(businessId);
        addHero("Good " + greeting() + ", " + user.optString("name", "team") + ".",
            profile.optString("name", "Darvi Billing") + " · On-device workspace");
        addSpace(content, 14);
        LinearLayout metrics = horizontal();
        metric(metrics, "Invoices", store.list(businessId, "invoices").size());
        metric(metrics, "Customers", store.list(businessId, "customers").size());
        metric(metrics, "Products", store.list(businessId, "products").size());
        content.addView(metrics, matchWidth());
        addSpace(content, 12);
        Button create = button("＋  Create invoice", true);
        content.addView(create, matchWidth());
        create.setOnClickListener(v -> openPage("new"));
        Button printer = button(printerButtonLabel(), false);
        content.addView(printer, matchWidth());
        printer.setOnClickListener(v -> choosePrinter(null));
        addCard("Phone lookup", "Search customers and CRM leads by name or phone.");
        EditText query = input("Name or phone", InputType.TYPE_CLASS_TEXT);
        content.addView(query, matchWidth());
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        Button lookup = button("Search", false);
        content.addView(lookup, matchWidth());
        content.addView(results, matchWidth());
        lookup.setOnClickListener(v -> renderPhoneLookup(query.getText().toString(), results));
        addText(content, "Quick actions", 18, INK, true);
        LinearLayout quick = horizontal();
        quickAction(quick, "Invoices", "invoices");
        quickAction(quick, "Products", "products");
        quickAction(quick, "Customers", "customers");
        content.addView(quick, matchWidth());
        addText(content, "Recent invoices", 18, INK, true);
        List<JSONObject> invoices = store.list(businessId, "invoices");
        for (int i = 0; i < Math.min(5, invoices.size()); i++) addRecordCard("invoices", invoices.get(i));
        if (invoices.isEmpty()) addCard("No invoices yet", "Create an invoice to record a sale.");
        addSmallFooter();
    }

    private void renderModule(String module) {
        addHero(title(page), moduleSubtitle(module));
        LinearLayout actions = horizontal();
        Button add = button("+ Add " + singular(module), true);
        actions.addView(add, new LinearLayout.LayoutParams(0, dp(48), 1));
        add.setEnabled(isAdmin() || Arrays.asList("invoices", "estimates", "leads", "purchases").contains(module));
        add.setOnClickListener(v -> showRecordForm(module, null));
        Button find = button("Search", false);
        actions.addView(find, new LinearLayout.LayoutParams(dp(96), dp(48)));
        content.addView(actions, matchWidth());
        EditText filter = input("Search " + plural(module), InputType.TYPE_CLASS_TEXT);
        content.addView(filter, matchWidth());
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list, matchWidth());
        find.setOnClickListener(v -> populateRecords(module, list, filter.getText().toString()));
        populateRecords(module, list, "");
        if ("products".equals(module)) {
            Button lowStock = button("Show low-stock products", false);
            content.addView(lowStock, matchWidth());
            lowStock.setOnClickListener(v -> populateRecords(module, list, "lowstock"));
        }
    }

    private void renderAccounts() {
        addHero("Accounts", "Cash, bank, UPI and card balances with local transaction history.");
        Button newAccount = button("+ Add account", true);
        content.addView(newAccount, matchWidth());
        newAccount.setOnClickListener(v -> showRecordForm("moneyAccounts", null));
        Button newTransaction = button("+ Record money in / out", false);
        content.addView(newTransaction, matchWidth());
        newTransaction.setOnClickListener(v -> showRecordForm("accountEntries", null));
        addText(content, "Accounts", 18, INK, true);
        populateRecords("moneyAccounts", content, "");
        addText(content, "Recent transactions", 18, INK, true);
        populateRecords("accountEntries", content, "");
    }

    private void renderReports() {
        List<JSONObject> invoices = store.list(businessId, "invoices");
        double sales = 0;
        double collected = 0;
        for (JSONObject invoice : invoices) {
            if (invoice.optBoolean("voided")) continue;
            sales += invoice.optDouble("total");
            collected += invoice.optDouble("paidAmount");
        }
        addHero("Sales summary", "A quick view of invoices, collections and outstanding balance.");
        LinearLayout metrics = horizontal();
        metric(metrics, "Sales", money(sales));
        metric(metrics, "Collected", money(collected));
        metric(metrics, "Balance", money(Math.max(0, sales - collected)));
        content.addView(metrics, matchWidth());
        addCard("Ledger overview", "Invoices: " + invoices.size() +
            "\nQuotations: " + store.list(businessId, "estimates").size() +
            "\nPurchases: " + store.list(businessId, "purchases").size() +
            "\nExpenses: " + store.list(businessId, "expenses").size() +
            "\nLeads: " + store.list(businessId, "leads").size() +
            "\nProducts: " + store.list(businessId, "products").size());
        addText(content, "Sales by invoice", 18, INK, true);
        populateRecords("invoices", content, "");
    }

    private void renderSettings() {
        JSONObject profile = store.business(businessId);
        addHero("Business profile", "Manage business details, printer and local data.");
        addCard(profile.optString("name", ""), profile.optString("address", "") +
            "\nPhone: " + profile.optString("phone", "") + "\nGSTIN: " + profile.optString("gstin", ""));
        Button edit = button("Edit business details", true);
        content.addView(edit, matchWidth());
        edit.setOnClickListener(v -> editBusiness());
        Button export = button("Export native backup (JSON)", false);
        content.addView(export, matchWidth());
        export.setOnClickListener(v -> createBackup.launch("darvi-billing-backup.json"));
        Button importFile = button("Import backup / legacy app data (JSON)", false);
        content.addView(importFile, matchWidth());
        importFile.setOnClickListener(v -> openBackup.launch(new String[]{"application/json", "text/*"}));
        Button legacy = button("Open previous web app to export its data", false);
        content.addView(legacy, matchWidth());
        legacy.setOnClickListener(v -> openLegacyApp());
        Button printer = button(printerButtonLabel(), false);
        content.addView(printer, matchWidth());
        printer.setOnClickListener(v -> choosePrinter(null));
        Button signOut = button("Sign out", false);
        content.addView(signOut, matchWidth());
        signOut.setOnClickListener(v -> {
            JSONObject app = store.getAppState();
            put(app, "currentUserId", "");
            store.putMeta("app", app);
            user = null;
            showLogin();
        });
        addCard("Demo security note", "This prototype stores data on this device. Local PIN and role checks are not production authentication.");
    }

    private void renderLegacyTools() {
        addHero("Data migration", "Import or export records from the previous app.");
        addCard("Keep your previous app records",
            "Open the previous web app, export its JSON backup, then return and import the file. The native importer accepts the existing business backup format.");
        Button legacy = button("Open previous web app", true);
        content.addView(legacy, matchWidth());
        legacy.setOnClickListener(v -> openLegacyApp());
        Button export = button("Export native data", false);
        content.addView(export, matchWidth());
        export.setOnClickListener(v -> createBackup.launch("darvi-billing-backup.json"));
        Button importFile = button("Import JSON backup", false);
        content.addView(importFile, matchWidth());
        importFile.setOnClickListener(v -> openBackup.launch(new String[]{"application/json", "text/*"}));
    }

    private void renderPhoneLookup(String query, LinearLayout results) {
        results.removeAllViews();
        String normalized = query.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.isEmpty()) {
            addText(results, "Enter a name or phone number.", 13, MUTED, false);
            return;
        }
        int count = 0;
        for (String module : Arrays.asList("customers", "leads")) {
            for (JSONObject item : store.list(businessId, module)) {
                String value = (item.optString("name") + item.optString("phone"))
                    .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (value.contains(normalized)) {
                    addRecordCard(module, item, results);
                    count++;
                }
            }
        }
        if (count == 0) addText(results, "No matching customer or lead.", 13, MUTED, false);
    }

    private void populateRecords(String module, LinearLayout target, String filter) {
        target.removeAllViews();
        int shown = 0;
        for (JSONObject row : store.list(businessId, module)) {
            if ("lowstock".equals(filter)) {
                if (row.optDouble("stock") > row.optDouble("lowStock")) continue;
            } else if (!filter.isEmpty() && !row.toString().toLowerCase(Locale.ROOT)
                .contains(filter.toLowerCase(Locale.ROOT))) continue;
            addRecordCard(module, row, target);
            shown++;
        }
        if (shown == 0) addText(target, "No " + plural(module).toLowerCase(Locale.ROOT) + " to show.", 14, MUTED, false);
    }

    private void addRecordCard(String module, JSONObject record) {
        addRecordCard(module, record, content);
    }

    private void addRecordCard(String module, JSONObject record, LinearLayout target) {
        LinearLayout box = card();
        String headline = record.optString("name");
        if (headline.isEmpty()) headline = record.optString("number");
        if (headline.isEmpty()) headline = record.optString("plotNumber");
        if (headline.isEmpty()) headline = record.optString("billNumber");
        if (headline.isEmpty()) headline = record.optString("employeeCode", singular(module));
        addText(box, headline, 16, INK, true);
        addText(box, recordSummary(module, record), 13, MUTED, false);
        box.setOnClickListener(v -> showRecordActions(module, record));
        target.addView(box, matchWidth());
    }

    private void showRecordActions(String module, JSONObject record) {
        ArrayList<String> actions = new ArrayList<>();
        ArrayList<Runnable> handlers = new ArrayList<>();
        actions.add("View details");
        handlers.add(() -> showDetails(module, record));
        if ("invoices".equals(module)) {
            actions.add("Print receipt");
            handlers.add(() -> choosePrinter(record));
            JSONObject shipping = record.optJSONObject("shippingSnapshot");
            if (shipping != null && (!shipping.optString("name").isEmpty() || !shipping.optString("address").isEmpty())) {
                actions.add("Print shipping label");
                handlers.add(() -> printShippingLabel(record));
            }
            actions.add("Record payment");
            handlers.add(() -> recordPayment(record));
            if (isAdmin() && !record.optBoolean("voided")) {
                actions.add("Void invoice");
                handlers.add(() -> voidInvoice(record));
            }
        } else if ("estimates".equals(module)) {
            if (record.optString("convertedInvoiceId").isEmpty()) {
                actions.add("Convert to invoice");
                handlers.add(() -> convertEstimate(record));
            }
        } else if ("leads".equals(module)) {
            actions.add("Add follow-up note");
            handlers.add(() -> addLeadNote(record));
            actions.add("Convert to customer");
            handlers.add(() -> convertLead(record));
        } else if ("customers".equals(module)) {
            actions.add("View customer history");
            handlers.add(() -> showCustomerHistory(record));
        } else if ("plots".equals(module)) {
            actions.add("View payment history");
            handlers.add(() -> showPlotReceipts(record));
            actions.add("Record plot payment");
            handlers.add(() -> recordPlotReceipt(record));
        } else if (isAdmin()) {
            actions.add("Edit");
            handlers.add(() -> showRecordForm(module, record));
            actions.add("Delete");
            handlers.add(() -> confirmDelete(module, record));
        }
        new AlertDialog.Builder(this).setTitle(recordTitle(module, record))
            .setItems(actions.toArray(new String[0]), (dialog, which) -> handlers.get(which).run()).show();
    }

    private void showDetails(String module, JSONObject record) {
        StringBuilder details = new StringBuilder();
        JSONArray names = record.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                if ("id".equals(key) || "businessId".equals(key)) continue;
                Object value = record.opt(key);
                if (value instanceof JSONObject || value instanceof JSONArray) continue;
                details.append(labelFor(key)).append(": ").append(String.valueOf(value)).append("\n");
            }
        }
        JSONArray lines = record.optJSONArray("lines");
        if (lines != null) {
            details.append("\nItems\n");
            for (int i = 0; i < lines.length(); i++) {
                JSONObject line = lines.optJSONObject(i);
                if (line != null) details.append(line.optString("name")).append(" × ")
                    .append(line.optString("qty")).append("  ").append(money(line.optDouble("total"))).append("\n");
            }
        }
        new AlertDialog.Builder(this).setTitle(recordTitle(module, record))
            .setMessage(details.length() == 0 ? record.toString() : details.toString())
            .setPositiveButton("Close", null).show();
    }

    private void showRecordForm(String module, JSONObject existing) {
        if (!isAdmin() && !Arrays.asList("invoices", "estimates", "leads", "purchases").contains(module)) {
            toast("This account cannot change " + plural(module).toLowerCase(Locale.ROOT) + ".");
            return;
        }
        List<String> keys = fieldsFor(module);
        if (keys.isEmpty()) {
            toast("This form is not available.");
            return;
        }
        content.removeAllViews();
        String subtitle = moduleSubtitle(module);
        if (subtitle.isEmpty()) subtitle = "Add or update " + singular(module) + " details.";
        addHero((existing == null ? "New " : "Edit ") + singular(module), subtitle);
        LinearLayout form = formColumn();
        form.setBackground(roundedDrawable(Color.WHITE, 18, BORDER));
        form.setElevation(dp(1));
        Map<String, EditText> fields = new LinkedHashMap<>();
        for (String key : keys) {
            String label = labelFor(key);
            form.addView(fieldLabel(label), matchWidth());
            EditText field = input("", inputTypeFor(key));
            field.setContentDescription(label);
            if (existing != null) field.setText(existing.optString(key, ""));
            fields.put(key, field);
            form.addView(field, matchWidth());
        }
        content.addView(form, matchWidth());
        LinearLayout formActions = horizontal();
        Button cancel = button("Cancel", false);
        Button save = button(existing == null ? "Save " + singular(module) : "Save changes", true);
        formActions.addView(cancel, new LinearLayout.LayoutParams(0, dp(52), 1));
        formActions.addView(save, new LinearLayout.LayoutParams(0, dp(52), 1));
        content.addView(formActions, matchWidth());
        cancel.setOnClickListener(v -> render());
        save.setOnClickListener(view -> {
            try {
                JSONObject record = existing == null ? new JSONObject() : new JSONObject(existing.toString());
                for (String key : keys) {
                    String value = fields.get(key).getText().toString().trim();
                    if (value.isEmpty() && required(key)) {
                        fields.get(key).setError("Required");
                        return;
                    }
                    putField(record, key, value);
                }
                if ("staff".equals(module)) {
                    if (record.optString("pin").isEmpty()) put(record, "pin", "1234");
                    if (!record.has("enabled")) put(record, "enabled", true);
                    if (record.optJSONArray("businessIds") == null)
                        put(record, "businessIds", new JSONArray(Collections.singletonList(businessId)));
                }
                if ("purchases".equals(module)) {
                    double subtotal = record.optDouble("quantity") * record.optDouble("purchaseRate");
                    double tax = subtotal * record.optDouble("tax") / 100;
                    put(record, "subtotal", round(subtotal));
                    put(record, "taxAmount", round(tax));
                    put(record, "total", round(subtotal + tax));
                    applyPurchaseStock(record);
                } else if ("payroll".equals(module)) {
                    put(record, "netPay", Math.max(0, record.optDouble("grossPay") -
                        record.optDouble("leaveDeduction") - record.optDouble("otherAdjustment")));
                } else if ("incentives".equals(module) && record.optDouble("amount") <= 0) {
                    put(record, "amount", round(record.optDouble("basisAmount") *
                        record.optDouble("ratePercent") / 100));
                } else if ("plots".equals(module) && record.optString("status").isEmpty()) {
                    put(record, "status", "available");
                }
                if ("staff".equals(module)) {
                    JSONArray assignedBusinesses = record.optJSONArray("businessIds");
                    if (assignedBusinesses == null || assignedBusinesses.length() == 0) {
                        store.saveRecord("darvi-group", module, record);
                    } else {
                        for (int i = 0; i < assignedBusinesses.length(); i++) {
                            store.saveRecord(assignedBusinesses.optString(i), module,
                                new JSONObject(record.toString()));
                        }
                    }
                } else {
                    store.saveRecord(businessId, module, record);
                }
                logEvent("save-" + module, record.optString("id"));
                render();
                toast(singular(module) + " saved.");
            } catch (JSONException e) {
                toast("Could not save this record: " + e.getMessage());
            }
        });
    }

    private void showInvoiceEditor(boolean estimate) {
        addHero(estimate ? "New quotation" : "New invoice",
            "Add customer, items, tax and payment details.");
        LinearLayout form = formColumn();
        form.setBackground(roundedDrawable(Color.WHITE, 18, BORDER));
        form.setElevation(dp(1));
        List<JSONObject> customerRecords = customerOptionsRecords();
        Spinner customer = spinner(customerOptions());
        addFormSection(form, "CUSTOMER");
        form.addView(fieldLabel("Customer"), matchWidth());
        form.addView(customer, matchWidth());
        LinearLayout lineList = new LinearLayout(this);
        lineList.setOrientation(LinearLayout.VERTICAL);
        addFormSection(form, "ITEMS");
        form.addView(lineList, matchWidth());
        ArrayList<InvoiceLineInput> lines = new ArrayList<>();
        Button addLine = button("+ Add item", false);
        form.addView(addLine, matchWidth());
        addLine.setOnClickListener(v -> addInvoiceLine(lineList, lines));
        addInvoiceLine(lineList, lines);
        Spinner taxMode = spinner(Arrays.asList("CGST + SGST", "IGST", "No GST"));
        addFormSection(form, "TAX AND PAYMENT");
        form.addView(fieldLabel("Tax mode"), matchWidth());
        form.addView(taxMode, matchWidth());
        EditText paid = addFormField(form, "Amount paid", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner payment = spinner(Arrays.asList("Cash", "UPI", "Card", "Bank", "Credit", "Other"));
        form.addView(fieldLabel("Payment method"), matchWidth());
        form.addView(payment, matchWidth());
        addFormSection(form, "NOTES");
        EditText notes = addFormField(form, "Notes", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        addFormSection(form, "SHIPPING");
        EditText shipName = addFormField(form, "Shipping name", InputType.TYPE_CLASS_TEXT);
        EditText shipPhone = addFormField(form, "Shipping phone", InputType.TYPE_CLASS_PHONE);
        EditText shipAddress = addFormField(form, "Shipping address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText shipCity = addFormField(form, "Shipping city", InputType.TYPE_CLASS_TEXT);
        EditText shipState = addFormField(form, "Shipping state", InputType.TYPE_CLASS_TEXT);
        EditText shipPin = addFormField(form, "Shipping PIN code", InputType.TYPE_CLASS_NUMBER);
        customer.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                JSONObject selected = optionAt(customerRecords, position);
                shipName.setText(selected.optString("shippingName", selected.optString("name")));
                shipPhone.setText(selected.optString("shippingPhone", selected.optString("phone")));
                shipAddress.setText(selected.optString("shippingAddress", selected.optString("address")));
                shipCity.setText(selected.optString("shippingCity"));
                shipState.setText(selected.optString("shippingState"));
                shipPin.setText(selected.optString("shippingPin", selected.optString("shippingPincode")));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        content.addView(form, matchWidth());
        LinearLayout formActions = horizontal();
        Button cancel = button("Cancel", false);
        Button save = button(estimate ? "Save quotation" : "Save invoice", true);
        formActions.addView(cancel, new LinearLayout.LayoutParams(0, dp(52), 1));
        formActions.addView(save, new LinearLayout.LayoutParams(0, dp(52), 1));
        content.addView(formActions, matchWidth());
        cancel.setOnClickListener(v -> openPage(estimate ? "estimates" : "invoices"));
        save.setOnClickListener(view -> {
            try {
                if (lines.isEmpty()) {
                    toast("Add at least one product.");
                    return;
                }
                JSONObject selectedCustomer = optionAt(customerRecords, customer.getSelectedItemPosition());
                JSONArray itemRows = new JSONArray();
                double subtotal = 0, discountTotal = 0, taxTotal = 0, total = 0;
                for (InvoiceLineInput line : lines) {
                    JSONObject product = optionAt(line.products, line.product.getSelectedItemPosition());
                    double qty = number(line.quantity.getText().toString(), 0);
                    double price = number(line.price.getText().toString(), product.optDouble("price"));
                    double discPct = Math.max(0, Math.min(100, number(line.discount.getText().toString(), 0)));
                    double taxPct = Math.max(0, number(line.tax.getText().toString(), product.optDouble("tax")));
                    if (qty <= 0) {
                        line.quantity.setError("Quantity must be greater than zero");
                        return;
                    }
                    double gross = round(qty * price);
                    double disc = round(gross * discPct / 100);
                    double taxable = round(gross - disc);
                    double lineTax = "No GST".equals(taxMode.getSelectedItem()) ? 0 : round(taxable * taxPct / 100);
                    double lineTotal = round(taxable + lineTax);
                    JSONObject item = new JSONObject();
                    item.put("productId", product.optString("id"));
                    item.put("name", product.optString("name"));
                    item.put("unit", product.optString("unit"));
                    item.put("qty", qty);
                    item.put("price", price);
                    item.put("discount", discPct);
                    item.put("tax", taxPct);
                    item.put("gross", gross);
                    item.put("discountAmount", disc);
                    item.put("taxAmount", lineTax);
                    item.put("total", lineTotal);
                    itemRows.put(item);
                    subtotal += gross;
                    discountTotal += disc;
                    taxTotal += lineTax;
                    total += lineTotal;
                }
                String module = estimate ? "estimates" : "invoices";
                String prefix = store.business(businessId).optString("invoicePrefix", "DB");
                JSONObject document = new JSONObject();
                document.put("id", java.util.UUID.randomUUID().toString());
                document.put("number", nextDocumentNumber(module, prefix, estimate));
                document.put("date", java.time.LocalDate.now().toString());
                document.put("kind", estimate ? "estimate" : "invoice");
                document.put("customerId", selectedCustomer.optString("id", "walk-in"));
                document.put("customerName", selectedCustomer.optString("name", "Walk-in Customer"));
                document.put("customerInvoiceSequence", estimate ? 0 : nextCustomerInvoiceSequence(selectedCustomer.optString("id")));
                document.put("customerInvoiceRef", estimate ? "" : selectedCustomer.optString("customerCode", "CUST") +
                    "-" + document.optInt("customerInvoiceSequence"));
                document.put("lines", itemRows);
                document.put("subtotal", round(subtotal));
                document.put("discount", round(discountTotal));
                document.put("tax", round(taxTotal));
                document.put("total", round(total));
                document.put("paidAmount", estimate ? 0 : Math.min(total, number(paid.getText().toString(), 0)));
                document.put("paymentMethod", String.valueOf(payment.getSelectedItem()));
                document.put("taxMode", String.valueOf(taxMode.getSelectedItem()));
                document.put("notes", notes.getText().toString().trim());
                document.put("createdBy", user.optString("id"));
                document.put("salespersonId", user.optString("id"));
                document.put("voided", false);
                JSONObject shipping = new JSONObject();
                shipping.put("name", shipName.getText().toString().trim());
                shipping.put("phone", shipPhone.getText().toString().trim());
                shipping.put("address", shipAddress.getText().toString().trim());
                shipping.put("city", shipCity.getText().toString().trim());
                shipping.put("state", shipState.getText().toString().trim());
                shipping.put("pincode", shipPin.getText().toString().trim());
                if (!shipName.getText().toString().trim().isEmpty() ||
                    !shipAddress.getText().toString().trim().isEmpty()) document.put("shippingSnapshot", shipping);
                if (estimate) store.saveRecord(businessId, "estimates", document);
                else {
                    document.put("status", document.optDouble("paidAmount") >= total ? "paid"
                        : document.optDouble("paidAmount") > 0 ? "partial" : "unpaid");
                    store.saveRecord(businessId, "invoices", document);
                    for (int i = 0; i < itemRows.length(); i++) {
                        JSONObject item = itemRows.getJSONObject(i);
                        adjustStock(item.optString("productId"), -item.optDouble("qty"), "invoice");
                    }
                }
                logEvent(estimate ? "create-estimate" : "create-invoice", document.optString("number"));
                page = estimate ? "estimates" : "invoices";
                render();
                toast(estimate ? "Quotation saved." : "Invoice saved.");
            } catch (JSONException e) {
                toast("Could not save the document: " + e.getMessage());
            }
        });
    }

    private void addInvoiceLine(LinearLayout container, List<InvoiceLineInput> lines) {
        List<JSONObject> products = store.list(businessId, "products");
        if (products.isEmpty()) {
            toast("Add a product before creating a document.");
            return;
        }
        ArrayList<String> productNames = new ArrayList<>();
        for (JSONObject product : products) productNames.add(product.optString("name"));
        LinearLayout row = card();
        Spinner productSpinner = spinner(productNames);
        EditText quantity = input("Quantity", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        quantity.setText("1");
        EditText price = input("Unit price", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText tax = input("Line tax %", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText discount = input("Line discount %", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout productField = new LinearLayout(this);
        productField.setOrientation(LinearLayout.VERTICAL);
        productField.addView(fieldLabel("Product"), matchWidth());
        productField.addView(productSpinner, matchWidth());
        row.addView(productField, matchWidth());
        LinearLayout qtyRate = horizontal();
        qtyRate.addView(inputCell("Quantity", quantity), new LinearLayout.LayoutParams(0, -2, 1));
        qtyRate.addView(inputCell("Unit price", price), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(qtyRate, matchWidth());
        LinearLayout taxDiscount = horizontal();
        taxDiscount.addView(inputCell("Line tax %", tax), new LinearLayout.LayoutParams(0, -2, 1));
        taxDiscount.addView(inputCell("Discount %", discount), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(taxDiscount, matchWidth());
        container.addView(row, matchWidth());
        InvoiceLineInput line = new InvoiceLineInput(productSpinner, quantity, price, tax, discount, products);
        lines.add(line);
        productSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                JSONObject selected = optionAt(products, position);
                price.setText(String.valueOf(selected.optDouble("price")));
                tax.setText(String.valueOf(selected.optDouble("tax")));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private void editBusiness() {
        JSONObject profile = store.business(businessId);
        LinearLayout form = formColumn();
        EditText name = addFormField(form, "Business name", InputType.TYPE_CLASS_TEXT);
        name.setText(profile.optString("name"));
        EditText address = addFormField(form, "Address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        address.setText(profile.optString("address"));
        EditText phone = addFormField(form, "Phone", InputType.TYPE_CLASS_PHONE);
        phone.setText(profile.optString("phone"));
        EditText gstin = addFormField(form, "GSTIN", InputType.TYPE_CLASS_TEXT);
        gstin.setText(profile.optString("gstin"));
        EditText prefix = addFormField(form, "Invoice prefix", InputType.TYPE_CLASS_TEXT);
        prefix.setText(profile.optString("invoicePrefix"));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Business details").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
                put(profile, "name", name.getText().toString().trim());
                put(profile, "address", address.getText().toString().trim());
                put(profile, "phone", phone.getText().toString().trim());
                put(profile, "gstin", gstin.getText().toString().trim());
                put(profile, "invoicePrefix", prefix.getText().toString().trim());
                store.saveBusiness(businessId, profile);
                render();
            }).create();
        dialog.show();
        styleFormDialog(dialog);
    }

    private void showPlotReceipts(JSONObject plot) {
        String plotId = plot.optString("id");
        StringBuilder history = new StringBuilder();
        for (JSONObject receipt : store.list(businessId, "plotReceipts")) {
            if (plotId.equals(receipt.optString("plotId"))) {
                history.append(receipt.optString("receiptDate")).append(" · ")
                    .append(money(receipt.optDouble("amount"))).append(" · ")
                    .append(receipt.optString("method")).append("\n");
            }
        }
        new AlertDialog.Builder(this).setTitle(plot.optString("plotNumber") + " · Payments")
            .setMessage(history.length() == 0 ? "No plot payments recorded." : history.toString())
            .setPositiveButton("Close", null).show();
    }

    private void chooseBusiness() {
        ArrayList<String> names = new ArrayList<>();
        for (String id : NativeStore.BUSINESS_IDS) names.add(store.business(id).optString("name", id));
        new AlertDialog.Builder(this).setTitle("Switch business")
            .setItems(names.toArray(new String[0]), (d, which) -> {
                String selected = NativeStore.BUSINESS_IDS.get(which);
                if (!userBusinessIds().contains(selected)) {
                    toast("This staff account is not assigned to that business.");
                    return;
                }
                businessId = selected;
                JSONObject app = store.getAppState();
                put(app, "activeBusinessId", businessId);
                store.putMeta("app", app);
                page = "dashboard";
                render();
            }).show();
    }

    private void showNavigationMenu() {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> pages = new ArrayList<>();
        for (int i = 0; i < NAV_PAGES.length; i++) {
            if (can(NAV_PAGES[i])) {
                pages.add(NAV_PAGES[i]);
                labels.add(NAV_LABELS[i]);
            }
        }
        new AlertDialog.Builder(this).setTitle("Darvi Billing")
            .setItems(labels.toArray(new String[0]), (d, which) -> openPage(pages.get(which))).show();
    }

    private void renderBottomNavigation() {
        navigation.removeAllViews();
        String[] pages = {"dashboard", "leads", "invoices", "new", "reports"};
        String[] labels = {"Home", "CRM", "Invoices", "＋ New", "Reports"};
        for (int i = 0; i < pages.length; i++) {
            if (!can(pages[i])) continue;
            final String target = pages[i];
            Button tab = button(labels[i], target.equals(page));
            tab.setTextSize(10);
            tab.setMinWidth(0);
            tab.setPadding(dp(3), dp(8), dp(3), dp(8));
            tab.setBackground(roundedDrawable(target.equals(page) ? GREEN : Color.WHITE,
                14, target.equals(page) ? GREEN : Color.TRANSPARENT));
            tab.setTextColor(target.equals(page) ? Color.WHITE : MUTED);
            tab.setElevation(target.equals(page) ? dp(1) : 0);
            navigation.addView(tab, new LinearLayout.LayoutParams(0, dp(48), 1));
            tab.setOnClickListener(v -> openPage(target));
        }
    }

    private void openPage(String destination) {
        if (!can(destination)) {
            toast("This account cannot open that section.");
            return;
        }
        page = destination;
        render();
    }

    private void choosePrinter(JSONObject invoice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pendingPrinterInvoice = invoice;
            pendingPrinterIsLabel = false;
            requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            toast("This device does not support Bluetooth.");
            return;
        }
        if (!adapter.isEnabled()) {
            startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            toast("Turn on Bluetooth, then select the printer again.");
            return;
        }
        try {
            if (invoice != null) {
                BluetoothDevice saved = savedPrinter(adapter);
                if (saved != null) {
                    printReceipt(saved, invoice);
                    return;
                }
            }
            List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
            if (devices.isEmpty()) {
                toast("Pair the printer in Android Bluetooth settings first.");
                return;
            }
            String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                BluetoothDevice device = devices.get(i);
                labels[i] = deviceName(device) + " · " + device.getAddress();
            }
            new AlertDialog.Builder(this).setTitle("Choose paired printer")
                .setItems(labels, (dialog, which) -> {
                    BluetoothDevice selected = devices.get(which);
                    rememberPrinter(selected);
                    if (invoice == null) printTest(selected);
                    else printReceipt(selected, invoice);
                }).show();
        } catch (SecurityException e) {
            toast("Bluetooth permission is required to list paired printers.");
        }
    }

    private void printReceipt(BluetoothDevice device, JSONObject invoice) {
        toast("Sending full receipt to " + deviceName(device) + ". Please wait for printing to finish.");
        JSONObject profile = store.business(businessId);
        JSONObject copy;
        try {
            copy = new JSONObject(invoice.toString());
        } catch (JSONException e) {
            toast("Could not prepare this receipt.");
            return;
        }
        printExecutor.execute(() -> {
            try {
                int bytes = NativePrinter.printInvoice(this, device, profile, copy);
                runOnUiThread(() -> toast(bytes + " bytes written. The printer does not return print confirmation."));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Print failed: " + e.getMessage()));
            }
        });
    }

    private void printTest(BluetoothDevice device) {
        toast("Sending printer test to " + deviceName(device) + "…");
        printExecutor.execute(() -> {
            try {
                int bytes = NativePrinter.testPrint(device);
                runOnUiThread(() -> toast(bytes + " test bytes written. Check the paper output."));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Printer test failed: " + e.getMessage()));
            }
        });
    }

    private void printShippingLabel(JSONObject invoice) {
        choosePrinterForLabel(invoice);
    }

    private void sendShippingLabel(BluetoothDevice device, JSONObject invoice) {
        printExecutor.execute(() -> {
            try {
                int bytes = NativePrinter.printShippingLabel(device, invoice);
                runOnUiThread(() -> toast(bytes + " label bytes written. Check the paper output."));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Shipping label failed: " + e.getMessage()));
            }
        });
    }

    private void choosePrinterForLabel(JSONObject invoice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pendingPrinterInvoice = invoice;
            pendingPrinterIsLabel = true;
            requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT);
            return;
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                toast("Enable Bluetooth and pair a printer first.");
                return;
            }
            BluetoothDevice saved = savedPrinter(adapter);
            if (saved != null) {
                sendShippingLabel(saved, invoice);
                return;
            }
            List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
            if (devices.isEmpty()) {
                toast("Pair the printer in Android Bluetooth settings first.");
                return;
            }
            String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) labels[i] = deviceName(devices.get(i));
            new AlertDialog.Builder(this).setTitle("Choose label printer")
                .setItems(labels, (d, which) -> {
                    BluetoothDevice device = devices.get(which);
                    rememberPrinter(device);
                    sendShippingLabel(device, invoice);
                }).show();
        } catch (SecurityException e) {
            toast("Bluetooth permission is required to list paired printers.");
        }
    }

    private BluetoothDevice savedPrinter(BluetoothAdapter adapter) {
        String address = getSharedPreferences(PRINTER_PREFERENCES, MODE_PRIVATE)
            .getString(PRINTER_ADDRESS_KEY, "");
        if (address.isEmpty()) return null;
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            if (address.equalsIgnoreCase(device.getAddress())) return device;
        }
        getSharedPreferences(PRINTER_PREFERENCES, MODE_PRIVATE).edit()
            .remove(PRINTER_ADDRESS_KEY).apply();
        return null;
    }

    private void rememberPrinter(BluetoothDevice device) {
        getSharedPreferences(PRINTER_PREFERENCES, MODE_PRIVATE).edit()
            .putString(PRINTER_ADDRESS_KEY, device.getAddress())
            .putString(PRINTER_NAME_KEY, deviceName(device))
            .apply();
    }

    private String printerButtonLabel() {
        String name = getSharedPreferences(PRINTER_PREFERENCES, MODE_PRIVATE)
            .getString(PRINTER_NAME_KEY, "");
        return name.isEmpty() ? "Select or test Bluetooth printer" : "Printer: " + name + " (change / test)";
    }

    private void showCustomerHistory(JSONObject customer) {
        String customerId = customer.optString("id");
        StringBuilder history = new StringBuilder();
        for (JSONObject invoice : store.list(businessId, "invoices")) {
            if (customerId.equals(invoice.optString("customerId"))) {
                history.append(invoice.optString("number")).append(" · ")
                    .append(invoice.optString("date")).append(" · ")
                    .append(money(invoice.optDouble("total"))).append("\n");
            }
        }
        new AlertDialog.Builder(this).setTitle(customer.optString("name") + " · History")
            .setMessage(history.length() == 0 ? "No invoices for this customer." : history.toString())
            .setPositiveButton("Close", null).show();
    }

    private void recordPlotReceipt(JSONObject plot) {
        LinearLayout form = formColumn();
        EditText amount = addFormField(form, "Payment amount", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText date = addFormField(form, "Receipt date", InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        date.setText(java.time.LocalDate.now().toString());
        EditText method = addFormField(form, "Payment method", InputType.TYPE_CLASS_TEXT);
        EditText reference = addFormField(form, "Reference", InputType.TYPE_CLASS_TEXT);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Plot payment").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
                double paid = number(amount.getText().toString(), 0);
                if (paid <= 0) {
                    amount.setError("Enter an amount above zero");
                    return;
                }
                JSONObject receipt = new JSONObject();
                put(receipt, "plotId", plot.optString("id"));
                put(receipt, "plotNumber", plot.optString("plotNumber"));
                put(receipt, "amount", paid);
                put(receipt, "receiptDate", date.getText().toString());
                put(receipt, "method", method.getText().toString());
                put(receipt, "reference", reference.getText().toString());
                put(receipt, "recordedBy", user.optString("id"));
                store.saveRecord(businessId, "plotReceipts", receipt);
                put(plot, "paidAmount", plot.optDouble("paidAmount") + paid);
                store.saveRecord(businessId, "plots", plot);
                render();
                toast("Plot payment recorded.");
            }).create();
        dialog.show();
        styleFormDialog(dialog);
    }

    private void recordPayment(JSONObject invoice) {
        LinearLayout form = formColumn();
        EditText amount = addFormField(form, "Payment amount",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setText(String.valueOf(Math.max(0, invoice.optDouble("total") - invoice.optDouble("paidAmount"))));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Record payment").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
                double paid = invoice.optDouble("paidAmount") + Math.max(0, number(amount.getText().toString(), 0));
                put(invoice, "paidAmount", Math.min(invoice.optDouble("total"), paid));
                put(invoice, "status", paid >= invoice.optDouble("total") ? "paid" : "partial");
                store.saveRecord(businessId, "invoices", invoice);
                logEvent("record-payment", invoice.optString("number"));
                render();
                toast("Payment recorded.");
            }).create();
        dialog.show();
        styleFormDialog(dialog);
    }

    private void voidInvoice(JSONObject invoice) {
        new AlertDialog.Builder(this).setTitle("Void invoice?")
            .setMessage("This marks the invoice void and restores its stock quantities.")
            .setNegativeButton("Cancel", null).setPositiveButton("Void", (d, w) -> {
                if (invoice.optBoolean("voided")) return;
                put(invoice, "voided", true);
                JSONArray lines = invoice.optJSONArray("lines");
                if (lines != null) for (int i = 0; i < lines.length(); i++) {
                    JSONObject line = lines.optJSONObject(i);
                    if (line != null) adjustStock(line.optString("productId"), line.optDouble("qty"), "void");
                }
                store.saveRecord(businessId, "invoices", invoice);
                logEvent("void-invoice", invoice.optString("number"));
                render();
            }).show();
    }

    private void convertEstimate(JSONObject estimate) {
        try {
            JSONObject invoice = new JSONObject();
            String prefix = store.business(businessId).optString("invoicePrefix", "DB");
            String customerId = estimate.optString("customerId", "walk-in");
            JSONObject customer = store.find(businessId, "customers", customerId);
            invoice.put("id", java.util.UUID.randomUUID().toString());
            invoice.put("number", nextDocumentNumber("invoices", prefix, false));
            invoice.put("date", java.time.LocalDate.now().toString());
            invoice.put("customerId", customerId);
            invoice.put("customerName", estimate.optString("customerName", "Walk-in Customer"));
            int customerSequence = nextCustomerInvoiceSequence(customerId);
            invoice.put("customerInvoiceSequence", customerSequence);
            invoice.put("customerInvoiceRef", (customer == null ? "CUST" :
                customer.optString("customerCode", "CUST")) + "-" + customerSequence);
            invoice.put("lines", estimate.optJSONArray("lines") == null ? new JSONArray() :
                new JSONArray(estimate.optJSONArray("lines").toString()));
            invoice.put("subtotal", estimate.optDouble("subtotal"));
            invoice.put("discount", estimate.optDouble("discount"));
            invoice.put("tax", estimate.optDouble("tax"));
            invoice.put("total", estimate.optDouble("total"));
            invoice.put("paidAmount", 0);
            invoice.put("paymentMethod", "Cash");
            invoice.put("taxMode", estimate.optString("taxMode", "CGST + SGST"));
            invoice.put("notes", estimate.optString("notes"));
            invoice.put("createdBy", user.optString("id"));
            invoice.put("status", "unpaid");
            invoice.put("voided", false);
            if (estimate.optJSONObject("shippingSnapshot") != null)
                invoice.put("shippingSnapshot", new JSONObject(estimate.optJSONObject("shippingSnapshot").toString()));
            store.saveRecord(businessId, "invoices", invoice);
            JSONArray lines = invoice.optJSONArray("lines");
            if (lines != null) for (int i = 0; i < lines.length(); i++) {
                JSONObject line = lines.optJSONObject(i);
                if (line != null) adjustStock(line.optString("productId"), -line.optDouble("qty"), "invoice");
            }
            put(estimate, "convertedInvoiceId", invoice.optString("id"));
            store.saveRecord(businessId, "estimates", estimate);
            render();
            toast("Quotation converted to invoice.");
        } catch (JSONException e) {
            toast("Could not convert quotation: " + e.getMessage());
        }
    }

    private void addLeadNote(JSONObject lead) {
        LinearLayout form = formColumn();
        EditText note = addFormField(form, "Follow-up note",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Lead activity").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Save note", (d, w) -> {
                JSONArray history = lead.optJSONArray("history");
                if (history == null) history = new JSONArray();
                JSONObject activity = new JSONObject();
                put(activity, "note", note.getText().toString().trim());
                put(activity, "at", java.time.Instant.now().toString());
                put(activity, "authorId", user.optString("id"));
                history.put(activity);
                put(lead, "history", history);
                put(lead, "stage", "Contacted");
                store.saveRecord(businessId, "leads", lead);
                render();
            }).create();
        dialog.show();
        styleFormDialog(dialog);
    }

    private void convertLead(JSONObject lead) {
        JSONObject customer = new JSONObject();
        try {
            String id = "customer-" + java.util.UUID.randomUUID();
            customer.put("id", id);
            customer.put("name", lead.optString("name"));
            customer.put("phone", lead.optString("phone"));
            customer.put("email", lead.optString("email"));
            customer.put("address", lead.optString("address"));
            customer.put("customerCode", store.business(businessId).optString("invoicePrefix", "DB") +
                "-C" + (store.list(businessId, "customers").size() + 1));
            store.saveRecord(businessId, "customers", customer);
            put(lead, "customerId", id);
            put(lead, "stage", "Won");
            store.saveRecord(businessId, "leads", lead);
            render();
            toast("Lead converted to customer.");
        } catch (JSONException e) {
            toast("Could not convert this lead: " + e.getMessage());
        }
    }

    private void confirmDelete(String module, JSONObject record) {
        new AlertDialog.Builder(this).setTitle("Delete " + singular(module).toLowerCase(Locale.ROOT) + "?")
            .setMessage("This record will be removed from this device.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> {
                store.deleteRecord(businessId, module, record.optString("id"));
                render();
                toast("Record deleted.");
            }).show();
    }

    private void applyPurchaseStock(JSONObject purchase) {
        String productName = purchase.optString("productName");
        for (JSONObject product : store.list(businessId, "products")) {
            if (productName.equalsIgnoreCase(product.optString("name"))) {
                adjustStock(product.optString("id"), purchase.optDouble("quantity"), "purchase");
                break;
            }
        }
    }

    private void adjustStock(String productId, double amount, String reason) {
        JSONObject product = store.find(businessId, "products", productId);
        if (product == null) return;
        put(product, "stock", Math.max(0, product.optDouble("stock") + amount));
        store.saveRecord(businessId, "products", product);
        JSONObject movement = new JSONObject();
        put(movement, "id", java.util.UUID.randomUUID().toString());
        put(movement, "productId", productId);
        put(movement, "quantity", amount);
        put(movement, "reason", reason);
        put(movement, "date", java.time.LocalDate.now().toString());
        store.saveRecord(businessId, "stockMovements", movement);
    }

    private void writeBackup(Uri uri) {
        if (uri == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("Could not open backup file");
            output.write(store.backup(businessId).toString(2).getBytes(StandardCharsets.UTF_8));
            toast("Backup exported.");
        } catch (Exception e) {
            toast("Backup export failed: " + e.getMessage());
        }
    }

    private void readBackup(Uri uri) {
        if (uri == null) return;
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("Could not open selected backup");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            JSONObject backup = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            store.importBackup(backup);
            JSONObject state = store.getAppState();
            businessId = state.optString("activeBusinessId", businessId);
            user = findStaff(state.optString("currentUserId", user.optString("id")));
            if (user == null) user = findStaff("staff-admin");
            render();
            toast("Backup imported. Review the active business before continuing.");
        } catch (Exception e) {
            toast("Backup import failed: " + e.getMessage());
        }
    }

    private void openLegacyApp() {
        try {
            startActivity(new Intent(this, LegacyWebActivity.class));
        } catch (Exception e) {
            toast("Previous app is not available in this build.");
        }
    }

    private void logEvent(String action, String subject) {
        JSONObject event = new JSONObject();
        put(event, "id", java.util.UUID.randomUUID().toString());
        put(event, "action", action);
        put(event, "subject", subject);
        put(event, "actorId", user == null ? "" : user.optString("id"));
        put(event, "at", java.time.Instant.now().toString());
        store.saveRecord(businessId, "auditLog", event);
    }

    private String recordSummary(String module, JSONObject row) {
        if ("invoices".equals(module) || "estimates".equals(module))
            return row.optString("date") + " · " + row.optString("customerName") + " · " +
                money(row.optDouble("total")) + ("invoices".equals(module) ? " · " + row.optString("status", "unpaid") : "");
        if ("products".equals(module))
            return "Stock " + row.optString("stock", "0") + " " + row.optString("unit") + " · " + money(row.optDouble("price"));
        if ("customers".equals(module) || "leads".equals(module))
            return row.optString("phone") + (row.optString("stage").isEmpty() ? "" : " · " + row.optString("stage"));
        if ("purchases".equals(module))
            return row.optString("date") + " · " + row.optString("supplierName") + " · " + money(row.optDouble("total"));
        if ("expenses".equals(module))
            return row.optString("date") + " · " + row.optString("category") + " · " + money(row.optDouble("amount"));
        if ("plots".equals(module))
            return row.optString("status", "available") + " · " + row.optString("sizeSqft") + " sqft · " + money(row.optDouble("agreementValue"));
        if ("plotReceipts".equals(module))
            return row.optString("receiptDate") + " · " + money(row.optDouble("amount"));
        if ("staff".equals(module)) return row.optString("employeeCode") + " · " + row.optString("role");
        if ("moneyAccounts".equals(module)) return row.optString("type") + " · Opening " + money(row.optDouble("opening"));
        if ("accountEntries".equals(module)) return row.optString("date") + " · " + row.optString("type") + " · " + money(row.optDouble("amount"));
        return row.optString("date", row.optString("type", row.optString("status", "")));
    }

    private List<String> fieldsFor(String module) {
        switch (module) {
            case "products": return Arrays.asList("name", "category", "unit", "price", "stock", "lowStock", "tax", "hsn");
            case "customers": return Arrays.asList("name", "phone", "email", "gstin", "address");
            case "leads": return Arrays.asList("name", "phone", "email", "interest", "source", "stage", "followUpAt", "notes");
            case "purchases": return Arrays.asList("supplierName", "billNumber", "date", "productName", "quantity", "purchaseRate", "tax");
            case "expenses": return Arrays.asList("category", "amount", "paymentMethod", "payee", "date", "remarks");
            case "payroll": return Arrays.asList("staffName", "salaryMonth", "grossPay", "leaveDeduction", "otherAdjustment", "paidAt");
            case "incentives": return Arrays.asList("staffName", "source", "basisAmount", "ratePercent", "amount", "approvalStatus");
            case "plots": return Arrays.asList("plotNumber", "sizeSqft", "customerName", "customerPhone", "status", "agreementValue", "registrationReference", "notes");
            case "staff": return Arrays.asList("employeeCode", "name", "role", "salary", "pin", "inboundRate", "outboundRate");
            case "moneyAccounts": return Arrays.asList("name", "type", "opening", "detail");
            case "accountEntries": return Arrays.asList("date", "type", "amount", "paymentMethod", "reference", "notes");
            default: return Collections.emptyList();
        }
    }

    private void putField(JSONObject object, String key, String value) throws JSONException {
        if (Arrays.asList("price", "stock", "lowStock", "tax", "quantity", "purchaseRate", "amount",
            "grossPay", "leaveDeduction", "otherAdjustment", "basisAmount", "ratePercent", "salary",
            "inboundRate", "outboundRate", "sizeSqft", "agreementValue", "opening").contains(key)) {
            object.put(key, number(value, 0));
        } else {
            object.put(key, value);
        }
    }

    private List<JSONObject> customerOptionsRecords() {
        List<JSONObject> customers = store.list(businessId, "customers");
        if (customers.isEmpty()) {
            JSONObject walkIn = new JSONObject();
            put(walkIn, "id", "walk-in");
            put(walkIn, "name", "Walk-in Customer");
            customers.add(walkIn);
        }
        return customers;
    }

    private List<String> customerOptions() {
        ArrayList<String> values = new ArrayList<>();
        for (JSONObject customer : customerOptionsRecords()) values.add(customer.optString("name"));
        return values;
    }

    private String moduleForPage(String value) {
        switch (value) {
            case "invoices": return "invoices";
            case "estimates": return "estimates";
            case "plots": return "plots";
            case "products": return "products";
            case "customers": return "customers";
            case "purchases": return "purchases";
            case "expenses": return "expenses";
            case "payroll": return "payroll";
            case "incentives": return "incentives";
            case "staff": return "staff";
            case "leads": return "leads";
            default: return null;
        }
    }

    private String moduleSubtitle(String module) {
        switch (module) {
            case "invoices": return "Create, review, print and track invoice payments.";
            case "estimates": return "Prepare quotations and convert an approved estimate to an invoice.";
            case "products": return "Catalog, prices, stock on hand and low-stock levels.";
            case "customers": return "Customer contact and billing records.";
            case "leads": return "Track prospects, follow-ups and customer conversions.";
            case "purchases": return "Supplier purchases and incoming stock.";
            case "expenses": return "Business expenses by date and payment method.";
            case "plots": return "Vikshha plot availability, allotment and agreement values.";
            case "payroll": return "Staff salary and monthly pay records.";
            case "incentives": return "Sales incentives and approval tracking.";
            case "staff": return "Staff accounts and local role previews.";
            default: return "";
        }
    }

    private boolean can(String destination) {
        if (user == null || !userBusinessIds().contains(businessId)) return false;
        boolean admin = isAdmin();
        if ("plots".equals(destination) && !"darvi-agri".equals(businessId)) return false;
        if ("darvi-agri".equals(businessId) && (!admin ||
            !Arrays.asList("dashboard", "leads", "plots", "customers", "settings", "reports",
                "accounts", "staff", "total-sales", "legacy", "payroll", "expenses").contains(destination))) return false;
        return admin || !Arrays.asList("plots", "purchases", "new-purchase", "expenses", "accounts",
            "payroll", "staff", "reports", "total-sales", "settings", "legacy").contains(destination);
    }

    private boolean isAdmin() {
        String role = user == null ? "" : user.optString("role");
        return "admin".equals(role) || "superadmin".equals(role);
    }

    private List<String> userBusinessIds() {
        if (isAdmin()) return NativeStore.BUSINESS_IDS;
        ArrayList<String> ids = new ArrayList<>();
        JSONArray values = user.optJSONArray("businessIds");
        if (values != null) for (int i = 0; i < values.length(); i++) {
            String id = values.optString(i);
            if (!"darvi-agri".equals(id)) ids.add(id);
        }
        return ids;
    }

    private JSONObject findStaff(String id) {
        if (id == null || id.isEmpty()) return null;
        for (String business : NativeStore.BUSINESS_IDS) {
            for (JSONObject person : store.list(business, "staff")) {
                if (id.equals(person.optString("id")) && person.optBoolean("enabled", true)) return person;
            }
        }
        return null;
    }

    private void metric(LinearLayout row, String label, Object value) {
        LinearLayout box = card();
        box.setBackground(roundedDrawable(SURFACE_TINT, 18, Color.TRANSPARENT));
        addText(box, String.valueOf(value), 18, GREEN, true);
        addText(box, label, 11, MUTED, false);
        row.addView(box, new LinearLayout.LayoutParams(0, dp(80), 1));
    }

    private void addHero(String heading, String subtitle) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        hero.setBackground(roundedGradient(GREEN, GREEN_DARK, 22));
        hero.setElevation(dp(2));
        TextView title = text(heading, 21, Color.WHITE, true);
        title.setPadding(0, 0, 0, dp(5));
        hero.addView(title, matchWidth());
        TextView detail = text(subtitle, 13, Color.rgb(223, 236, 225), false);
        detail.setLineSpacing(dp(2), 1f);
        hero.addView(detail, matchWidth());
        View accent = new View(this);
        accent.setBackground(roundedDrawable(ACCENT, 3, Color.TRANSPARENT));
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(42), dp(4));
        accentParams.topMargin = dp(13);
        hero.addView(accent, accentParams);
        LinearLayout.LayoutParams params = matchWidth();
        params.bottomMargin = dp(15);
        content.addView(hero, params);
    }

    private void quickAction(LinearLayout row, String label, String destination) {
        Button action = button(label, false);
        row.addView(action, new LinearLayout.LayoutParams(0, dp(46), 1));
        action.setOnClickListener(v -> openPage(destination));
    }

    private void addCard(String heading, String body) {
        LinearLayout box = card();
        addText(box, heading, 15, INK, true);
        addText(box, body, 13, MUTED, false);
        content.addView(box, matchWidth());
    }

    private void addSmallFooter() {
        addSpace(content, 16);
        addText(content, "Darvi Billing · Native Android · Stored on this device", 11, MUTED, false);
    }

    private EditText addFormField(LinearLayout form, String label, int type) {
        form.addView(fieldLabel(label), matchWidth());
        EditText field = input("", type);
        field.setContentDescription(label);
        form.addView(field, matchWidth());
        return field;
    }

    private LinearLayout inputCell(String label, EditText field) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(3), 0, dp(3), 0);
        cell.addView(fieldLabel(label), matchWidth());
        cell.addView(field, matchWidth());
        return cell;
    }

    private void styleFormDialog(AlertDialog dialog) {
        if (dialog.getWindow() == null) return;
        dialog.getWindow().setBackgroundDrawable(roundedDrawable(Color.WHITE, 22, BORDER));
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(560));
        int availableHeight = Math.max(dp(280),
            getResources().getDisplayMetrics().heightPixels - dp(160));
        int height = Math.min(dp(760), availableHeight);
        dialog.getWindow().setLayout(width, height);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (positive != null) {
            positive.setTextColor(GREEN);
            positive.setAllCaps(false);
        }
        if (negative != null) {
            negative.setTextColor(MUTED);
            negative.setAllCaps(false);
        }
    }

    private LinearLayout formColumn() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), dp(8));
        return form;
    }

    private void addFormSection(LinearLayout form, String label) {
        TextView heading = text(label, 12, GREEN, true);
        heading.setLetterSpacing(0.06f);
        heading.setPadding(dp(2), dp(12), dp(2), dp(5));
        form.addView(heading, matchWidth());
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(roundedDrawable(Color.WHITE, 18, BORDER));
        box.setElevation(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(9);
        box.setLayoutParams(params);
        return box;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(primary ? Color.WHITE : GREEN);
        button.setBackground(primary
            ? roundedGradient(GREEN, GREEN_DARK, 14)
            : roundedDrawable(Color.WHITE, 14, BORDER));
        button.setPadding(dp(12), dp(4), dp(12), dp(4));
        button.setMinHeight(dp(46));
        button.setElevation(primary ? dp(2) : 0);
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private TextView fieldLabel(String value) {
        TextView label = text(value, 13, MUTED, true);
        label.setPadding(dp(2), dp(6), dp(2), dp(2));
        return label;
    }

    private void addText(LinearLayout target, String value, int size, int color, boolean bold) {
        TextView element = text(value, size, color, bold);
        element.setPadding(0, dp(3), 0, dp(4));
        target.addView(element, matchWidth());
    }

    private EditText input(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        field.setInputType(inputType);
        field.setHint(hint);
        field.setTextSize(15);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        boolean multiline = (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(roundedDrawable(Color.rgb(248, 250, 248), 12, BORDER));
        if (multiline) field.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(multiline ? 96 : 52));
        params.bottomMargin = dp(6);
        field.setLayoutParams(params);
        return field;
    }

    private Spinner spinner(List<String> labels) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setBackground(roundedDrawable(Color.WHITE, 12, BORDER));
        spinner.setPadding(dp(8), 0, dp(8), 0);
        return spinner;
    }

    private GradientDrawable roundedDrawable(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private GradientDrawable roundedGradient(int start, int end, int radius) {
        GradientDrawable drawable = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {start, end});
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void addSpace(LinearLayout target, int height) {
        target.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static JSONObject optionAt(List<JSONObject> options, int index) {
        if (options == null || options.isEmpty()) return new JSONObject();
        return options.get(Math.max(0, Math.min(index, options.size() - 1)));
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Could not save field " + key, e);
        }
    }

    private static double number(String value, double fallback) {
        try {
            double result = Double.parseDouble(value.trim());
            return Double.isFinite(result) ? result : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String money(double value) {
        NumberFormat format = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(value);
    }

    private static String shortName(String value) {
        return value.length() > 14 ? value.substring(0, 12) + "…" : value;
    }

    private static String title(String value) {
        for (int i = 0; i < NAV_PAGES.length; i++) if (NAV_PAGES[i].equals(value)) return NAV_LABELS[i];
        return "Darvi Billing";
    }

    private static String singular(String module) {
        switch (module) {
            case "products": return "product";
            case "customers": return "customer";
            case "invoices": return "invoice";
            case "estimates": return "quotation";
            case "leads": return "lead";
            case "purchases": return "purchase";
            case "expenses": return "expense";
            case "moneyAccounts": return "account";
            case "accountEntries": return "transaction";
            case "payroll": return "salary";
            case "incentives": return "incentive";
            case "plots": return "plot";
            case "staff": return "staff account";
            default: return module;
        }
    }

    private static String plural(String module) {
        switch (module) {
            case "moneyAccounts": return "Accounts";
            case "accountEntries": return "Transactions";
            case "payroll": return "Staff salary";
            case "plots": return "Plots";
            case "staff": return "Staff accounts";
            default: return module.substring(0, 1).toUpperCase(Locale.ROOT) + module.substring(1);
        }
    }

    private static String recordTitle(String module, JSONObject record) {
        String value = record.optString("name");
        if (value.isEmpty()) value = record.optString("number");
        if (value.isEmpty()) value = record.optString("plotNumber");
        return value.isEmpty() ? singular(module) : value;
    }

    private static String labelFor(String key) {
        String label = key.replaceAll("([a-z])([A-Z])", "$1 $2")
            .replace("gstin", "GSTIN").replace("hsn", "HSN").replace("pin", "PIN");
        return label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1);
    }

    private static int inputTypeFor(String key) {
        if (Arrays.asList("price", "stock", "lowStock", "tax", "quantity", "purchaseRate", "amount",
            "grossPay", "leaveDeduction", "otherAdjustment", "basisAmount", "ratePercent", "salary",
            "inboundRate", "outboundRate", "sizeSqft", "agreementValue", "opening").contains(key))
            return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        if ("phone".equals(key) || "customerPhone".equals(key)) return InputType.TYPE_CLASS_PHONE;
        if ("email".equals(key)) return InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
        return "notes".equals(key) || "address".equals(key) || "remarks".equals(key)
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_CLASS_TEXT;
    }

    private static boolean required(String key) {
        return Arrays.asList("name", "plotNumber", "category", "amount", "phone", "employeeCode").contains(key);
    }

    private static String greeting() {
        int hour = java.time.LocalTime.now().getHour();
        return hour < 12 ? "morning" : hour < 17 ? "afternoon" : "evening";
    }

    private String deviceName(BluetoothDevice device) {
        try {
            return device.getName() == null ? "printer" : device.getName();
        } catch (SecurityException e) {
            return "printer";
        }
    }

    private static final class InvoiceLineInput {
        final Spinner product;
        final EditText quantity;
        final EditText price;
        final EditText tax;
        final EditText discount;
        final List<JSONObject> products;

        InvoiceLineInput(Spinner product, EditText quantity, EditText price, EditText tax,
                        EditText discount, List<JSONObject> products) {
            this.product = product;
            this.quantity = quantity;
            this.price = price;
            this.tax = tax;
            this.discount = discount;
            this.products = products;
        }
    }
}
