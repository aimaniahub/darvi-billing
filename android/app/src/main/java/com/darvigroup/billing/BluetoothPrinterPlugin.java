package com.darvigroup.billing;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

@CapacitorPlugin(
    name = "BluetoothPrinter",
    permissions = {
        @Permission(
            strings = {
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            },
            alias = "bluetoothLegacy"
        ),
        @Permission(
            strings = {
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN"
            },
            alias = "bluetoothModern"
        )
    }
)
public class BluetoothPrinterPlugin extends Plugin {
    private static final String TAG = "DarviBluetoothPrinter";

    // Standard SPP (Serial Port Profile) UUID for Bluetooth Classic thermal printers
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Connection states
    public static final String STATE_BLUETOOTH_UNAVAILABLE = "BluetoothUnavailable";
    public static final String STATE_PERMISSION_REQUIRED = "PermissionRequired";
    public static final String STATE_BLUETOOTH_DISABLED = "BluetoothDisabled";
    public static final String STATE_SEARCHING = "Searching";
    public static final String STATE_PRINTER_NOT_PAIRED = "PrinterNotPaired";
    public static final String STATE_PRINTER_FOUND = "PrinterFound";
    public static final String STATE_CONNECTING = "Connecting";
    public static final String STATE_CONNECTED = "Connected";
    public static final String STATE_PRINTING = "Printing";
    public static final String STATE_DISCONNECTED = "Disconnected";
    public static final String STATE_CONNECTION_FAILED = "ConnectionFailed";

    private BluetoothAdapter bluetoothAdapter = null;
    private BluetoothSocket bluetoothSocket = null;
    private OutputStream outputStream = null;
    private String currentState = STATE_DISCONNECTED;
    private String connectedDeviceName = null;
    private String connectedDeviceAddress = null;
    private String lastErrorMessage = null;

    @Override
    public void load() {
        super.load();
        try {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                currentState = STATE_BLUETOOTH_UNAVAILABLE;
            } else if (!bluetoothAdapter.isEnabled()) {
                currentState = STATE_BLUETOOTH_DISABLED;
            } else {
                currentState = STATE_DISCONNECTED;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing BluetoothAdapter", e);
            currentState = STATE_BLUETOOTH_UNAVAILABLE;
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+ (API 31+)
            return ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
        }
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        boolean isReallyConnected = isSocketConnected();
        if (isReallyConnected) {
            currentState = STATE_CONNECTED;
        } else if (STATE_CONNECTED.equals(currentState)) {
            currentState = STATE_DISCONNECTED;
        }

        JSObject ret = new JSObject();
        ret.put("state", currentState);
        ret.put("isConnected", isReallyConnected);
        ret.put("deviceName", connectedDeviceName);
        ret.put("deviceAddress", connectedDeviceAddress);
        ret.put("error", lastErrorMessage);
        call.resolve(ret);
    }

