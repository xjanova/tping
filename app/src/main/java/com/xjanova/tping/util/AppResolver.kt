package com.xjanova.tping.util

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

object AppResolver {

    /**
     * Get the user-visible name of an app from its package name.
     * Returns the last segment of the package name as fallback.
     */
    fun getAppName(context: Context, packageName: String): String {
        if (packageName.isBlank()) return ""
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
                .replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Get the icon Drawable of an app from its package name.
     */
    fun getAppIcon(context: Context, packageName: String): Drawable? {
        if (packageName.isBlank()) return null
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Launch an app by its package name.
     * Returns true if the launch intent was found and started.
     */
    fun launchApp(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Suggest a Thai-friendly field name based on the input field's metadata.
     * Uses resourceId, hintText, and contentDescription to infer what the field is for.
     */
    fun suggestFieldName(
        resourceId: String = "",
        hintText: String = "",
        contentDescription: String = ""
    ): String {
        // Combine all available info
        val combined = "$resourceId $hintText $contentDescription".lowercase()

        // Check against known patterns → Thai labels
        for ((patterns, label) in fieldPatterns) {
            if (patterns.any { it in combined }) {
                return label
            }
        }
        return ""
    }

    // Common field patterns mapped to Thai-friendly names
    private val fieldPatterns: List<Pair<List<String>, String>> = listOf(
        listOf("email", "e-mail", "อีเมล") to "อีเมล",
        listOf("password", "passwd", "รหัสผ่าน") to "รหัสผ่าน",
        listOf("username", "user_name", "ชื่อผู้ใช้", "userid", "user_id") to "ชื่อผู้ใช้",
        listOf("phone", "tel", "mobile", "เบอร์โทร", "หมายเลขโทรศัพท์") to "เบอร์โทร",
        listOf("first_name", "firstname", "ชื่อจริง", "given_name") to "ชื่อจริง",
        listOf("last_name", "lastname", "นามสกุล", "family_name", "surname") to "นามสกุล",
        listOf("full_name", "fullname", "ชื่อเต็ม", "display_name") to "ชื่อ-นามสกุล",
        listOf("address", "ที่อยู่", "addr") to "ที่อยู่",
        listOf("search", "ค้นหา", "query") to "ค้นหา",
        listOf("otp", "verify_code", "verification") to "รหัส OTP",
        listOf("pin", "pincode") to "รหัส PIN",
        listOf("id_card", "citizen", "บัตรประชาชน") to "เลขบัตรประชาชน",
        listOf("zip", "postal", "รหัสไปรษณีย์") to "รหัสไปรษณีย์",
        listOf("comment", "note", "message", "ข้อความ", "หมายเหตุ") to "ข้อความ",
        listOf("amount", "price", "จำนวนเงิน", "ราคา") to "จำนวนเงิน",
        listOf("account", "bank", "บัญชี") to "เลขบัญชี",
        listOf("url", "link", "website") to "ลิงก์",
        listOf("date", "วันที่", "birthday", "วันเกิด") to "วันที่",
    )
}
