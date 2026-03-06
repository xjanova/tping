package com.xjanova.tping.data.license

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.xjanova.tping.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Central license state manager for Tping.
 * Trial/demo always verified from server — no local fallback for trial.
 * Paid licenses allow offline cached fallback.
 */
object LicenseManager {

    private const val PREFS_NAME = "tping_license"
    private const val KEY_LICENSE_KEY = "license_key"
    private const val KEY_LICENSE_TYPE = "license_type"
    private const val KEY_LICENSE_STATUS = "license_status"
    private const val KEY_LICENSE_EXPIRES_AT = "license_expires_at"
    private const val KEY_DEVICE_REGISTERED = "device_registered"
    private const val KEY_DEVICE_ID = "device_id"

    // Purchase URL
    private const val PURCHASE_URL = "https://xmanstudio.com/product/tping"

    private val _state = MutableStateFlow(LicenseState())
    val state: StateFlow<LicenseState> = _state

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    /**
     * Initialize on app start. Must be called from Application.onCreate() or MainActivity.
     */
    suspend fun initialize(context: Context) {
        appContext = context.applicationContext
        prefs = getPrefs(context)

        val deviceId = DeviceManager.getDeviceId(context)
        prefs?.edit()?.putString(KEY_DEVICE_ID, deviceId)?.apply()

        // Set loading state
        _state.value = LicenseState(status = LicenseStatus.CHECKING, deviceId = deviceId, isLoading = true)

        val licenseKey = prefs?.getString(KEY_LICENSE_KEY, "") ?: ""

        if (licenseKey.isNotEmpty()) {
            // Has license key — validate with server (paid license)
            validatePaidLicense(context, licenseKey, deviceId)
        } else {
            // No license key — check demo/trial from server
            checkOrStartDemo(context, deviceId)
        }
    }

    /**
     * Validate paid license (with offline cached fallback).
     */
    private suspend fun validatePaidLicense(context: Context, licenseKey: String, deviceId: String) {
        try {
            val result = withContext(Dispatchers.IO) {
                LicenseApiClient.validateLicense(licenseKey, deviceId)
            }
            if (result.success) {
                // is_valid is at root level, other fields inside "data"
                val valid = result.data.get("is_valid")?.asBoolean ?: false
                val data = result.data.getAsJsonObject("data") ?: result.data
                val type = data.get("license_type")?.asString ?: ""
                val expiresAtStr = data.get("expires_at")?.asString
                val daysRemaining = data.get("days_remaining")?.asInt ?: 0

                val expiresAt = parseIsoTimestamp(expiresAtStr)

                if (valid) {
                    prefs?.edit()
                        ?.putString(KEY_LICENSE_TYPE, type)
                        ?.putString(KEY_LICENSE_STATUS, "active")
                        ?.putLong(KEY_LICENSE_EXPIRES_AT, expiresAt)
                        ?.apply()

                    _state.value = LicenseState(
                        status = LicenseStatus.ACTIVE,
                        licenseType = type,
                        expiresAt = expiresAt,
                        remainingDays = daysRemaining,
                        deviceId = deviceId,
                        isLoading = false
                    )
                } else {
                    prefs?.edit()?.putString(KEY_LICENSE_STATUS, "expired")?.apply()
                    _state.value = LicenseState(
                        status = LicenseStatus.EXPIRED,
                        licenseType = type,
                        deviceId = deviceId,
                        isLoading = false,
                        errorMessage = result.message.ifEmpty { "License หมดอายุ" }
                    )
                }
            } else {
                // Server returned error — use cached state (paid license only)
                useCachedState(deviceId)
            }
        } catch (_: Exception) {
            // Network error — use cached state (paid license only)
            useCachedState(deviceId)
        }
    }

