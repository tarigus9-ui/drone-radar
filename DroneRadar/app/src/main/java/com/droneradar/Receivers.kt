package com.droneradar

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Woken by Android for BLE Remote ID hits, even when the app is fully closed. */
class BleHitReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Backend.init(ctx)
        Whitelist.load(ctx)
        val results: List<ScanResult> = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java).orEmpty()
        else @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT).orEmpty()
        results.forEach { Alerts.handleBle(ctx, it) }
        if (results.isNotEmpty() && Prefs.enabled(ctx)) ScanService.start(ctx) // try to resume full scanning
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Prefs.enabled(ctx)) return
        ScanService.registerPendingIntentScan(ctx)
        ScanService.start(ctx)
    }
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("radar", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = p(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("enabled", v).apply()
}
