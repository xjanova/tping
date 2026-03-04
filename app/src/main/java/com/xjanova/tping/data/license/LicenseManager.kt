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
 * Handles device registration, trial management, and license validation.
 */
object LicenseManager {

    private const val PREFS_NAME = "tping_license"
    private const val KEY_LICENSE_KEY = "license_key"
    private const val KEY_LICENSE_TYPE = "license_type"
    private const val KEY_LICENSE_STATUS = "license_status"
    private const val KEY_LICENSE_EXPIRES_AT = "license_expires_at"
    private const val KEY_FIRST_LAUNCH_AT = "first_launch_at"
    private const val KEY_TRIAL_EXPIRES_AT = "trial_expires_at"
    private const val KEY_DEVICE_REGISTERED = "device_registered"
    private const val KEY_DEVICE_ID = "device_id"

    // 1 day trial in milliseconds
    private const val TRIAL_DURATION_MS = 24 * 60 * 60 * 1000L

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

        // Check if first launch
        val firstLaunch = prefs?.getLong(KEY_FIRST_LAUNCH_AT, 0L) ?: 0L
        if (firstLaunch == 0L) {
            // First launch — register device and start trial
            prefs?.edit()?.putLong(KEY_FIRST_LAUNCH_AT, System.currentTimeMillis())?.apply()
            val trialExpiry = System.currentTimeMillis() + TRIAL_DURATION_MS
            prefs?.edit()?.putLong(KEY_TRIAL_EXPIRES_AT, trialExpiry)?.apply()

            // Register device in background
            registerDeviceAsync(context)

            _state.value = LicenseState(
                status = LicenseStatus.TRIAL,
                licenseType = "trial",
                expiresAt = trialExpiry,
                remainingHours = 24,
                deviceId = deviceId,
                isLoading = false
            )
        } else {
            // Subsequent launch — validate
            validateOnStartup(context)
        }
    }

    /**
     * Validate license state on app startup.
     */
    suspend fun validateOnStartup(context: Context) {
        val deviceId = DeviceManager.getDeviceId(context)
        val licenseKey = prefs?.getString(KEY_LICENSE_KEY, "") ?: ""
        val trialExpiresAt = prefs?.getLong(KEY_TRIAL_EXPIRES_AT, 0L) ?: 0L
        val now = System.currentTimeMillis()

        if (licenseKey.isNotEmpty()) {
            // Has license key — validate with server
            _state.value = _state.value.copy(isLoading = true, deviceId = deviceId)
            try {
                val result = withContext(Dispatchers.IO) {
                    LicenseApiClient.validateLicense(licenseKey, deviceId)
                }
                if (result.success) {
                    val data = result.data
                    val valid = data.get("valid")?.asBoolean ?: false
                    val type = data.get("license_type")?.asString ?: ""
                    val expiresAt = data.get("expires_at")?.asLong ?: 0L
                    val daysRemaining = data.get("days_remaining")?.asInt ?: 0

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
                            errorMessage = result.message
                        )
                    }
                } else {
                    // Server error — use cached state
                    useCachedState(deviceId)
                }
            } catch (_: Exception) {
                useCachedState(deviceId)
            }
        } else if (trialExpiresAt > 0 && now < trialExpiresAt) {
            // Trial still active
            val hoursLeft = ((trialExpiresAt - now) / (60 * 60 * 1000)).toInt().coerceAtLeast(0)
            _state.value = LicenseState(
                status = LicenseStatus.TRIAL,
                licenseType = "trial",
                expiresAt = trialExpiresAt,
                remainingHours = hoursLeft,
                deviceId = deviceId,
                isLoading = false
            )
        } else if (trialExpiresAt > 0) {
            // Trial expired
            _state.value = LicenseState(
                status = LicenseStatus.EXPIRED,
                licenseType = "trial",
                deviceId = deviceId,
                isLoading = false
            )
        } else {
            // No license, no trial
            _state.value = LicenseState(
                status = LicenseStatus.NONE,
                deviceId = deviceId,
                isLoading = false
            )
        }
    }

    /**
     * Activate a license key.
     */
    suspend fun activateKey(context: Context, key: String): Result<String> {
        val deviceId = DeviceManager.getDeviceId(context)
        val deviceName = DeviceManager.getDeviceName()

        _state.value = _state.value.copy(isLoading = true)

        return try {
            val result = withContext(Dispatchers.IO) {
                LicenseApiClient.activateLicense(key, deviceId, deviceName)
            }
            if (result.success) {
                val data = result.data
                val type = data.get("license_type")?.asString
                    ?: data.getAsJsonObject("license")?.get("license_type")?.asString
                    ?: "unknown"
                val expiresAt = data.get("expires_at")?.asLong
                    ?: data.getAsJsonObject("license")?.get("expires_at")?.asLong
                    ?: 0L
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

    fun isTrialActive(): Boolean = _state.value.status == LicenseStatus.TRIAL

    fun isTrialExpired(): Boolean {
        val trialExpiry = prefs?.getLong(KEY_TRIAL_EXPIRES_AT, 0L) ?: 0L
        return trialExpiry > 0 && System.currentTimeMillis() >= trialExpiry && _state.value.status != LicenseStatus.ACTIVE
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

    private fun useCachedState(deviceId: String) {
        val cachedStatus = prefs?.getString(KEY_LICENSE_STATUS, "") ?: ""
        val cachedType = prefs?.getString(KEY_LICENSE_TYPE, "") ?: ""
        val cachedExpiry = prefs?.getLong(KEY_LICENSE_EXPIRES_AT, 0L) ?: 0L
        val now = System.currentTimeMillis()

        if (cachedStatus == "active" && (cachedExpiry == 0L || cachedExpiry > now)) {
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
            _state.value = LicenseState(
                status = LicenseStatus.EXPIRED,
                deviceId = deviceId,
                isLoading = false,
                errorMessage = "ไม่สามารถตรวจสอบไลเซนส์ได้"
            )
        }
    }

    private suspend fun registerDeviceAsync(context: Context) {
        try {
            withContext(Dispatchers.IO) {
                val deviceId = DeviceManager.getDeviceId(context)
                val deviceName = DeviceManager.getDeviceName()
                val osVersion = DeviceManager.getOsVersion()
                val appVersion = BuildConfig.VERSION_NAME
                val hardwareHash = DeviceManager.getHardwareHash(context)

                val result = LicenseApiClient.registerDevice(
                    machineId = deviceId,
                    machineName = deviceName,
                    osVersion = osVersion,
                    appVersion = appVersion,
                    hardwareHash = hardwareHash
                )
                if (result.success) {
                    prefs?.edit()?.putBoolean(KEY_DEVICE_REGISTERED, true)?.apply()
                }

                // Also start demo on the backend
                LicenseApiClient.startDemo(
                    machineId = deviceId,
                    machineName = deviceName,
                    osVersion = osVersion,
                    appVersion = appVersion
                )
            }
        } catch (_: Exception) {
            // Silently fail — trial is tracked locally
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