    /**
     * Check demo/trial status from server. If device is new, start demo.
     * No offline fallback — must connect to server.
     */
    private suspend fun checkOrStartDemo(context: Context, deviceId: String) {
        try {
            val checkResult = withContext(Dispatchers.IO) {
                LicenseApiClient.checkDemo(deviceId)
            }

            if (!checkResult.success) {
                // Server error — block usage (no offline trial)
                _state.value = LicenseState(
                    status = LicenseStatus.NONE,
                    deviceId = deviceId,
                    isLoading = false,
                    errorMessage = "ต้องเชื่อมต่ออินเทอร์เน็ตเพื่อตรวจสอบสถานะ"
                )
                return
            }

            val data = checkResult.data.getAsJsonObject("data") ?: checkResult.data
            val hasUsedDemo = data.get("has_used_demo")?.asBoolean ?: false
            val canStartDemo = data.get("can_start_demo")?.asBoolean ?: false
            val isTrialActive = data.get("is_trial_active")?.asBoolean ?: false

            if (isTrialActive) {
                // Trial still active on server — use server's time
                val trialInfo = data.getAsJsonObject("trial_info")
                val daysRemaining = trialInfo?.get("days_remaining")?.asInt ?: 0
                val expiresAtStr = trialInfo?.get("expires_at")?.asString
                val expiresAt = parseIsoTimestamp(expiresAtStr)
                val hoursRemaining = if (expiresAt > 0) {
                    ((expiresAt - System.currentTimeMillis()) / (60 * 60 * 1000)).toInt().coerceAtLeast(0)
                } else {
                    daysRemaining * 24
                }

                _state.value = LicenseState(
                    status = LicenseStatus.TRIAL,
                    licenseType = "trial",
                    expiresAt = expiresAt,
                    remainingDays = daysRemaining,
                    remainingHours = hoursRemaining,
                    deviceId = deviceId,
                    isLoading = false
                )
            } else if (!hasUsedDemo && canStartDemo) {
                // First time — start demo on server
                startDemoOnServer(context, deviceId)
            } else {
                // Trial expired or used up — show gate
                _state.value = LicenseState(
                    status = LicenseStatus.EXPIRED,
                    licenseType = "trial",
                    deviceId = deviceId,
                    isLoading = false
                )
            }
        } catch (_: Exception) {
            // Network error — block usage (no offline trial)
            _state.value = LicenseState(
                status = LicenseStatus.NONE,
                deviceId = deviceId,
                isLoading = false,
                errorMessage = "ต้องเชื่อมต่ออินเทอร์เน็ตเพื่อตรวจสอบสถานะ"
            )
        }
    }

    /**
     * Start demo/trial on server for new device.
     */
    private suspend fun startDemoOnServer(context: Context, deviceId: String) {
        try {
            // Register device first
            withContext(Dispatchers.IO) {
                val deviceName = DeviceManager.getDeviceName()
                val osVersion = DeviceManager.getOsVersion()
                val appVersion = BuildConfig.VERSION_NAME
                val hardwareHash = DeviceManager.getHardwareHash(context)

                LicenseApiClient.registerDevice(
                    machineId = deviceId,
                    machineName = deviceName,
                    osVersion = osVersion,
                    appVersion = appVersion,
                    hardwareHash = hardwareHash
                )
            }

            // Start demo
            val demoResult = withContext(Dispatchers.IO) {
                val hardwareHash = DeviceManager.getHardwareHash(context)
                LicenseApiClient.startDemo(
                    machineId = deviceId,
                    hardwareHash = hardwareHash
                )
            }

            if (demoResult.success) {
                prefs?.edit()?.putBoolean(KEY_DEVICE_REGISTERED, true)?.apply()

                val data = demoResult.data.getAsJsonObject("data") ?: demoResult.data
                val daysRemaining = data.get("days_remaining")?.asInt ?: 7
                val expiresAtStr = data.get("expires_at")?.asString
                val expiresAt = parseIsoTimestamp(expiresAtStr)
                val hoursRemaining = daysRemaining * 24

                _state.value = LicenseState(
                    status = LicenseStatus.TRIAL,
                    licenseType = "trial",
                    expiresAt = expiresAt,
                    remainingDays = daysRemaining,
                    remainingHours = hoursRemaining,
                    deviceId = deviceId,
                    isLoading = false
                )
            } else {
                // Server rejected demo (abuse, blocked, etc.)
                _state.value = LicenseState(
                    status = LicenseStatus.EXPIRED,
                    licenseType = "trial",
                    deviceId = deviceId,
                    isLoading = false,
                    errorMessage = demoResult.message.ifEmpty { "ไม่สามารถเริ่มทดลองใช้ได้" }
                )
            }
        } catch (_: Exception) {
            _state.value = LicenseState(
                status = LicenseStatus.NONE,
                deviceId = deviceId,
                isLoading = false,
                errorMessage = "ต้องเชื่อมต่ออินเทอร์เน็ตเพื่อเริ่มทดลองใช้"
            )
        }
    }