    @PluginMethod
    public void isBluetoothEnabled(PluginCall call) {
        boolean enabled = bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        JSObject ret = new JSObject();
        ret.put("enabled", enabled);
        ret.put("hasPermissions", hasBluetoothPermissions());
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        if (!hasBluetoothPermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissionForAlias("bluetoothModern", call, "permissionCallback");
            } else {
                requestPermissionForAlias("bluetoothLegacy", call, "permissionCallback");
            }
        } else {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
        }
    }

    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", hasBluetoothPermissions());
        call.resolve(ret);
    }

    @PluginMethod
    public void listPairedDevices(PluginCall call) {
        if (!hasBluetoothPermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissionForAlias("bluetoothModern", call, "listDevicesPermissionCallback");
            } else {
                requestPermissionForAlias("bluetoothLegacy", call, "listDevicesPermissionCallback");
            }
            return;
        }
        performListPairedDevices(call);
    }

    @PermissionCallback
    private void listDevicesPermissionCallback(PluginCall call) {
        if (hasBluetoothPermissions()) {
            performListPairedDevices(call);
        } else {
            call.reject("Bluetooth permissions not granted", "PERMISSION_DENIED");
        }
    }

    private void performListPairedDevices(PluginCall call) {
        if (bluetoothAdapter == null) {
            call.reject("Bluetooth hardware not available on this device", "UNAVAILABLE");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            call.reject("Bluetooth is currently disabled. Please enable Bluetooth in settings.", "DISABLED");
            return;
        }

        try {
            Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
            JSArray devices = new JSArray();
            if (paired != null) {
                for (BluetoothDevice d : paired) {
                    JSObject dev = new JSObject();
                    dev.put("name", d.getName() != null ? d.getName() : "Unknown");
                    dev.put("address", d.getAddress());
                    dev.put("isSeznik", isSeznikDevice(d.getName()));
                    devices.put(dev);
                }
            }
            JSObject ret = new JSObject();
            ret.put("devices", devices);
            call.resolve(ret);
        } catch (SecurityException se) {
            call.reject("Security exception listing paired devices: " + se.getMessage(), "SECURITY_ERROR");
        } catch (Exception e) {
            call.reject("Error listing paired devices: " + e.getMessage(), "ERROR");
        }
    }

    private boolean isSeznikDevice(String name) {
        if (name == null) return false;
        String upper = name.toUpperCase();
        return upper.contains("SSEZNIK") || upper.contains("SEZNIK") || upper.contains("2POS") || upper.contains("B5A5")
                || upper.contains("PRINTER") || upper.contains("THERMAL") || upper.contains("RPP") || upper.contains("MTP");
    }

    @PluginMethod
    public void connect(PluginCall call) {
        if (!hasBluetoothPermissions()) {
            currentState = STATE_PERMISSION_REQUIRED;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissionForAlias("bluetoothModern", call, "connectPermissionCallback");
            } else {
                requestPermissionForAlias("bluetoothLegacy", call, "connectPermissionCallback");
            }
            return;
        }
        performConnect(call);
    }

    @PermissionCallback
    private void connectPermissionCallback(PluginCall call) {
        if (hasBluetoothPermissions()) {
            performConnect(call);
        } else {
            currentState = STATE_PERMISSION_REQUIRED;
            call.reject("Bluetooth permission required. Please allow nearby devices access.", "PERMISSION_DENIED");
        }
    }

    private void performConnect(PluginCall call) {
        if (bluetoothAdapter == null) {
            currentState = STATE_BLUETOOTH_UNAVAILABLE;
            call.reject("Bluetooth hardware not available", "UNAVAILABLE");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            currentState = STATE_BLUETOOTH_DISABLED;
            call.reject("Bluetooth is disabled. Please turn Bluetooth on.", "DISABLED");
            return;
        }

        String targetAddress = call.getString("address");
        String targetName = call.getString("name");

        closeSocketQuietly();

        currentState = STATE_SEARCHING;
        BluetoothDevice targetDevice = null;

        try {
            Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
            if (paired != null && !paired.isEmpty()) {
                // 1. Match by explicit address if provided
                if (targetAddress != null && !targetAddress.isEmpty()) {
                    for (BluetoothDevice d : paired) {
                        if (targetAddress.equalsIgnoreCase(d.getAddress())) {
                            targetDevice = d;
                            break;
                        }
                    }
                }
                // 2. Match by explicit name if provided
                if (targetDevice == null && targetName != null && !targetName.isEmpty()) {
                    for (BluetoothDevice d : paired) {
                        if (targetName.equalsIgnoreCase(d.getName())) {
                            targetDevice = d;
                            break;
                        }
                    }
                }
                // 3. Match Seznik or thermal printer patterns
                if (targetDevice == null) {
                    for (BluetoothDevice d : paired) {
                        if (isSeznikDevice(d.getName())) {
                            targetDevice = d;
                            break;
                        }
                    }
                }
            }

            if (targetDevice == null) {
                currentState = STATE_PRINTER_NOT_PAIRED;
                lastErrorMessage = "Seznik printer not found. Pair it in Android Settings and select it from the paired-device list.";
                call.reject(lastErrorMessage, "NOT_PAIRED");
                return;
            }

            currentState = STATE_PRINTER_FOUND;
            final BluetoothDevice dev = targetDevice;
            final String devName = dev.getName();
            final String devAddr = dev.getAddress();

            // Run connection on background thread to prevent UI freezing
            getBridge().execute(() -> {
                try {
                    currentState = STATE_CONNECTING;
                    // Cancel discovery before connecting as it slows down RFCOMM
                    if (bluetoothAdapter.isDiscovering()) {
                        bluetoothAdapter.cancelDiscovery();
                    }

                    BluetoothSocket socket = null;
                    // Attempt 1: Standard SPP UUID
                    try {
                        socket = dev.createRfcommSocketToServiceRecord(SPP_UUID);
                        socket.connect();
                    } catch (IOException e1) {
                        Log.w(TAG, "Standard SPP connect failed, attempting fallback reflection socket", e1);
                        try {
                            if (socket != null) socket.close();
                        } catch (Exception ignored) {}

                        // Attempt 2: Fallback reflection on channel 1 (common for older thermal printer firmware)
                        Method m = dev.getClass().getMethod("createRfcommSocket", new Class[]{int.class});
                        socket = (BluetoothSocket) m.invoke(dev, 1);
                        if (socket != null) {
                            socket.connect();
                        }
                    }

                    if (socket != null && socket.isConnected()) {
                        bluetoothSocket = socket;
                        outputStream = socket.getOutputStream();
                        currentState = STATE_CONNECTED;
                        connectedDeviceName = devName;
                        connectedDeviceAddress = devAddr;
                        lastErrorMessage = null;

                        JSObject ret = new JSObject();
                        ret.put("ok", true);
                        ret.put("state", STATE_CONNECTED);
                        ret.put("deviceName", devName);
                        ret.put("deviceAddress", devAddr);
                        call.resolve(ret);
                    } else {
                        throw new IOException("Socket connected but isConnected() returned false");
                    }
                } catch (Exception connectEx) {
                    Log.e(TAG, "Bluetooth connect failed to " + devName, connectEx);
                    closeSocketQuietly();
                    currentState = STATE_CONNECTION_FAILED;
                    lastErrorMessage = "Could not connect to " + devName + ": " + connectEx.getMessage();
                    call.reject(lastErrorMessage, "CONNECT_FAILED");
                }
            });

        } catch (SecurityException se) {
            currentState = STATE_PERMISSION_REQUIRED;
            call.reject("Security exception: " + se.getMessage(), "PERMISSION_DENIED");
        } catch (Exception e) {
            currentState = STATE_CONNECTION_FAILED;
            call.reject("Connect error: " + e.getMessage(), "ERROR");
        }
    }

    private boolean isSocketConnected() {
        return bluetoothSocket != null && bluetoothSocket.isConnected() && outputStream != null;
    }

    private void closeSocketQuietly() {
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (Exception ignored) {}
        try {
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }
        } catch (Exception ignored) {}
        bluetoothSocket = null;
        outputStream = null;
        connectedDeviceName = null;
        connectedDeviceAddress = null;
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        closeSocketQuietly();
        currentState = STATE_DISCONNECTED;
        JSObject ret = new JSObject();
        ret.put("ok", true);
        ret.put("state", STATE_DISCONNECTED);
        call.resolve(ret);
    }

    @PluginMethod
    public void printBytes(PluginCall call) {
        if (!isSocketConnected()) {
            currentState = STATE_DISCONNECTED;
            call.reject("Printer is not connected. Tap Connect first.", "NOT_CONNECTED");
            return;
        }

        byte[] bytesToSend = null;

        // Support direct byte array (as JSON array of integers) or Base64 string
        if (call.hasOption("bytes")) {
            try {
                JSONArray arr = call.getArray("bytes");
                if (arr != null) {
                    bytesToSend = new byte[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        bytesToSend[i] = (byte) arr.getInt(i);
                    }
                }
            } catch (JSONException e) {
                Log.w(TAG, "Failed to parse bytes array", e);
            }
        } else if (call.hasOption("base64")) {
            String b64 = call.getString("base64");
            if (b64 != null) {
                bytesToSend = Base64.decode(b64, Base64.DEFAULT);
            }
        }

        if (bytesToSend == null || bytesToSend.length == 0) {
            call.reject("No print data provided (expected 'bytes' integer array or 'base64' string)", "INVALID_ARGUMENT");
            return;
        }

        final byte[] payload = bytesToSend;
        writePayload(call, payload, false);
    }

    @PluginMethod
    public void printReceipt(PluginCall call) {
        if (!isSocketConnected()) {
            currentState = STATE_DISCONNECTED;
            call.reject("Printer is not connected. Connect to the printer first.", "NOT_CONNECTED");
            return;
        }
        JSObject document = call.getObject("document");
        if (document == null) {
            call.reject("No receipt document was provided.", "INVALID_ARGUMENT");
            return;
        }
        JSONArray lines = document.optJSONArray("lines");
        if (lines == null || lines.length() == 0) {
            call.reject("The receipt has no items.", "INVALID_ARGUMENT");
            return;
        }

        final byte[] payload = NativeReceiptFormatter.format(document, call.getString("docType", "invoice"));
        writePayload(call, payload, false);
    }

    @PluginMethod
    public void testPrint(PluginCall call) {
        if (!isSocketConnected()) {
            currentState = STATE_DISCONNECTED;
            call.reject("Printer is not connected. Connect to the printer first.", "NOT_CONNECTED");
            return;
        }
        writePayload(call, NativeReceiptFormatter.diagnostic(), true);
    }

    private void writePayload(PluginCall call, byte[] payload, boolean diagnostic) {
        getBridge().execute(() -> {
            long startTime = System.currentTimeMillis();
            currentState = STATE_PRINTING;
            try {
                // Cancel discovery to maximize Bluetooth SPP throughput and avoid packet loss
                if (bluetoothAdapter != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                                bluetoothAdapter.cancelDiscovery();
                            }
                        } else {
                            bluetoothAdapter.cancelDiscovery();
                        }
                    } catch (Throwable ignored) {}
                }

                synchronized (this) {
                    if (outputStream == null || bluetoothSocket == null || !bluetoothSocket.isConnected()) {
                        throw new IOException("Bluetooth printer socket is no longer connected");
                    }
                    final int chunkSize = 64;
                    final int chunkDelayMs = 30;
                    for (int offset = 0; offset < payload.length; offset += chunkSize) {
                        int length = Math.min(chunkSize, payload.length - offset);
                        outputStream.write(payload, offset, length);
                        if (offset + length < payload.length) {
                            try {
                                Thread.sleep(chunkDelayMs);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("Bluetooth print was interrupted before the full receipt was sent", interrupted);
                            }
                        }
                    }
                    outputStream.flush();
                }
                long duration = System.currentTimeMillis() - startTime;
                currentState = STATE_CONNECTED;
                Log.i(TAG, "Wrote " + payload.length + " ESC/POS bytes to Bluetooth socket in " + duration + "ms");

                JSObject ret = new JSObject();
                ret.put("ok", true);
                ret.put("bytesWritten", payload.length);
                ret.put("deviceName", connectedDeviceName);
                ret.put("durationMs", duration);
                ret.put("diagnostic", diagnostic);
                call.resolve(ret);
            } catch (IOException ioEx) {
                Log.e(TAG, "Failed writing to printer socket", ioEx);
                closeSocketQuietly();
                currentState = STATE_DISCONNECTED;
                lastErrorMessage = "Connection lost during print: " + ioEx.getMessage();
                call.reject(lastErrorMessage, "WRITE_FAILED");
            }
        });
    }

    @PluginMethod
    public void feed(PluginCall call) {
        int lines = call.getInt("lines", 4);
        if (!isSocketConnected()) {
            call.reject("Printer not connected", "NOT_CONNECTED");
            return;
        }
        // Send CR LF newlines and ESC d lines
        byte[] feedCmd = new byte[] { '\r', '\n', '\r', '\n', 0x1B, 0x64, (byte) Math.max(1, Math.min(lines, 10)) };
        try {
            synchronized (this) {
                if (outputStream == null) {
                    throw new IOException("Output stream closed");
                }
                outputStream.write(feedCmd);
                outputStream.flush();
            }
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (IOException e) {
            closeSocketQuietly();
            currentState = STATE_DISCONNECTED;
            call.reject("Feed failed: " + e.getMessage(), "FEED_FAILED");
        }
    }
}
