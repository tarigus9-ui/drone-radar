package com.droneradar

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

/**
 * Foreground service that keeps listening for Remote ID on:
 *  Bluetooth 4 legacy + Bluetooth 5 long range, Wi-Fi Beacon, Wi-Fi NaN.
 * START_STICKY + BootReceiver keep it alive after the UI is closed.
 */
@SuppressLint("MissingPermission")
class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var awareSession: WifiAwareSession? = null
    private val wifi by lazy { applicationContext.getSystemService(WifiManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Alerts.createChannels(this)
        Backend.init(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, Alerts.SERVICE_NOTIF_ID, serviceNotification(0), type)
        startBle()
        startWifiBeacon()
        startNan()
        scope.launch {
            DroneRepository.drones.collect { list -> updateServiceNotification(list.size) }
        }
        scope.launch { while (isActive) { delay(10_000); DroneRepository.prune() } }
        // Platform: guard location, upload queue, heartbeat
        Backend.startLocation(this)
        scope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                runCatching { Backend.flush() }
                if (tick % 12 == 0) runCatching { Backend.heartbeat(scanning = true) }
                tick++
                delay(5_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        Backend.stopLocation(this)
        runCatching { bleScanner()?.stopScan(bleCallback) }
        runCatching { unregisterReceiver(wifiReceiver) }
        awareSession?.close()
        super.onDestroy()
    }

    // ---------- Notifications ----------
    private fun serviceNotification(count: Int) =
        NotificationCompat.Builder(this, Alerts.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_drone)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text, count))
            .setOngoing(true)
            .setContentIntent(Alerts.openAppIntent(this))
            .build()

    private fun updateServiceNotification(count: Int) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(Alerts.SERVICE_NOTIF_ID, serviceNotification(count))
    }

    // ---------- Bluetooth ----------
    private fun bleScanner() =
        getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = Alerts.handleBle(this@ScanService, result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) =
            results.forEach { Alerts.handleBle(this@ScanService, it) }
        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "BLE scan failed $errorCode") }
    }

    private fun startBle() {
        val scanner = bleScanner() ?: return
        // Filter is required for scanning to continue while the screen is off.
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceData(Alerts.ODID_UUID, byteArrayOf(0x0D), byteArrayOf(0xFF.toByte()))
                .build()
        )
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                // Bluetooth 5 Long Range (Coded PHY) when the phone supports it
                if (adapter.isLeCodedPhySupported) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()
        runCatching { scanner.startScan(filters, settings, bleCallback) }
            .onFailure { Log.w(TAG, "BLE start", it) }

        // Backup: OS-delivered scan that survives the app process being killed.
        registerPendingIntentScan(this)
    }

    // ---------- Wi-Fi Beacon ----------
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = processWifiResults()
    }

    private fun startWifiBeacon() {
        if (Build.VERSION.SDK_INT < 30) return // ScanResult.informationElements needs Android 11
        registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        scope.launch {
            while (isActive) {
                // Android throttles to ~4 scans / 2 min; disable "Wi-Fi scan throttling"
                // in Developer options for faster updates.
                @Suppress("DEPRECATION") runCatching { wifi.startScan() }
                delay(30_000)
            }
        }
    }

    private fun processWifiResults() {
        if (Build.VERSION.SDK_INT < 30) return
        val results = runCatching { wifi.scanResults }.getOrNull() ?: return
        for (r in results) {
            for (ie in r.informationElements) {
                if (ie.id != 221) continue
                val buf = ie.bytes
                val body = ByteArray(buf.remaining()).also { buf.duplicate().get(it) }
                val res = DroneRepository.update(r.BSSID, "Wi-Fi Beacon", r.level) {
                    OdidParser.decodeWifiVendorIe(it, body)
                } ?: continue
                if (res.second) Alerts.notifyDrone(this, res.first)
            }
        }
    }

    // ---------- Wi-Fi NaN (Aware) ----------
    private fun startNan() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return
        val wam = getSystemService(WifiAwareManager::class.java) ?: return
        if (!wam.isAvailable) return
        runCatching {
            wam.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    awareSession = session
                    val cfg = SubscribeConfig.Builder()
                        .setServiceName("org.opendroneid.remoteid")
                        .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                        .build()
                    session.subscribe(cfg, object : DiscoverySessionCallback() {
                        override fun onServiceDiscovered(peer: PeerHandle, info: ByteArray?, mf: MutableList<ByteArray>?) {
                            val data = info ?: return
                            val res = DroneRepository.update("nan-${peer.hashCode()}", "Wi-Fi NaN", 0) {
                                OdidParser.decodeNan(it, data)
                            } ?: return
                            if (res.second) Alerts.notifyDrone(this@ScanService, res.first)
                        }
                    }, null)
                }
            }, null)
        }.onFailure { Log.w(TAG, "NaN", it) }
    }

    companion object {
        private const val TAG = "ScanService"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, ScanService::class.java)) }
                .onFailure { Log.w(TAG, "cannot start service now", it) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScanService::class.java))
            bleScannerStatic(ctx)?.let { s -> runCatching { s.stopScan(pending(ctx)) } }
        }

        private fun bleScannerStatic(ctx: Context) =
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner

        private fun pending(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 42, Intent(ctx, BleHitReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        /** The OS keeps this scan alive and wakes BleHitReceiver even if our process is dead. */
        fun registerPendingIntentScan(ctx: Context) {
            val scanner = bleScannerStatic(ctx) ?: return
            val filter = ScanFilter.Builder()
                .setServiceData(Alerts.ODID_UUID, byteArrayOf(0x0D), byteArrayOf(0xFF.toByte()))
                .build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
            runCatching { scanner.startScan(listOf(filter), settings, pending(ctx)) }
        }
    }
}
