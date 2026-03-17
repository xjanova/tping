package com.xjanova.tping.data.diagnostic

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xjanova.tping.BuildConfig
import com.xjanova.tping.data.license.CertPinning
import com.xjanova.tping.data.license.DeviceManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
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
        .certificatePinner(CertPinning.pinner)
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

        val hardwareHash = DeviceManager.getHardwareHash(ctx)
        val deviceName = DeviceManager.getDeviceName()
        val osVersion = DeviceManager.getOsVersion()

        val body = JsonObject().apply {
            addProperty("machine_id", hardwareHash)
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

    // ====== Debug Image Upload ======

    private val IMAGE_TYPE = "image/png".toMediaType()

    /**
     * Upload puzzle debug images to server for learning/analysis.
     * Sends all PNG files from the debug directory as multipart form data.
     * Call from background thread.
     *
     * @param debugDir directory containing debug PNGs (e.g. puzzle_debug/)
     * @param metadata extra info: gapX, confidence, detectionMethod, attempt, etc.
     */
    /** Last upload status for diagnostic feedback */
    @Volatile var lastUploadStatus: String = "not_attempted"
        private set

    /**
     * Upload debug images and return the server record ID.
     * Returns record_id > 0 on success, -1 on failure.
     * After successful upload, deletes the uploaded files to save device storage.
     * Call from background thread (synchronous HTTP).
     */
    fun uploadDebugImages(debugDir: File, metadata: Map<String, String>): Long {
        val ctx = appContext ?: run {
            lastUploadStatus = "err:app_not_init"
            return -1
        }

        val pngFiles = debugDir.listFiles { f -> f.extension == "png" }
        if (pngFiles.isNullOrEmpty()) {
            lastUploadStatus = "err:no_png_files(dir=${debugDir.absolutePath},exists=${debugDir.exists()})"
            Log.d(TAG, "No debug images to upload: ${lastUploadStatus}")
            return -1
        }

        val sorted = pngFiles.sortedBy { it.name }
        val filesToUpload = sorted.takeLast(10)
        val totalSize = filesToUpload.sumOf { it.length() }
        Log.d(TAG, "Uploading ${filesToUpload.size} debug images (${totalSize / 1024}KB): " +
            filesToUpload.joinToString { it.name })

        val hardwareHash = DeviceManager.getHardwareHash(ctx)

        try {
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("machine_id", hardwareHash)
                .addFormDataPart("app_version", BuildConfig.VERSION_NAME)
                .addFormDataPart("timestamp", dateFormat.format(Date()))

            for ((key, value) in metadata) {
                builder.addFormDataPart(key, value)
            }

            for (file in filesToUpload) {
                builder.addFormDataPart(
                    "images[]", file.name,
                    file.asRequestBody(IMAGE_TYPE)
                )
            }

            val requestBody = builder.build()
            val request = Request.Builder()
                .url("$BASE_URL/debug-images")
                .post(requestBody)
                .addHeader("Accept", "application/json")
                .build()

            val uploadClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .certificatePinner(CertPinning.pinner)
                .build()

            val response = uploadClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = gson.fromJson(body, com.google.gson.JsonObject::class.java)
                val recordId = json?.get("id")?.asLong ?: -1
                Log.d(TAG, "Uploaded ${filesToUpload.size} debug images → record #$recordId")
                lastUploadStatus = "ok:${filesToUpload.size}imgs,record=$recordId"

                // Delete uploaded files to save device storage
                for (file in filesToUpload) {
                    try { file.delete() } catch (_: Exception) {}
                }
                Log.d(TAG, "Cleaned up ${filesToUpload.size} uploaded debug images")

                return recordId
            } else {
                val msg = response.body?.string()?.take(200) ?: "HTTP ${response.code}"
                lastUploadStatus = "err:http_${response.code}($msg)"
                Log.w(TAG, "Debug image upload failed: $msg")
                return -1
            }
        } catch (e: Exception) {
            lastUploadStatus = "err:${e.javaClass.simpleName}(${e.localizedMessage?.take(80)})"
            Log.e(TAG, "Debug image upload error", e)
            return -1
        }
    }

    // ====== Puzzle Feedback (Auto-Label) ======

    /**
     * Send puzzle solve result back to server for tracking.
     * IMPORTANT: Never send actual_gap_x — only HUMAN labeling via admin
     * page should set actual_gap_x. Otherwise records get auto-labeled
     * and hidden from the admin review queue.
     *
     * Call from background thread.
     */
    fun sendPuzzleFeedback(
        success: Boolean,
        detectedGapX: Int,
        actualGapX: Int? = null,
        attempt: Int = 0,
        detectionMethod: String = "unknown",
        recordId: Long = -1
    ): SendResult {
        val ctx = appContext ?: return SendResult(false, "App not initialized")
        val hardwareHash = DeviceManager.getHardwareHash(ctx)

        try {
            val payload = mutableMapOf<String, Any?>(
                "machine_id" to hardwareHash,
                "app_version" to BuildConfig.VERSION_NAME,
                "success" to success,
                "detected_gap_x" to detectedGapX,
                // NEVER auto-set actual_gap_x — human must label via admin page
                // Store app's guess in metadata only (for reference, not for training)
                "attempt" to attempt,
                "detection_method" to detectionMethod,
                "timestamp" to dateFormat.format(java.util.Date())
            )
            // Store app's gap guess in metadata (not as actual_gap_x label)
            if (actualGapX != null) {
                payload["app_estimated_gap_x"] = actualGapX
            }
            // Link to specific upload record
            if (recordId > 0) {
                payload["record_id"] = recordId
            }
            // Diagnostic: include upload status so server knows what happened
            payload["upload_status"] = lastUploadStatus

            val json = gson.toJson(payload)
            val requestBody = json.toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url("$BASE_URL/debug-images/feedback")
                .post(requestBody)
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            return if (response.isSuccessful) {
                Log.d(TAG, "Puzzle feedback sent: success=$success gap=$detectedGapX actual=$actualGapX")
                SendResult(true, "Feedback sent")
            } else {
                SendResult(false, "Feedback failed (${response.code})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Puzzle feedback error", e)
            return SendResult(false, "Feedback error: ${e.localizedMessage?.take(100)}")
        }
    }

    // ====== Server Inference ======

    /**
     * Request gap detection from server AI model.
     * Sends the before/after screenshots, server returns predicted gap_x.
     *
     * Call from background thread.
     *
     * @return predicted gap X coordinate, or null if server can't detect
     */
    fun requestServerInference(
        beforeImage: java.io.File,
        afterImage: java.io.File,
        sliderX: Int,
        sliderY: Int,
        moveDistance: Int,
        trackWidth: Int
    ): Int? {
        val ctx = appContext ?: return null

        try {
            val hardwareHash = DeviceManager.getHardwareHash(ctx)

            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("machine_id", hardwareHash)
                .addFormDataPart("app_version", BuildConfig.VERSION_NAME)
                .addFormDataPart("slider_x", sliderX.toString())
                .addFormDataPart("slider_y", sliderY.toString())
                .addFormDataPart("move_distance", moveDistance.toString())
                .addFormDataPart("track_width", trackWidth.toString())
                .addFormDataPart(
                    "before", "before.png",
                    beforeImage.asRequestBody(IMAGE_TYPE)
                )
                .addFormDataPart(
                    "after", "after.png",
                    afterImage.asRequestBody(IMAGE_TYPE)
                )

            val request = Request.Builder()
                .url("$BASE_URL/debug-images/infer")
                .post(builder.build())
                .addHeader("Accept", "application/json")
                .build()

            val inferClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .certificatePinner(CertPinning.pinner)
                .build()

            val response = inferClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val result = gson.fromJson(body, Map::class.java)
                val gapX = (result["gap_x"] as? Number)?.toInt()
                val confidence = (result["confidence"] as? Number)?.toDouble() ?: 0.0
                Log.d(TAG, "Server inference: gap_x=$gapX confidence=$confidence")
                return if (confidence >= 0.3) gapX else null
            } else {
                Log.w(TAG, "Server inference failed: ${response.code}")
                return null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Server inference error: ${e.message}")
            return null
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
