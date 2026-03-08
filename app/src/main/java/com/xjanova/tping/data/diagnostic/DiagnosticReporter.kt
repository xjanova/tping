package com.xjanova.tping.data.diagnostic

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xjanova.tping.BuildConfig
import com.xjanova.tping.data.license.DeviceManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Collects and sends diagnostic reports to xmanstudio backend.
 * Stores events in SharedPreferences as a circular buffer (max 50 events).
 * Follows the same pattern as LicenseApiClient for HTTP.
 */
object DiagnosticReporter {

    private const val TAG = "DiagnosticReporter"
    private const val BASE_URL = "https://xman4289.com/api/v1/product/tping"
    private const val PREFS_NAME = "tping_diagnostics"
    private const val KEY_EVENTS = "events_json"
    private const val MAX_EVENTS = 50

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    // ====== Event Recording ======

    /**
     * Log a diagnostic event (crash, captcha result, error, etc.)
     * Stored locally, sent when user taps "ส่งรายงาน" or auto-sent on crash.
     */
    fun logEvent(category: String, message: String, details: String = "") {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
            val events: JsonArray = try {
                gson.fromJson(existing, JsonArray::class.java) ?: JsonArray()
            } catch (_: Exception) { JsonArray() }

            val event = JsonObject().apply {
                addProperty("category", category)
                addProperty("message", message.take(500))
                addProperty("details", details.take(2000))
                addProperty("timestamp", dateFormat.format(Date()))
                addProperty("version", BuildConfig.VERSION_NAME)
            }
            events.add(event)

            // Keep only last MAX_EVENTS
            while (events.size() > MAX_EVENTS) {
                events.remove(0)
            }

            prefs.edit().putString(KEY_EVENTS, gson.toJson(events)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log event", e)
        }
    }

    /**
     * Log a crash from UncaughtExceptionHandler.
     * Uses commit() to ensure write before process death.
     */
    fun logCrash(throwable: Throwable) {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
            val events: JsonArray = try {
                gson.fromJson(existing, JsonArray::class.java) ?: JsonArray()
            } catch (_: Exception) { JsonArray() }

            val event = JsonObject().apply {
                addProperty("category", "crash")
                addProperty("message", throwable.message?.take(500) ?: "Unknown crash")
                addProperty("details", throwable.stackTraceToString().take(2000))
                addProperty("timestamp", dateFormat.format(Date()))
                addProperty("version", BuildConfig.VERSION_NAME)
            }
            events.add(event)

            while (events.size() > MAX_EVENTS) {
                events.remove(0)
            }

            prefs.edit().putString(KEY_EVENTS, gson.toJson(events)).commit()
        } catch (_: Exception) { }
    }

    /**
     * Log captcha solver status for debugging.
     */
    fun logCaptcha(message: String, details: String = "") {
        logEvent("captcha", message, details)
    }

    // ====== Sending Reports ======

    /**
     * Get count of pending diagnostic events.
     */
    fun getPendingCount(): Int {
        val ctx = appContext ?: return 0
        return try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
            val events = gson.fromJson(existing, JsonArray::class.java) ?: JsonArray()
            events.size()
        } catch (_: Exception) { 0 }
    }

    /**
     * Send all pending diagnostic events to the server.
     * Returns true if successful (or nothing to send).
     * Call from background thread.
     */
    fun sendReport(): SendResult {
        val ctx = appContext ?: return SendResult(false, "App not initialized")

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
        val events: JsonArray = try {
            gson.fromJson(existing, JsonArray::class.java) ?: JsonArray()
        } catch (_: Exception) { JsonArray() }

        if (events.size() == 0) {
            return SendResult(true, "ไม่มีรายงานที่ต้องส่ง")
        }

        val deviceId = DeviceManager.getDeviceId(ctx)
        val deviceName = DeviceManager.getDeviceName()
        val osVersion = DeviceManager.getOsVersion()
        val hardwareHash = DeviceManager.getHardwareHash(ctx)

        val body = JsonObject().apply {
            addProperty("machine_id", deviceId)
            addProperty("machine_name", deviceName)
            addProperty("os_version", osVersion)
            addProperty("hardware_hash", hardwareHash)
            addProperty("app_version", BuildConfig.VERSION_NAME)
            addProperty("app_version_code", BuildConfig.VERSION_CODE)
            add("events", events)
        }

        return try {
            val requestBody = gson.toJson(body).toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/diagnostics")
                .post(requestBody)
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                // Clear sent events
                prefs.edit().putString(KEY_EVENTS, "[]").apply()
                Log.d(TAG, "Sent ${events.size()} diagnostic events successfully")
                SendResult(true, "ส่ง ${events.size()} รายการสำเร็จ")
            } else {
                val msg = response.body?.string()?.take(200) ?: "HTTP ${response.code}"
                Log.w(TAG, "Failed to send diagnostics: $msg")
                SendResult(false, "ส่งไม่สำเร็จ (${response.code})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send diagnostics", e)
            SendResult(false, "เชื่อมต่อไม่ได้: ${e.localizedMessage?.take(100)}")
        }
    }

    /**
     * Clear all pending events without sending.
     */
    fun clearEvents() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_EVENTS, "[]").apply()
    }

    data class SendResult(val success: Boolean, val message: String)
}
