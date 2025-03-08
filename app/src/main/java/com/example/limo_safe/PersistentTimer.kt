package com.example.limo_safe.Object

import android.content.Context
import android.content.SharedPreferences

class PersistentTimer(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_END_TIME = "timer_end_time"
        private const val KEY_CURRENT_CODE = "current_code"
        private const val KEY_IS_TIMER_RUNNING = "is_timer_running"
    }

    fun startTimer(durationMillis: Long, code: String) {
        val endTime = System.currentTimeMillis() + durationMillis
        prefs.edit().apply {
            putLong(KEY_END_TIME, endTime)
            putString(KEY_CURRENT_CODE, code)
            putBoolean(KEY_IS_TIMER_RUNNING, true)
            apply()
        }
    }

    fun getRemainingTime(): Long {
        val endTime = prefs.getLong(KEY_END_TIME, 0)
        if (endTime == 0L) return 0

        val remaining = endTime - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0
    }

    fun getCurrentCode(): String {
        return prefs.getString(KEY_CURRENT_CODE, "") ?: ""
    }

    fun isTimerRunning(): Boolean {
        return prefs.getBoolean(KEY_IS_TIMER_RUNNING, false) && getRemainingTime() > 0
    }

    fun stopTimer() {
        prefs.edit().apply {
            putBoolean(KEY_IS_TIMER_RUNNING, false)
            apply()
        }
    }
}