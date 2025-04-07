package com.example.limo_safe.Object

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth

class PersistentTimer(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("timer_prefs", Context.MODE_PRIVATE)
    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val KEY_END_TIME = "timer_end_time"
        private const val KEY_CURRENT_CODE = "current_code"
        private const val KEY_IS_TIMER_RUNNING = "is_timer_running"
        private const val KEY_GENERATE_COOLDOWN = "generate_cooldown"
        private const val KEY_MORSE_COOLDOWN = "morse_cooldown"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_REMAINING_TRIES = "remaining_tries"
    }

    private fun getUserSpecificKey(key: String): String {
        val userId = auth.currentUser?.uid ?: return key
        return "${userId}_$key"
    }

    fun startTimer(durationMillis: Long, code: String) {
        val endTime = System.currentTimeMillis() + durationMillis
        prefs.edit().apply {
            putLong(getUserSpecificKey(KEY_END_TIME), endTime)
            putString(getUserSpecificKey(KEY_CURRENT_CODE), code)
            putBoolean(getUserSpecificKey(KEY_IS_TIMER_RUNNING), true)
            putString(getUserSpecificKey(KEY_USER_ID), auth.currentUser?.uid)
            apply()
        }
    }

    fun getRemainingTime(): Long {
        val endTime = prefs.getLong(getUserSpecificKey(KEY_END_TIME), 0)
        if (endTime == 0L) return 0

        val remaining = endTime - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0
    }

    fun getCurrentCode(): String {
        return prefs.getString(getUserSpecificKey(KEY_CURRENT_CODE), "") ?: ""
    }

    fun isTimerRunning(): Boolean {
        val userId = auth.currentUser?.uid ?: return false
        val savedUserId = prefs.getString(getUserSpecificKey(KEY_USER_ID), "")
        return userId == savedUserId &&
                prefs.getBoolean(getUserSpecificKey(KEY_IS_TIMER_RUNNING), false) &&
                getRemainingTime() > 0
    }

    fun stopTimer() {
        prefs.edit().apply {
            putBoolean(getUserSpecificKey(KEY_IS_TIMER_RUNNING), false)
            apply()
        }
    }

    fun setGenerateCooldown(durationMillis: Long) {
        val endTime = System.currentTimeMillis() + durationMillis
        prefs.edit().putLong(getUserSpecificKey(KEY_GENERATE_COOLDOWN), endTime).apply()
    }

    fun setMorseCooldown(durationMillis: Long) {
        val endTime = System.currentTimeMillis() + durationMillis
        prefs.edit().putLong(getUserSpecificKey(KEY_MORSE_COOLDOWN), endTime).apply()
    }

    fun getGenerateCooldownRemaining(): Long {
        val endTime = prefs.getLong(getUserSpecificKey(KEY_GENERATE_COOLDOWN), 0)
        if (endTime == 0L) return 0
        val remaining = endTime - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0
    }

    fun getMorseCooldownRemaining(): Long {
        val endTime = prefs.getLong(getUserSpecificKey(KEY_MORSE_COOLDOWN), 0)
        if (endTime == 0L) return 0
        val remaining = endTime - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0
    }

    fun setRemainingTries(tries: Int) {
        prefs.edit().putInt(getUserSpecificKey(KEY_REMAINING_TRIES), tries).apply()
    }

    fun getRemainingTries(): Int {
        return prefs.getInt(getUserSpecificKey(KEY_REMAINING_TRIES), 3)
    }

    fun clearUserData() {
        val userId = auth.currentUser?.uid ?: return
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("${userId}_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }
}