    /**
     * Activate a license key.
     */
    suspend fun activateKey(context: Context, key: String): Result<String> {
        val deviceId = DeviceManager.getDeviceId(context)
        val hardwareHash = DeviceManager.getHardwareHash(context)

        _state.value = _state.value.copy(isLoading = true)

        return try {
            val result = withContext(Dispatchers.IO) {
                LicenseApiClient.activateLicense(key, deviceId, hardwareHash)
            }
            if (result.success) {
                val data = result.data.getAsJsonObject("data") ?: result.data
                val type = data.get("license_type")?.asString ?: "unknown"
                val expiresAtStr = data.get("expires_at")?.asString
                val expiresAt = parseIsoTimestamp(expiresAtStr)
                val daysRemaining = data.get("days_remaining")?.asInt ?: 0

                prefs?.edit()
                    ?.putString(KEY_LICENSE_KEY, key)
                    ?.putString(KEY_LICENSE_TYPE, type)
                    ?.putString(KEY_LICENSE_STATUS, "active")
                    ?.putLong(KEY_LICENSE_EXPIRES_AT, expiresAt)
                    ?.apply()

                _state.value = LicenseState(
                    status = LicenseStatus.ACTIVE,
                    licenseType = type,
                    expiresAt = expiresAt,
                    remainingDays = daysRemaining,
                    deviceId = deviceId,
                    isLoading = false
                )
                Result.success(getLicenseTypeDisplay(type))
            } else {
                _state.value = _state.value.copy(isLoading = false, errorMessage = result.message)
                Result.failure(Exception(result.message.ifEmpty { "ไม่สามารถเปิดใช้งานคีย์ได้" }))
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false, errorMessage = e.localizedMessage ?: "เกิดข้อผิดพลาด")
            Result.failure(e)
        }
    }

    // ---- Queries ----

    fun isLicenseValid(): Boolean {
        val s = _state.value
        return s.status == LicenseStatus.ACTIVE || s.status == LicenseStatus.TRIAL
    }

    fun shouldShowGate(): Boolean {
        val s = _state.value
        return s.status == LicenseStatus.EXPIRED || s.status == LicenseStatus.NONE
    }

    fun isTrialActive(): Boolean = _state.value.status == LicenseStatus.TRIAL

    fun isNearExpiry(): Boolean {
        val s = _state.value
        return s.status == LicenseStatus.ACTIVE && s.remainingDays in 1..7
    }

    fun getPurchaseUrl(): String = PURCHASE_URL

    fun getLicenseTypeDisplay(type: String = _state.value.licenseType): String {
        return when (type) {
            "lifetime" -> "ตลอดชีพ"
            "yearly" -> "รายปี"
            "monthly" -> "รายเดือน"
            "demo", "trial" -> "ทดลองใช้"
            else -> type
        }
    }

    fun getDeviceId(): String = prefs?.getString(KEY_DEVICE_ID, "") ?: ""

    // ---- Private helpers ----

    /**
     * Use cached state — only for paid licenses (offline fallback).
     * Trial/demo users must connect to server.
     */
    private fun useCachedState(deviceId: String) {
        val cachedStatus = prefs?.getString(KEY_LICENSE_STATUS, "") ?: ""
        val cachedType = prefs?.getString(KEY_LICENSE_TYPE, "") ?: ""
        val cachedExpiry = prefs?.getLong(KEY_LICENSE_EXPIRES_AT, 0L) ?: 0L
        val now = System.currentTimeMillis()

        // Only allow offline for paid licenses (not demo/trial)
        if (cachedStatus == "active" && cachedType != "demo" && cachedType != "trial" &&
            (cachedExpiry == 0L || cachedExpiry > now)) {
            val days = if (cachedExpiry > 0) ((cachedExpiry - now) / (24 * 60 * 60 * 1000)).toInt() else 999
            _state.value = LicenseState(
                status = LicenseStatus.ACTIVE,
                licenseType = cachedType,
                expiresAt = cachedExpiry,
                remainingDays = days,
                deviceId = deviceId,
                isLoading = false
            )
        } else {
            // No valid cached paid license — need connection
            _state.value = LicenseState(
                status = LicenseStatus.NONE,
                deviceId = deviceId,
                isLoading = false,
                errorMessage = "ต้องเชื่อมต่ออินเทอร์เน็ตเพื่อตรวจสอบสถานะ"
            )
        }
    }

    /**
     * Parse ISO 8601 timestamp to epoch millis. Returns 0 if null/invalid.
     */
    private fun parseIsoTimestamp(isoString: String?): Long {
        if (isoString.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(isoString.replace(Regex("\\.[0-9]+Z$"), "").replace("Z", ""))
                ?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKeyAlias,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // Fallback to regular SharedPreferences if encryption fails
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
