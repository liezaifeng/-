package com.ideawav.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台服务：在 App 退到后台（切到 revheadz）后，持续通过 GPS 获取车速并通过
 * BLE 发送给开发板。前台服务（location + connectedDevice 类型）保证进程不被
 * 系统杀死，且能在后台持续定位与维持蓝牙连接。
 */
class SpeedSyncService : Service() {

    companion object {
        private const val TAG = "SpeedSyncService"
        private const val CHANNEL_ID = "speed_sync"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "com.ideawav.app.action.STOP_SPEED_SYNC"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var boardSync: BoardSyncManager
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    override fun onCreate() {
        super.onCreate()
        boardSync = BoardSyncManager.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(NOTIFICATION_ID, buildNotification())
        startGps()
        boardSync.connect()
        return START_STICKY
    }

    override fun onDestroy() {
        stopGps()
        boardSync.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== GPS ====================

    private fun startGps() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = lm
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // Location.speed 单位为 m/s，转成 KM/H
                val kmh = location.speed * 3.6f
                handler.post { boardSync.updateSpeed(kmh) }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener!!)
        } catch (e: SecurityException) {
            Log.w(TAG, "GPS 无权限: ${e.message}")
        }
    }

    private fun stopGps() {
        locationListener?.let { locationManager?.removeUpdates(it) }
        locationListener = null
        locationManager = null
    }

    // ==================== 通知 ====================

    private fun buildNotification(): Notification {
        createChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("模拟声浪")
            .setContentText("正在后台发送车速给开发板")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "车速同步", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    // ==================== 前台服务类型 ====================

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(id, notification, type)
        } else {
            startForeground(id, notification)
        }
    }
}
