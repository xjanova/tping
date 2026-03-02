package com.xjanova.tping.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Helper to navigate directly to the correct permission settings page
 * for different phone brands (Samsung, Xiaomi, Oppo, Vivo, Huawei, etc.)
 */
object PermissionHelper {

    private const val TAG = "PermissionHelper"

    /** Get device brand for display */
    fun getDeviceBrand(): String = Build.MANUFACTURER

    /**
     * Open Accessibility Settings - tries brand-specific deep links first,
     * falls back to standard Settings.ACTION_ACCESSIBILITY_SETTINGS.
     */
    fun openAccessibilitySettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()

        // Try brand-specific intents first
        val brandIntent = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                tryIntent("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity")
            }
            manufacturer.contains("samsung") -> {
                tryIntent("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity")
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                tryIntent("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity")
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                tryIntent("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity")
            }
            manufacturer.contains("vivo") -> {
                tryIntent("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity")
            }
            else -> null
        }

        try {
            if (brandIntent != null) {
                brandIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(brandIntent)
                return
            }
        } catch (e: Exception) {
            Log.d(TAG, "Brand-specific intent failed: ${e.message}")
        }

        // Fallback: standard Android accessibility settings
        try {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings: ${e.message}")
        }
    }

    /**
     * Open Overlay Permission Settings - navigates directly to this app's overlay permission page.
     */
    fun openOverlaySettings(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Fallback: open app settings
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open overlay settings: ${e2.message}")
            }
        }
    }

    /**
     * Open Notification Permission Settings (Android 13+).
     */
    fun openNotificationSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                openAppSettings(context)
            }
        } catch (e: Exception) {
            openAppSettings(context)
        }
    }

    /**
     * Open the app's detail settings page.
     */
    fun openAppSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings: ${e.message}")
        }
    }

    /**
     * Get brand-specific instructions for enabling Accessibility.
     */
    fun getAccessibilityInstructions(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.MANUFACTURER

        return when {
            manufacturer.contains("samsung") ->
                "สำหรับ $brand:\nตั้งค่า → การช่วยเหลือพิเศษ → แอพที่ติดตั้ง → Tping → เปิด"

            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                "สำหรับ $brand:\nตั้งค่า → การตั้งค่าเพิ่มเติม → การเข้าถึง → Tping → เปิด\n" +
                "⚠ อาจต้องปิด \"MIUI Optimization\" ในตัวเลือกนักพัฒนา"

            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                "สำหรับ $brand:\nตั้งค่า → การเข้าถึง → Tping → เปิด\n" +
                "⚠ ไปที่ ตั้งค่า → แบตเตอรี่ → เปิดแอพ → Tping → จัดการด้วยตนเอง"

            manufacturer.contains("oppo") || manufacturer.contains("realme") ->
                "สำหรับ $brand:\nตั้งค่า → การตั้งค่าเพิ่มเติม → การเข้าถึง → แอพที่ดาวน์โหลด → Tping → เปิด"

            manufacturer.contains("vivo") ->
                "สำหรับ $brand:\nตั้งค่า → ตั้งค่าเพิ่มเติม → การเข้าถึง → Tping → เปิด"

            manufacturer.contains("oneplus") ->
                "สำหรับ $brand:\nตั้งค่า → การตั้งค่าเพิ่มเติม → การเข้าถึง → แอพที่ดาวน์โหลด → Tping → เปิด"

            manufacturer.contains("google") ->
                "สำหรับ $brand Pixel:\nตั้งค่า → การเข้าถึง → Tping → เปิดบริการ"

            else ->
                "สำหรับ $brand:\nตั้งค่า → การเข้าถึง (Accessibility) → ค้นหา \"Tping\" → เปิด"
        }
    }

    /**
     * Get battery optimization warning for brands that aggressively kill background services.
     */
    fun getBatteryOptimizationTip(): String? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                "⚡ ${ Build.MANUFACTURER}: ไปที่ ตั้งค่า → แอพ → Tping → ประหยัดแบตเตอรี่ → ไม่มีข้อจำกัด\nและ ล็อกแอพไว้ใน Recent Apps (ปัดลงบนแอพ)"

            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                "⚡ ${Build.MANUFACTURER}: ไปที่ ตั้งค่า → แบตเตอรี่ → เปิดแอพ → Tping → จัดการด้วยตนเอง → เปิดทุกตัวเลือก"

            manufacturer.contains("oppo") || manufacturer.contains("realme") ->
                "⚡ ${Build.MANUFACTURER}: ไปที่ ตั้งค่า → แบตเตอรี่ → Tping → อนุญาตกิจกรรมเบื้องหลัง"

            manufacturer.contains("vivo") ->
                "⚡ ${Build.MANUFACTURER}: ไปที่ ตั้งค่า → แบตเตอรี่ → พื้นหลังสูง → Tping → เปิด"

            manufacturer.contains("samsung") ->
                "⚡ ${Build.MANUFACTURER}: ไปที่ ตั้งค่า → การดูแลอุปกรณ์ → แบตเตอรี่ → ขีดจำกัดการใช้พื้นหลัง → ไม่จำกัด Tping"

            else -> null
        }
    }

    private fun tryIntent(packageName: String, className: String): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(packageName, className)
            }
        } catch (e: Exception) {
            null
        }
    }
}
