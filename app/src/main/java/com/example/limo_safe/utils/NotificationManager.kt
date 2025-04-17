package com.example.limo_safe.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.limo_safe.MainActivity
import com.example.limo_safe.R

/**
 * Utility class to handle device status notifications across the app.
 */
class DeviceNotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "device_status_channel"
        private const val NOTIFICATION_ID_BASE = 1000

        // Notification types
        const val STATUS_ONLINE = "online"
        const val STATUS_LOCKED = "locked"
        const val STATUS_SECURE = "secure"
    }

    init {
        // Create the notification channel when instance is created
        createNotificationChannel()
    }

    /**
     * Check if notification permissions are granted
     * @return true if permissions are granted or not needed (pre-Android 13)
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Creates notification channel for Android 8.0+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Device Status Alerts"
            val descriptionText = "Notifications for device status changes"
            val importance = NotificationManager.IMPORTANCE_HIGH // High importance for heads-up
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC // Show on lockscreen
            }
            // Register the channel with the system
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Send a notification for device status change.
     *
     * @param deviceId Unique identifier for the device
     * @param deviceName Display name of the device
     * @param statusType Type of status change (online, locked, secure)
     * @param statusValue New status value (true = good state, false = alert state)
     * @param showOnlyAlerts If true, only shows notifications for alert states (false values)
     */
    fun sendStatusNotification(
        deviceId: String,
        deviceName: String,
        statusType: String,
        statusValue: Boolean,
        showOnlyAlerts: Boolean = true
    ) {
        // Check for notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w("DeviceNotificationManager", "Notification permission not granted")
                return
            }
        }

        // Skip if we're only showing alerts and this is a positive status
        if (showOnlyAlerts && statusValue) {
            return
        }

        // Create an intent that opens the app
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        // Prepare notification content based on status type
        val (title, message) = when (statusType) {
            STATUS_ONLINE -> {
                if (statusValue) {
                    Pair("Device Online", "Device '$deviceName' is now online")
                } else {
                    Pair("Device Offline", "⚠️ Device '$deviceName' has gone offline")
                }
            }
            STATUS_LOCKED -> {
                if (statusValue) {
                    Pair("Device Locked", "Device '$deviceName' is now locked")
                } else {
                    Pair("Device Unlocked", "⚠️ Device '$deviceName' has been unlocked")
                }
            }
            STATUS_SECURE -> {
                if (statusValue) {
                    Pair("Device Secure", "Device '$deviceName' is now secure")
                } else {
                    Pair("Tamper Detected", "⚠️ Tamper detected on device '$deviceName'")
                }
            }
            else -> Pair("Status Change", "Device '$deviceName' status changed")
        }

        // Set priority based on status value
        val priority = if (!statusValue) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT

        // Create the notification
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification) // Make sure you have this icon
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show full content on lockscreen

        // For heads-up notification, add these for alert conditions
        if (!statusValue) {
            builder.setFullScreenIntent(pendingIntent, true)
            builder.setVibrate(longArrayOf(0, 500, 250, 500))
        }

        // Use a unique ID for each device and status type
        val notificationId = NOTIFICATION_ID_BASE + deviceId.hashCode() + statusType.hashCode()

        try {
            with(NotificationManagerCompat.from(context)) {
                notify(notificationId, builder.build())
            }
        } catch (e: Exception) {
            Log.e("DeviceNotificationManager", "Error sending notification: ${e.message}")
        }
    }

    /**
     * Utility method to detect status changes and notify if needed
     *
     * @param deviceId Unique identifier of the device
     * @param deviceName Display name of the device
     * @param previousStates Previous device states (online, locked, secure) or null if first time
     * @param currentStates Current device states (online, locked, secure)
     * @return Current states (to be used as previous states in next call)
     */
    fun notifyChanges(
        deviceId: String,
        deviceName: String,
        previousStates: Triple<Boolean, Boolean, Boolean>?,
        currentStates: Triple<Boolean, Boolean, Boolean>
    ): Triple<Boolean, Boolean, Boolean> {
        // Unpack current states
        val (currentOnline, currentLocked, currentSecure) = currentStates

        // If we have previous states, check for changes
        previousStates?.let { prevStates ->
            val (prevOnline, prevLocked, prevSecure) = prevStates

            // Check for online status change
            if (prevOnline != currentOnline) {
                sendStatusNotification(deviceId, deviceName, STATUS_ONLINE, currentOnline)
            }

            // Check for lock status change
            if (prevLocked != currentLocked) {
                sendStatusNotification(deviceId, deviceName, STATUS_LOCKED, currentLocked)
            }

            // Check for security status change
            if (prevSecure != currentSecure) {
                sendStatusNotification(deviceId, deviceName, STATUS_SECURE, currentSecure)
            }
        }

        return currentStates
    }
}