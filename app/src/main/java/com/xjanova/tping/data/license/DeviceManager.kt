package com.xjanova.tping.data.license

import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

/**
 * Manages device identification for license binding.
 *
 * ID priority:
 * 1. MediaDrm (Widevine) — stable across reinstall & factory reset (99%+ devices)
 * 2. Hardware-only constants — stable across reinstall but NOT unique per device
 * 3. ANDROID_ID — unique per device but changes on reinstall (Android 8+)
 *
 * NEVER use ANDROID_ID in the hardware hash — it changes on reinstall.
 */
object DeviceManager {

    private const val TAG = "DeviceManager"

    /** Widevine DRM scheme UUID — available on 99%+ of Android devices. */
    private val WIDEVINE_UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    /** Cached DRM ID — computed once per process. */
    @Volatile
    private var cachedDrmId: String? = null
    private var drmIdResolved = false

    /**
     * Get the Android ID (NOT stable across reinstall on Android 8+).
     * Only used as supplementary server lookup field, NEVER in hardware hash.
     */
    fun getAndroidId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    /**
     * @deprecated Use [getAndroidId] — renamed for clarity.
     */
    fun getDeviceId(context: Context): String = getAndroidId(context)

    /**
     * Get a stable device ID using MediaDrm (Widevine).
     * This ID survives app reinstall and factory reset.
     * Returns null if Widevine is not available (extremely rare).
     * Result is cached for the process lifetime.
     */
    fun getDrmId(): String? {
        if (drmIdResolved) return cachedDrmId
        synchronized(this) {
            if (drmIdResolved) return cachedDrmId
            cachedDrmId = try {
                val mediaDrm = MediaDrm(WIDEVINE_UUID)
                try {
                    val id = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                    id.joinToString("") { "%02x".format(it) }
                } finally {
                    mediaDrm.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaDrm ID unavailable", e)
                null
            }
            drmIdResolved = true
            Log.d(TAG, "DRM ID resolved: ${if (cachedDrmId != null) "OK (${cachedDrmId!!.length} chars)" else "UNAVAILABLE"}")
            return cachedDrmId
        }
    }

    /**
     * Get a stable display ID for the UI.
     * Uses DRM ID (truncated) → hardware hash as fallback.
     * This ID is STABLE across reinstalls — unlike ANDROID_ID.
     */
    fun getStableDisplayId(context: Context): String {
        val drmId = getDrmId()
        if (drmId != null) {
            // Show first 16 hex chars of drmId, uppercase, formatted as XXXX-XXXX-XXXX-XXXX
            val short = drmId.take(16).uppercase()
            return if (short.length >= 16) {
                "${short.substring(0, 4)}-${short.substring(4, 8)}-${short.substring(8, 12)}-${short.substring(12, 16)}"
            } else {
                short
            }
        }
        // Fallback: first 16 chars of hardware hash
        val hash = getHardwareHash(context).take(16).uppercase()
        return if (hash.length >= 16) {
            "${hash.substring(0, 4)}-${hash.substring(4, 8)}-${hash.substring(8, 12)}-${hash.substring(12, 16)}"
        } else {
            hash
        }
    }

    /**
     * Get a human-readable device name.
     */
    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    /**
     * Get OS version string.
     */
    fun getOsVersion(): String {
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    /**
     * Create a hardware hash for device fingerprinting.
     * Uses MediaDrm (Widevine) ID as primary — stable across reinstall & factory reset.
     *
     * IMPORTANT: NEVER uses ANDROID_ID — it changes on reinstall (Android 8+).
     * Fallback uses hardware-only constants (not unique per device, but stable).
     */
    fun getHardwareHash(context: Context): String {
        val drmId = getDrmId()
        val raw = if (drmId != null) {
            // Primary: DRM-based (unique + stable)
            "$drmId|${Build.MANUFACTURER}|${Build.MODEL}|${Build.BOARD}|${Build.HARDWARE}"
        } else {
            // Fallback: hardware-only (stable but may collide across same-model devices)
            // Include as many hardware constants as possible to reduce collision
            Log.w(TAG, "getHardwareHash: DRM unavailable, using hardware-only fallback")
            "no-drm|${Build.MANUFACTURER}|${Build.MODEL}|${Build.BOARD}|${Build.HARDWARE}|${Build.BRAND}|${Build.DEVICE}|${Build.PRODUCT}|${Build.DISPLAY}"
        }
        return sha256(raw)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
