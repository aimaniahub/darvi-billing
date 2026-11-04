package com.darvigroup.billing;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class NativeStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "darvi_native.db";
    private static final int DATABASE_VERSION = 1;
    static final List<String> BUSINESS_IDS = Arrays.asList("darvi-group", "darvi-genetics", "darvi-agri");
    static final String[] MODULES = {
        "products", "customers", "invoices", "estimates", "plots", "plotReceipts", "leads", "staff",
        "purchases", "expenses", "payroll", "incentives", "moneyAccounts",
        "moneyAdjustments", "suppliers", "accountEntries", "stockMovements", "auditLog"
    };

    NativeStore(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records (business_id TEXT NOT NULL, module TEXT NOT NULL, record_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY (business_id, module, record_id))");
        db.execSQL("CREATE TABLE app_meta (meta_key TEXT PRIMARY KEY, payload TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported native data migration from version " + oldVersion);
    }

    void initialize() {
        JSONObject initialized = getMeta("initialized");
        if (initialized != null && initialized.optBoolean("value")) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONObject group = business("Darvi Group", "DG", "9986890777", "29BKZPA0053M1ZQ");
            group.put("address", "#2 totad building, Arjun Vihar, Gokul road Hubli, Karnataka 580030, India HUBLI");
            saveBusiness("darvi-group", group);
            saveBusiness("darvi-genetics", business("Darvi Genetics", "DGE", "", ""));
            saveBusiness("darvi-agri", business("Darvi Agri Developer LLP", "DAD", "", ""));

            addProduct("darvi-group", "Coconut Hybrid", "Coconut", "sapling", 375, 500, 20);
            addProduct("darvi-group", "Sandalwood", "Forestry", "no", 35, 200, 15);
            addProduct("darvi-group", "Redsandal", "Forestry", "piece", 50, 150, 15);
            addProduct("darvi-group", "Red Diamond Guava", "Guava", "piece", 80, 250, 25);
            addProduct("darvi-group", "Gangabondam Cross Coconut", "Coconut", "sapling", 450, 80, 10);
            addProduct("darvi-group", "Reed Avocado", "Avocado", "sapling", 250, 35, 8);
            addProduct("darvi-group", "Pinkerton Avocado", "Avocado", "sapling", 550, 21, 6);
            addProduct("darvi-group", "Mexican Hass Avocado", "Avocado", "sapling", 700, 14, 6);
            addProduct("darvi-group", "Coco Booster 50 kg", "Fertilizer", "bag", 2500, 16, 5);
            addProduct("darvi-group", "Seaweed Extract 20 kg", "Fertilizer", "bag", 3000, 8, 4);
            addProduct("darvi-genetics", "Gir Cow", "Cows", "cow", 0, 0, 0);
            addProduct("darvi-genetics", "Gir Semen Straw", "Semen Bank", "straw", 0, 0, 0);
            addProduct("darvi-genetics", "Gir Ghee", "Ghee", "kg", 0, 0, 0);

            JSONObject walkIn = record("walk-in", "Walk-in Customer");
            walkIn.put("phone", "");
            saveRecord("darvi-group", "customers", walkIn);
            JSONObject customer = record("cust-harshavardhan", "Harshavardhan");
            customer.put("phone", "9986890777");
            customer.put("address", "Hubli, Karnataka");
            customer.put("customerCode", "DG-C00487");
            saveRecord("darvi-group", "customers", customer);

            addStaff("staff-admin", "DG-000", "Darvi Administrator", "superadmin", 0);
            addStaff("staff-shrishall", "DG-001", "Shrishall Bhadake", "manager", 0);
            addStaff("staff-sandesh", "DG-002", "Sandesh Ullagaddimath", "manager", 0);
            addStaff("staff-anurag", "DG-003", "Anurag Tengli", "sales", 0);
            addStaff("staff-pramod", "DG-004", "Pramod Attigeri", "sales", 0);

            String[][] accounts = {
                {"cash", "Cash in hand", "cash", "Main cash drawer"},
                {"bank", "Bank account", "bank", "Add bank reference in account settings"},
                {"upi", "UPI collections", "upi", "UPI / QR payments"},
                {"card", "Card settlements", "card", "Card machine receipts"},
                {"other", "Other / unallocated", "other", "Requires review"}
            };
            for (String[] row : accounts) {
                JSONObject account = record(row[0], row[1]);
                account.put("type", row[2]);
                account.put("detail", row[3]);
                account.put("opening", 0);
                account.put("enabled", true);
                saveRecord("darvi-group", "moneyAccounts", account);
            }
            JSONObject meta = new JSONObject();
            meta.put("activeBusinessId", "darvi-group");
            meta.put("currentUserId", "");
            putMeta("app", meta);
            JSONObject marker = new JSONObject();
            marker.put("value", true);
            putMeta("initialized", marker);
            db.setTransactionSuccessful();
        } catch (JSONException e) {
            throw new IllegalStateException("Could not initialize native billing data", e);
        } finally {
            db.endTransaction();
        }
    }

    JSONObject business(String id) {
        JSONObject value = getMeta("business:" + id);
        if (value != null) return value;
        JSONObject fallback = new JSONObject();
        try {
            fallback.put("name", id);
            fallback.put("invoicePrefix", "DB");
            fallback.put("address", "");
            fallback.put("phone", "");
            fallback.put("gstin", "");
            fallback.put("state", "Karnataka");
        } catch (JSONException ignored) { }
        return fallback;
    }

    void saveBusiness(String id, JSONObject value) {
        putMeta("business:" + id, value);
    }

    JSONObject getMeta(String key) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
            "SELECT payload FROM app_meta WHERE meta_key=?", new String[]{key})) {
            return cursor.moveToFirst() ? new JSONObject(cursor.getString(0)) : null;
        } catch (JSONException e) {
            throw new IllegalStateException("Stored app settings are invalid", e);
        }
    }

    void putMeta(String key, JSONObject value) {
        ContentValues values = new ContentValues();
        values.put("meta_key", key);
        values.put("payload", value.toString());
        getWritableDatabase().insertWithOnConflict("app_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    JSONObject getAppState() {
        JSONObject state = getMeta("app");
        if (state == null) state = new JSONObject();
        return state;
    }

    List<JSONObject> list(String businessId, String module) {
        ArrayList<JSONObject> results = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
            "SELECT payload FROM records WHERE business_id=? AND module=? ORDER BY rowid DESC",
            new String[]{businessId, module})) {
            while (cursor.moveToNext()) {
                try {
                    results.add(new JSONObject(cursor.getString(0)));
                } catch (JSONException e) {
                    throw new IllegalStateException("A saved " + module + " record is invalid", e);
                }
            }
        }
        return results;
    }

    JSONObject find(String businessId, String module, String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
            "SELECT payload FROM records WHERE business_id=? AND module=? AND record_id=?",
            new String[]{businessId, module, id})) {
            if (!cursor.moveToFirst()) return null;
            return new JSONObject(cursor.getString(0));
        } catch (JSONException e) {
            throw new IllegalStateException("A saved " + module + " record is invalid", e);
        }
    }

    void saveRecord(String businessId, String module, JSONObject record) {
        if (!Arrays.asList(MODULES).contains(module)) {
            throw new IllegalArgumentException("Unsupported record type: " + module);
        }
        String id = record.optString("id");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            try {
                record.put("id", id);
            } catch (JSONException e) {
                throw new IllegalStateException("Could not assign record ID", e);
            }
        }
        try {
            record.put("businessId", businessId);
        } catch (JSONException e) {
            throw new IllegalStateException("Could not assign business ID", e);
        }
        ContentValues values = new ContentValues();
        values.put("business_id", businessId);
        values.put("module", module);
        values.put("record_id", id);
        values.put("payload", record.toString());
        getWritableDatabase().insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void deleteRecord(String businessId, String module, String id) {
        getWritableDatabase().delete("records",
            "business_id=? AND module=? AND record_id=?", new String[]{businessId, module, id});
    }

    private void clearModule(String businessId, String module) {
        getWritableDatabase().delete("records", "business_id=? AND module=?",
            new String[]{businessId, module});
    }

    JSONObject backup(String activeBusinessId) throws JSONException {
        JSONObject root = new JSONObject();
        JSONObject businesses = new JSONObject();
        root.put("version", 2);
        root.put("activeBusinessId", activeBusinessId);
        root.put("businesses", businesses);
        root.put("staff", new JSONArray(list(activeBusinessId, "staff")));
        for (String id : BUSINESS_IDS) {
            JSONObject businessStore = new JSONObject();
            businessStore.put("business", business(id));
            for (String module : MODULES) {
                if ("auditLog".equals(module) || "stockMovements".equals(module) || "moneyAdjustments".equals(module)) {
                    businessStore.put(module, new JSONArray(list(id, module)));
                } else {
                    businessStore.put(module, new JSONArray(list(id, module)));
                }
            }
            businesses.put(id, businessStore);
        }
        businesssStaff(root);
        return root;
    }

    private void businesssStaff(JSONObject root) throws JSONException {
        JSONArray staff = new JSONArray();
        for (String id : BUSINESS_IDS) {
            for (JSONObject person : list(id, "staff")) {
                boolean exists = false;
                for (int i = 0; i < staff.length(); i++) {
                    if (person.optString("id").equals(staff.getJSONObject(i).optString("id"))) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) staff.put(person);
            }
        }
        root.put("staff", staff);
    }

    void importBackup(JSONObject root) throws JSONException {
        JSONObject businesses = root.optJSONObject("businesses");
        if (businesses == null) {
            businesses = new JSONObject();
            businesses.put("darvi-group", root);
        }
        JSONArray topLevelStaff = root.optJSONArray("staff");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String businessId : BUSINESS_IDS) {
                JSONObject source = businesses.optJSONObject(businessId);
                if (source == null) continue;
                JSONObject profile = source.optJSONObject("business");
                if (profile != null) saveBusiness(businessId, profile);
                for (String module : MODULES) {
                    JSONArray records = source.optJSONArray(module);
                    if (records == null) continue;
                    if ("staff".equals(module) && topLevelStaff != null) continue;
                    clearModule(businessId, module);
                    for (int i = 0; i < records.length(); i++) {
                        JSONObject record = records.optJSONObject(i);
                        if (record != null) saveRecord(businessId, module, record);
                    }
                }
            }
            if (topLevelStaff != null) {
                for (String businessId : BUSINESS_IDS) clearModule(businessId, "staff");
                for (int i = 0; i < topLevelStaff.length(); i++) {
                    JSONObject person = topLevelStaff.optJSONObject(i);
                    if (person == null) continue;
                    JSONArray businessesForStaff = person.optJSONArray("businessIds");
                    if (businessesForStaff == null || businessesForStaff.length() == 0) {
                        saveRecord("darvi-group", "staff", person);
                    } else {
                        for (int j = 0; j < businessesForStaff.length(); j++) {
                            saveRecord(businessesForStaff.optString(j), "staff", new JSONObject(person.toString()));
                        }
                    }
                }
            }
            String selected = root.optString("activeBusinessId", "darvi-group");
            if (!BUSINESS_IDS.contains(selected)) selected = "darvi-group";
            JSONObject app = getAppState();
            app.put("activeBusinessId", selected);
            putMeta("app", app);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static JSONObject business(String name, String prefix, String phone, String gstin) {
        JSONObject result = new JSONObject();
        try {
            result.put("name", name);
            result.put("invoicePrefix", prefix);
            result.put("address", "");
            result.put("phone", phone);
            result.put("gstin", gstin);
            result.put("state", "Karnataka");
        } catch (JSONException ignored) { }
        return result;
    }

    private void addProduct(String businessId, String name, String category, String unit, double price, double stock, double lowStock) throws JSONException {
        JSONObject product = record(UUID.randomUUID().toString(), name);
        product.put("category", category);
        product.put("type", "product");
        product.put("unit", unit);
        product.put("price", price);
        product.put("stock", stock);
        product.put("lowStock", lowStock);
        product.put("tax", 0);
        product.put("active", true);
        saveRecord(businessId, "products", product);
    }

    private void addStaff(String id, String code, String name, String role, double salary) throws JSONException {
        JSONObject person = record(id, name);
        person.put("employeeCode", code);
        person.put("role", role);
        person.put("primaryBusinessId", "darvi-group");
        person.put("businessIds", new JSONArray(Arrays.asList("darvi-group", "darvi-genetics")));
        person.put("salary", salary);
        person.put("inboundRate", 0);
        person.put("outboundRate", 0);
        person.put("pin", "1234");
        person.put("enabled", true);
        for (String businessId : Arrays.asList("darvi-group", "darvi-genetics")) {
            saveRecord(businessId, "staff", new JSONObject(person.toString()));
        }
    }

    private static JSONObject record(String id, String name) {
        JSONObject result = new JSONObject();
        try {
            result.put("id", id);
            result.put("name", name);
        } catch (JSONException ignored) { }
        return result;
    }
}
