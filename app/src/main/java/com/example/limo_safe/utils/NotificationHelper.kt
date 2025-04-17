package com.example.limo_safe.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * Helper class to manage notification permissions and handle permission requests
 */
class NotificationHelper(private val context: Context) {
    private var permissionLauncher: ActivityResultLauncher<String>? = null

    /**
     * Initialize the permission launcher for a Fragment
     */
    fun setupPermissionLauncher(fragment: Fragment, onResult: (Boolean) -> Unit) {
        permissionLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            onResult(isGranted)
        }
    }

    /**
     * Check if notification permission is granted
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Permission not required for Android 12 and below
        }
    }

    /**
     * Request notification permission if needed
     */
    fun requestNotificationPermissionIfNeeded(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                hasNotificationPermission() -> {
                    onResult(true)
                }
                permissionLauncher != null -> {
                    permissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    onResult(false)
                }
            }
        } else {
            onResult(true)
        }
    }
}
