package com.antigravity.mobile.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.antigravity.mobile.data.model.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground Service that manages the lifecycle of the PRoot Debian container
 * and provides persistent execution when user switches apps.
 */
class AntigravityService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var containerManager: ContainerManager
        private set

    var antigravityBridge: AntigravityBridge? = null
        private set

    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): AntigravityService = this@AntigravityService
    }

    override fun onCreate() {
        super.onCreate()
        containerManager = ContainerManager(applicationContext)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AntigravityMobile::EngineWakeLock"
        )

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Antigravity Engine Ready")
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    fun startSession(account: Account, projectDir: File, onReady: () -> Unit) {
        serviceScope.launch {
            wakeLock?.acquire(30 * 60 * 1000L) // 30 min max

            updateNotification("Launching Debian Virtual Container...")
            val ready = containerManager.setupContainerIfNeeded()
            if (ready) {
                updateNotification("Starting Antigravity agent in ${projectDir.name}...")
                containerManager.startAntigravityEngine(account, projectDir, port = 18080)

                // Initialize WebSocket bridge
                val bridge = AntigravityBridge(serviceScope, port = 18080)
                antigravityBridge = bridge
                bridge.connect()

                updateNotification("Antigravity Active: ${account.name}")
                withContext(Dispatchers.Main) {
                    onReady()
                }
            }
        }
    }

    fun stopSession() {
        antigravityBridge?.disconnect()
        containerManager.stopAntigravityEngine()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        updateNotification("Antigravity Engine Idle")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Antigravity Engine Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Antigravity AI agents running in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Antigravity Mobile Engine")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopSession()
        serviceScope.cancel()
    }

    companion object {
        private const val CHANNEL_ID = "antigravity_service_channel"
        private const val NOTIFICATION_ID = 2001
    }
}
