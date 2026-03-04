package com.xjanova.tping.data.license

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * Manages device identification for license binding.
 */
object DeviceManager {

    /**
     * Get the Android ID (unique per device per app signing key).
     */
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
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
     */
    fun getHardwareHash(context: Context): String {
        val raw = "${getDeviceId(context)}|${Build.MANUFACTURER}|${Build.MODEL}|${Build.BOARD}|${Build.HARDWARE}"
        return sha256(raw)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
