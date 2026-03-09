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
 */
object DeviceManager {

    private const val TAG = "DeviceManager"

    /** Widevine DRM scheme UUID — available on 99%+ of Android devices. */
    private val WIDEVINE_UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    /**
     * Get the Android ID (for display purposes only — not stable across reinstall).
     */
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    /**
     * Get a stable device ID using MediaDrm (Widevine).
     * This ID survives app reinstall and factory reset.
     * Returns null if Widevine is not available (extremely rare).
     */
    fun getDrmId(): String? {
        return try {
            val mediaDrm = MediaDrm(WIDEVINE_UUID)
            try {
                val id = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                id.joinToString("") { "%02x".format(it) }
            } finally {
                mediaDrm.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaDrm ID unavailable, will fallback to ANDROID_ID", e)
            null
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
     * Falls back to ANDROID_ID only if Widevine is unavailable.
     */
    fun getHardwareHash(context: Context): String {
        val stableId = getDrmId() ?: getDeviceId(context)
        val raw = "$stableId|${Build.MANUFACTURER}|${Build.MODEL}|${Build.BOARD}|${Build.HARDWARE}"
        return sha256(raw)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
