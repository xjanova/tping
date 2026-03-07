package com.xjanova.tping

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.xjanova.tping.data.cloud.CloudAuthManager
import com.xjanova.tping.data.database.TpingDatabase

class TpingApplication : Application() {

    val database: TpingDatabase by lazy { TpingDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Global crash handler — save stack trace to prefs for display on next launch
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("TpingApp", "UNCAUGHT EXCEPTION in ${thread.name}", throwable)
            try {
                val prefs = getSharedPreferences("crash_log", MODE_PRIVATE)
                val trace = throwable.stackTraceToString().take(3000)
                prefs.edit()
                    .putString("last_crash", trace)
                    .putLong("crash_time", System.currentTimeMillis())
                    .commit() // commit() not apply() — must write before process dies
            } catch (_: Exception) { }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            CloudAuthManager.initialize(this)
        } catch (e: Exception) {
            Log.e("TpingApp", "CloudAuthManager.initialize failed", e)
        }

        try {
            createNotificationChannel()
        } catch (e: Exception) {
            Log.e("TpingApp", "createNotificationChannel failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "tping_service_channel"
        lateinit var instance: TpingApplication
            private set
    }
}
