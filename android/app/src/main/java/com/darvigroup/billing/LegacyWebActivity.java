package com.darvigroup.billing;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class LegacyWebActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BluetoothPrinterPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
