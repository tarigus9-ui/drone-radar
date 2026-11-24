package com.droneradar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Alerts {
    const val CH_SERVICE = "scanner"
    const val CH_ALERT = "drone_alert"
    const val SERVICE_NOTIF_ID = 1
    val ODID_UUID: ParcelUuid = ParcelUuid.fromString("0000fffa-0000-1000-8000-00805f9b34fb")

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, ctx.getString(R.string.ch_service), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, ctx.getString(R.string.ch_alert), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), null)
            }
        )
    }

    fun openAppIntent(ctx: Context, key: String? = null): PendingIntent =
        PendingIntent.getActivity(
            ctx, key?.hashCode() ?: 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("focus", key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun notifyDrone(ctx: Context, d: DroneInfo) {
        if (SpoofCheck.silentBecauseWhitelisted(d)) return   // authorized and genuine — no alarm
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannels(ctx)
        val op = if (d.opLat != null) ctx.getString(R.string.op_known) else ctx.getString(R.string.op_unknown)
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_drone)
            .setContentTitle(ctx.getString(if (d.spoof.isNotEmpty()) R.string.alert_title_spoof else R.string.alert_title))
            .setContentText("${d.label} • ${d.source} • $op")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "${d.label} • ${d.source} • $op" +
                    if (d.spoof.isNotEmpty()) "\n⚠️ " + SpoofCheck.describe(ctx, d.spoof) else ""))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, d.key))
            .build()
        NotificationManagerCompat.from(ctx).notify(d.key.hashCode(), n)
    }

    /** Shared BLE handling for the live callback and the PendingIntent receiver. */
    fun handleBle(ctx: Context, r: ScanResult) {
        val data = r.scanRecord?.getServiceData(ODID_UUID) ?: return
        val res = DroneRepository.update(r.device.address, "Bluetooth", r.rssi) {
            OdidParser.decodeBle(it, data)
        } ?: return
        if (res.second) notifyDrone(ctx, res.first)
    }
}
